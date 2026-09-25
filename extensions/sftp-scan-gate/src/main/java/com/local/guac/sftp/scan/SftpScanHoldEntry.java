package com.local.guac.sftp.scan;

public class SftpScanHoldEntry {

    public enum Phase {
        POLLING,
        READY,
        REJECTED,
        TRANSFERRING,
        COMPLETED
    }

    private final String sessionId;
    private final String eventId;
    private final SftpScanHoldMetadata metadata;
    private volatile Phase phase = Phase.POLLING;
    private volatile String scanStatus;
    private volatile String userMessage;
    private volatile int pollAfterSeconds = SftpScanConfig.DEFAULT_POLL_AFTER_SEC;

    public SftpScanHoldEntry(String sessionId, String eventId,
            SftpScanHoldMetadata metadata, SftpScanResult initial) {
        this.sessionId = sessionId;
        this.eventId = eventId;
        this.metadata = metadata;
        this.scanStatus = initial.getScanStatus();
        this.userMessage = initial.getUserMessage();
        this.pollAfterSeconds = initial.getPollAfterSeconds();
    }

    public void applyResult(SftpScanResult result) {
        scanStatus = result.getScanStatus();
        userMessage = result.getUserMessage();
        pollAfterSeconds = result.getPollAfterSeconds();
        if (result.isHeld()) {
            phase = Phase.POLLING;
        }
        else if (SftpScanStatus.isTerminalAllow(result.getScanStatus(), result.isAllowed())) {
            phase = Phase.READY;
        }
        else {
            phase = Phase.REJECTED;
        }
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getEventId() {
        return eventId;
    }

    public SftpScanHoldMetadata getMetadata() {
        return metadata;
    }

    public Phase getPhase() {
        return phase;
    }

    public void setPhase(Phase phase) {
        this.phase = phase;
    }

    public String getScanStatus() {
        return scanStatus;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public int getPollAfterSeconds() {
        return pollAfterSeconds;
    }
}
