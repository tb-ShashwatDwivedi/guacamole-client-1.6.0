package com.local.guac.sftp.scan;

public class SftpScanDecision {

    public static final String WARNING_HEADER = "X-Scan-User-Message";

    public enum Kind {
        PROCEED,
        PROCEED_WARNING,
        BLOCK,
        HELD
    }

    private final Kind kind;
    private final String warningMessage;
    private final String eventId;
    private final SftpScanResult result;

    private SftpScanDecision(Kind kind, String warningMessage, String eventId,
            SftpScanResult result) {
        this.kind = kind;
        this.warningMessage = warningMessage;
        this.eventId = eventId;
        this.result = result;
    }

    public static SftpScanDecision proceed(String eventId) {
        return new SftpScanDecision(Kind.PROCEED, null, eventId, null);
    }

    public static SftpScanDecision proceedWithWarning(String warningMessage, String eventId) {
        return new SftpScanDecision(Kind.PROCEED_WARNING, warningMessage, eventId, null);
    }

    public static SftpScanDecision block() {
        return new SftpScanDecision(Kind.BLOCK, null, null, null);
    }

    public static SftpScanDecision held(SftpScanResult result) {
        return new SftpScanDecision(Kind.HELD, result.getUserMessage(), result.getEventId(), result);
    }

    public Kind getKind() {
        return kind;
    }

    public boolean isAllowed() {
        return kind == Kind.PROCEED || kind == Kind.PROCEED_WARNING;
    }

    public boolean isHeld() {
        return kind == Kind.HELD;
    }

    public boolean hasWarning() {
        return kind == Kind.PROCEED_WARNING && warningMessage != null && !warningMessage.isBlank();
    }

    public String getWarningMessage() {
        return warningMessage;
    }

    public String getEventId() {
        return eventId;
    }

    public SftpScanResult getResult() {
        return result;
    }
}
