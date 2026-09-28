package com.local.guac.sftp.scan;

import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.language.TranslatableMessage;
import org.apache.guacamole.net.auth.ActiveConnection;
import org.apache.guacamole.net.auth.Connection;
import org.apache.guacamole.net.auth.UserContext;
import org.apache.guacamole.protocol.GuacamoleStatus;
import org.apache.guacamole.tunnel.UserTunnel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

public final class SftpScanGate {

    private static final String UNTRANSLATED_MESSAGE_KEY = "APP.TEXT_UNTRANSLATED";
    private static final String UNTRANSLATED_MESSAGE_VARIABLE = "MESSAGE";

    private static final Logger logger = LoggerFactory.getLogger(SftpScanGate.class);

    private SftpScanGate() {
    }

    public static boolean shouldScan(UserTunnel tunnel) {
        if (!SftpScanConfig.SCAN_ENABLED) {
            return false;
        }

        try {
            if (!"ssh".equalsIgnoreCase(tunnel.getSocket().getProtocol())) {
                return false;
            }
        }
        catch (Exception e) {
            logger.debug("Unable to determine tunnel protocol; skipping scan", e);
            return false;
        }

        Boolean enableSftp = resolveEnableSftp(tunnel);
        if (enableSftp == null) {
            logger.info("SFTP scan: enable-sftp unknown for tunnel {}; scanning SSH transfer",
                    tunnel.getUUID());
            return true;
        }
        if (!enableSftp) {
            logger.debug("SFTP scan skipped: enable-sftp is false for tunnel {}", tunnel.getUUID());
            return false;
        }
        return true;
    }

    private static Boolean resolveEnableSftp(UserTunnel tunnel) {
        try {
            ActiveConnection activeConnection = tunnel.getActiveConnection();
            if (activeConnection == null) {
                return null;
            }

            UserContext userContext = tunnel.getUserContext();
            Connection connection = userContext.getConnectionDirectory()
                    .get(activeConnection.getConnectionIdentifier());
            if (connection == null || connection.getConfiguration() == null) {
                return null;
            }

            String enableSftp = connection.getConfiguration().getParameter("enable-sftp");
            if (enableSftp == null || enableSftp.isBlank()) {
                return null;
            }
            return "true".equalsIgnoreCase(enableSftp);
        }
        catch (GuacamoleException e) {
            logger.debug("Unable to determine SFTP status for tunnel {}", tunnel.getUUID(), e);
            return null;
        }
    }

    public static SftpScanDecision evaluateScan(SftpScanRequest request)
            throws SftpScanBlockedException {

        SftpScanLifecycle.ensureStarted();

        if (!SftpScanConfig.SCAN_ENABLED) {
            return SftpScanDecision.proceed(null);
        }

        if (request.getFileBytes().length > SftpScanConfig.DEFAULT_MAX_SCAN_BYTES) {
            throw blockedException("File exceeds maximum scan size.", request, null);
        }

        logger.info("SFTP scan starting: user={}, session={}, direction={}, file={}, bytes={}",
                request.getUsername(), request.getSessionId(), request.getDirection(),
                request.getFilename(), request.getFileBytes().length);

        SftpScanResult result = SftpScanHoldService.client().scan(request);

        if (result.isNoScanner()) {
            logger.info("Scan skipped (no scanner): user={}, session={}, file={}",
                    request.getUsername(), request.getSessionId(), request.getFilename());
            return SftpScanDecision.proceed(null);
        }

        if (result.isHeld()) {
            if (result.getEventId() == null || result.getEventId().isBlank()) {
                throw blockedException(
                        "Scan hold is missing eventId. File was not transferred.",
                        request, result);
            }
            logger.info("Scan hold required: user={}, session={}, file={}, eventId={}, status={}",
                    request.getUsername(), request.getSessionId(), request.getFilename(),
                    result.getEventId(), result.getScanStatus());
            return SftpScanDecision.held(result);
        }

        if (result.isAllowed()) {
            if (result.isWarning()) {
                logger.warn("Scan passed with policy warning: user={}, session={}, file={}, message={}",
                        request.getUsername(), request.getSessionId(), request.getFilename(),
                        result.getUserMessage());
                return SftpScanDecision.proceedWithWarning(result.getUserMessage(),
                        result.getEventId());
            }
            logger.info("Scan passed: user={}, session={}, file={}, status={}, eventId={}",
                    request.getUsername(), request.getSessionId(), request.getFilename(),
                    result.getScanStatus(), result.getEventId());
            return SftpScanDecision.proceed(result.getEventId());
        }

        logger.warn("SFTP transfer blocked by scan: user={}, session={}, file={}, status={}, message={}",
                request.getUsername(), request.getSessionId(), request.getFilename(),
                result.getScanStatus(), result.getUserMessage());

        throw blockedException(result.getUserMessage(), request, result);
    }

    /**
     * Downloads are not malware-scanned; only logged as outbound transfers.
     * Runs off the request thread so audit latency never blocks the file response.
     */
    public static void auditOutboundDownload(SftpScanRequest request) {
        long size = request.getFileBytes() != null ? request.getFileBytes().length : 0;
        auditOutboundDownload(request, size);
    }

    public static void auditOutboundDownload(SftpScanRequest request, long fileSizeBytes) {
        Thread auditThread = new Thread(() -> {
            try {
                SftpScanHoldService.client().auditOutbound(request, fileSizeBytes);
            }
            catch (Exception e) {
                logger.warn("Outbound download audit failed: {}", e.getMessage());
            }
        }, "sftp-audit-outbound");
        auditThread.setDaemon(true);
        auditThread.start();
    }

    public static void reportCompleted(String eventId) {
        SftpScanHoldService.client().reportOutcome(eventId, "completed", null);
    }

    public static void reportFailed(String eventId, String errorMessage) {
        SftpScanHoldService.client().reportOutcome(eventId, "failed", errorMessage);
    }

    public static void reportExpired(String eventId) {
        SftpScanHoldService.client().reportOutcome(eventId, "expired", null);
    }

    public static void onSessionClosed(String sessionId) {
        logger.info("SFTP session closed — detaching scan holds (24h PAM review TTL): session={}",
                sessionId);
        SftpScanHoldService.detachSession(sessionId);
    }

    private static SftpScanBlockedException blockedException(String userMessage,
            SftpScanRequest request, SftpScanResult result) {

        String message = userMessage;
        if (message == null || message.isBlank()) {
            message = "File transfer blocked by malware scan.";
        }

        GuacamoleStatus status = GuacamoleStatus.CLIENT_FORBIDDEN;
        if (result != null && SftpScanResult.REASON_SCAN_ERROR.equals(result.getReason())
                && !result.isAllowed()) {
            status = GuacamoleStatus.UPSTREAM_UNAVAILABLE;
        }

        return new SftpScanBlockedException(status,
                userMessageTranslatable(message),
                message);
    }

    public static TranslatableMessage userMessageTranslatable(String userMessage) {
        return new TranslatableMessage(UNTRANSLATED_MESSAGE_KEY,
                Collections.singletonMap(UNTRANSLATED_MESSAGE_VARIABLE, userMessage));
    }
}
