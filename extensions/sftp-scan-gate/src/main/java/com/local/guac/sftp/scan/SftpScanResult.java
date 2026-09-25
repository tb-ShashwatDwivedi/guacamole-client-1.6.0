package com.local.guac.sftp.scan;

public class SftpScanResult {

    public static final String REASON_CLEAN = "clean";
    public static final String REASON_SCAN_ERROR = "scan_error";
    public static final String REASON_MALICIOUS = "malicious";

    public static final String ACTION_ALLOWED = "allowed";
    public static final String ACTION_BLOCKED = "blocked";
    public static final String ACTION_SKIPPED = "skipped";

    private final boolean allowed;
    private final boolean noScanner;
    private final boolean warning;
    private final boolean terminal;
    private final boolean held;
    private final String userMessage;
    private final String reason;
    private final String actionTaken;
    private final String threat;
    private final String eventId;
    private final String scanStatus;
    private final int pollAfterSeconds;

    private SftpScanResult(boolean allowed, boolean noScanner, boolean warning,
            boolean terminal, boolean held, String userMessage, String reason,
            String actionTaken, String threat, String eventId, String scanStatus,
            int pollAfterSeconds) {
        this.allowed = allowed;
        this.noScanner = noScanner;
        this.warning = warning;
        this.terminal = terminal;
        this.held = held;
        this.userMessage = userMessage;
        this.reason = reason;
        this.actionTaken = actionTaken;
        this.threat = threat;
        this.eventId = eventId;
        this.scanStatus = scanStatus;
        this.pollAfterSeconds = pollAfterSeconds;
    }

    public static SftpScanResult noScannerProceed() {
        return new SftpScanResult(true, true, false, true, false, null, null,
                ACTION_SKIPPED, null, null, null, SftpScanConfig.DEFAULT_POLL_AFTER_SEC);
    }

    public static SftpScanResult proceedSilently(String eventId, String reason,
            String actionTaken, String scanStatus) {
        return new SftpScanResult(true, false, false, true, false, null,
                reason != null ? reason : REASON_CLEAN,
                actionTaken != null ? actionTaken : ACTION_ALLOWED, null, eventId,
                scanStatus != null ? scanStatus : SftpScanStatus.ALLOWED,
                SftpScanConfig.DEFAULT_POLL_AFTER_SEC);
    }

    public static SftpScanResult proceedWithWarning(String userMessage, String eventId,
            String reason, String actionTaken, String scanStatus) {
        return new SftpScanResult(true, false, true, true, false, userMessage,
                reason != null ? reason : REASON_SCAN_ERROR,
                actionTaken != null ? actionTaken : ACTION_SKIPPED, null, eventId,
                scanStatus != null ? scanStatus : SftpScanStatus.SCAN_ERROR,
                SftpScanConfig.DEFAULT_POLL_AFTER_SEC);
    }

    public static SftpScanResult held(String eventId, String scanStatus, int pollAfterSeconds,
            String userMessage) {
        return new SftpScanResult(false, false, false, false, true, userMessage,
                null, null, null, eventId, scanStatus, pollAfterSeconds);
    }

    public static SftpScanResult blocked(String userMessage, String reason, String threat,
            String eventId, String scanStatus) {
        return new SftpScanResult(false, false, false, true, false, userMessage, reason,
                ACTION_BLOCKED, threat, eventId,
                scanStatus != null ? scanStatus : SftpScanStatus.BLOCKED,
                SftpScanConfig.DEFAULT_POLL_AFTER_SEC);
    }

    public static SftpScanResult fromStatusPoll(String eventId, boolean terminal,
            boolean allowed, String scanStatus, int pollAfterSeconds, String userMessage) {
        if (!terminal) {
            return held(eventId, scanStatus, pollAfterSeconds, userMessage);
        }
        if (SftpScanStatus.isTerminalAllow(scanStatus, allowed)) {
            if (SftpScanStatus.SCAN_ERROR.equals(scanStatus) && allowed) {
                return proceedWithWarning(userMessage, eventId, REASON_SCAN_ERROR,
                        ACTION_SKIPPED, scanStatus);
            }
            return proceedSilently(eventId, REASON_CLEAN, ACTION_ALLOWED, scanStatus);
        }
        return blocked(userMessage, scanStatus, null, eventId, scanStatus);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public boolean isNoScanner() {
        return noScanner;
    }

    public boolean isWarning() {
        return warning;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public boolean isHeld() {
        return held;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public String getReason() {
        return reason;
    }

    public String getActionTaken() {
        return actionTaken;
    }

    public String getThreat() {
        return threat;
    }

    public String getEventId() {
        return eventId;
    }

    public String getScanStatus() {
        return scanStatus;
    }

    public int getPollAfterSeconds() {
        return pollAfterSeconds;
    }
}
