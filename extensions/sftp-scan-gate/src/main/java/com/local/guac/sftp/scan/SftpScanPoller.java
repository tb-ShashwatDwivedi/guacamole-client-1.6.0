package com.local.guac.sftp.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class SftpScanPoller {

    private static final Logger logger = LoggerFactory.getLogger(SftpScanPoller.class);
    private static final ScheduledExecutorService EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "sftp-scan-poller");
                thread.setDaemon(true);
                return thread;
            });
    private static final ConcurrentHashMap<String, ScheduledFuture<?>> TASKS =
            new ConcurrentHashMap<>();

    private SftpScanPoller() {
    }

    public static void schedulePoll(String eventId) {
        cancelPoll(eventId);
        SftpScanHoldEntry entry = SftpScanHoldRegistry.get(eventId);
        int delaySec = entry != null ? entry.getPollAfterSeconds()
                : SftpScanConfig.DEFAULT_POLL_AFTER_SEC;
        ScheduledFuture<?> future = EXECUTOR.schedule(() -> pollOnce(eventId),
                delaySec, TimeUnit.SECONDS);
        TASKS.put(eventId, future);
    }

    public static void cancelPoll(String eventId) {
        ScheduledFuture<?> future = TASKS.remove(eventId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private static void pollOnce(String eventId) {
        SftpScanHoldEntry entry = SftpScanHoldRegistry.get(eventId);
        if (entry == null) {
            return;
        }
        if (entry.getPhase() == SftpScanHoldEntry.Phase.COMPLETED
                || entry.getPhase() == SftpScanHoldEntry.Phase.REJECTED
                || entry.getPhase() == SftpScanHoldEntry.Phase.TRANSFERRING) {
            return;
        }

        try {
            SftpScanResult result = SftpScanHoldService.client().pollStatus(eventId);
            logger.info("Scan poll: eventId={}, terminal={}, status={}, allowed={}",
                    eventId, result.isTerminal(), result.getScanStatus(), result.isAllowed());
            entry.applyResult(result);

            if (result.isHeld()) {
                schedulePoll(eventId);
                return;
            }

            if (SftpScanStatus.isTerminalAllow(result.getScanStatus(), result.isAllowed())) {
                entry.setPhase(SftpScanHoldEntry.Phase.READY);
                cancelPoll(eventId);
                return;
            }

            entry.setPhase(SftpScanHoldEntry.Phase.REJECTED);
            cancelPoll(eventId);
            SftpScanHoldService.deleteHeldBytes(entry.getSessionId(), eventId);
        }
        catch (Exception e) {
            logger.warn("Scan poll failed for eventId={}: {}", eventId, e.getMessage());
            schedulePoll(eventId);
        }
    }

    public static void shutdown() {
        TASKS.values().forEach(f -> f.cancel(false));
        TASKS.clear();
        EXECUTOR.shutdownNow();
    }
}
