package com.local.guac.sftp.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

public final class SftpScanHoldService {

    private static final Logger logger = LoggerFactory.getLogger(SftpScanHoldService.class);
    private static final SftpScanHoldStore STORE = new SftpScanHoldStore();
    private static final SftpScanClient CLIENT = new SftpScanClient();

    static {
        SftpScanLifecycle.ensureStarted();
    }

    private SftpScanHoldService() {
    }

    public static SftpScanHoldEntry createHold(SftpScanRequest request, int streamIndex,
            String tunnelUuid, SftpScanResult scanResult, byte[] fileBytes) throws IOException {

        String eventId = scanResult.getEventId();
        if (eventId == null || eventId.isBlank()) {
            throw new IOException("Scan hold requires eventId");
        }

        SftpScanHoldMetadata metadata = SftpScanHoldMetadata.fromRequest(request, streamIndex,
                tunnelUuid);
        metadata.setEventId(eventId);

        STORE.writeHold(request.getSessionId(), eventId, fileBytes, metadata);

        SftpScanHoldEntry entry = new SftpScanHoldEntry(request.getSessionId(), eventId,
                metadata, scanResult);
        SftpScanHoldRegistry.register(entry);
        SftpScanPoller.schedulePoll(eventId);
        logger.info("Scan hold created: session={}, eventId={}, status={}, bytes={}",
                request.getSessionId(), eventId, scanResult.getScanStatus(), fileBytes.length);
        return entry;
    }

    public static SftpScanHoldEntry getEntry(String eventId) {
        return SftpScanHoldRegistry.get(eventId);
    }

    public static boolean holdFileExists(String sessionId, String eventId) {
        return STORE.holdExists(sessionId, eventId);
    }

    public static byte[] readHeldBytes(String sessionId, String eventId) throws IOException {
        return STORE.readHoldBytes(sessionId, eventId);
    }

    public static java.nio.file.Path heldFilePath(String sessionId, String eventId) {
        return STORE.holdFilePath(sessionId, eventId);
    }

    public static SftpScanHoldMetadata readMetadata(String sessionId, String eventId)
            throws IOException {
        return STORE.readMetadata(sessionId, eventId);
    }

    public static void deleteHeldBytes(String sessionId, String eventId) {
        STORE.deleteHold(sessionId, eventId);
        logger.info("Scan hold bytes deleted: session={}, eventId={}", sessionId, eventId);
    }

    public static void discardHold(String sessionId, String eventId, String outcomeStatus) {
        discardHold(sessionId, eventId, outcomeStatus, null);
    }

    public static void discardHold(String sessionId, String eventId,
            String outcomeStatus, String errorMessage) {
        deleteHeldBytes(sessionId, eventId);
        SftpScanHoldRegistry.remove(sessionId, eventId);
        SftpScanPoller.cancelPoll(eventId);
        if (eventId != null && outcomeStatus != null) {
            CLIENT.reportOutcome(eventId, outcomeStatus, errorMessage);
        }
        logger.info("Scan hold discarded: session={}, eventId={}, outcome={}",
                sessionId, eventId, outcomeStatus);
    }

    /**
     * User cancelled from Guacamole. Local hold cleanup is best-effort; PAM outcome PATCH is mandatory.
     */
    public static void cancelHoldByUser(String sessionId, String eventId) {
        SftpScanPoller.cancelPoll(eventId);
        if (STORE.holdExists(sessionId, eventId)) {
            deleteHeldBytes(sessionId, eventId);
        }
        SftpScanHoldRegistry.remove(sessionId, eventId);
        boolean patched = CLIENT.reportOutcome(eventId, "failed",
                SftpScanConfig.CANCELLED_BY_USER_MESSAGE,
                SftpScanConfig.OUTCOME_PATCH_MAX_ATTEMPTS);
        if (!patched) {
            logger.error(
                    "Cancel transfer: PAM outcome PATCH failed after retries: session={}, eventId={}",
                    sessionId, eventId);
        }
        logger.info("Scan hold cancelled by user: session={}, eventId={}, pamPatched={}",
                sessionId, eventId, patched);
    }

    /**
     * Tunnel/browser closed: stop polling and drop in-memory state. PAM stays pending until
     * admin action or {@link SftpScanConfig#getHoldTtlMs()} sweeper expires the event.
     */
    public static void detachSession(String sessionId) {
        List<SftpScanHoldEntry> entries = SftpScanHoldRegistry.listSession(sessionId);
        java.util.LinkedHashSet<String> eventIds = new java.util.LinkedHashSet<>();
        for (SftpScanHoldEntry entry : entries) {
            if (entry.getPhase() != SftpScanHoldEntry.Phase.COMPLETED) {
                eventIds.add(entry.getEventId());
            }
        }
        for (String eventId : eventIds) {
            SftpScanPoller.cancelPoll(eventId);
        }
        SftpScanHoldRegistry.removeSession(sessionId);
        logger.info("Scan hold session detached (PAM review unchanged): session={}, holds={}",
                sessionId, eventIds.size());
    }

    /** @deprecated use {@link #detachSession} on user disconnect; sweeper calls {@link #discardHold}. */
    public static void cleanupSession(String sessionId) {
        detachSession(sessionId);
    }

    public static void markCompleted(String sessionId, String eventId) {
        SftpScanHoldEntry entry = SftpScanHoldRegistry.get(eventId);
        if (entry != null) {
            entry.setPhase(SftpScanHoldEntry.Phase.COMPLETED);
        }
        STORE.deleteHold(sessionId, eventId);
        SftpScanHoldRegistry.remove(sessionId, eventId);
        SftpScanPoller.cancelPoll(eventId);
    }

    public static SftpScanHoldStore store() {
        return STORE;
    }

    public static SftpScanClient client() {
        return CLIENT;
    }
}
