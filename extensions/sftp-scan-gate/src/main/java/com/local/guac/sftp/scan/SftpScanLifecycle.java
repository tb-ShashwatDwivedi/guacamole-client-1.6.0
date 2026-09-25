package com.local.guac.sftp.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class SftpScanLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(SftpScanLifecycle.class);
    private static final SftpScanHoldStore STORE = new SftpScanHoldStore();
    private static final SftpScanClient CLIENT = new SftpScanClient();
    private static volatile boolean started = false;
    private static ScheduledExecutorService sweeper;

    private SftpScanLifecycle() {
    }

    public static synchronized void ensureStarted() {
        if (started) {
            return;
        }
        started = true;
        startupPurge();
        Runtime.getRuntime().addShutdownHook(new Thread(SftpScanLifecycle::gracefulShutdown,
                "sftp-scan-shutdown"));
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "sftp-scan-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        sweeper.scheduleAtFixedRate(SftpScanLifecycle::sweepExpired,
                SftpScanConfig.SWEEPER_INTERVAL_SEC,
                SftpScanConfig.SWEEPER_INTERVAL_SEC,
                TimeUnit.SECONDS);
        logger.info("SFTP scan hold lifecycle started (dir={})", SftpScanConfig.getHoldDir());
    }

    private static void startupPurge() {
        // Fresh JVM has no registry; do not expire every PAM row on redeploy — only 24h+ disk holds.
        SftpScanHoldRegistry.clearAll();
        try {
            sweepExpired();
        }
        catch (Exception e) {
            logger.warn("Scan hold startup sweep failed: {}", e.getMessage());
        }
    }

    private static void sweepExpired() {
        try {
            List<SftpScanHoldStore.ExpiredHold> expired =
                    STORE.findExpiredHolds(SftpScanConfig.getHoldTtlMs());
            for (SftpScanHoldStore.ExpiredHold hold : expired) {
                SftpScanHoldService.discardHold(hold.getSessionId(), hold.getEventId(), "expired");
            }
        }
        catch (Exception e) {
            logger.warn("Scan hold sweeper failed: {}", e.getMessage());
        }
    }

    private static void gracefulShutdown() {
        logger.info("SFTP scan graceful shutdown: expiring active holds");
        for (SftpScanHoldEntry entry : SftpScanHoldRegistry.listActive()) {
            SftpScanHoldService.discardHold(entry.getSessionId(), entry.getEventId(), "expired");
        }
        SftpScanPoller.shutdown();
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
    }
}
