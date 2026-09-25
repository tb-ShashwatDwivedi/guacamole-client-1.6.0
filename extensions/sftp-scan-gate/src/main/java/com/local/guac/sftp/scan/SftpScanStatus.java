package com.local.guac.sftp.scan;

public final class SftpScanStatus {

    public static final String SANDBOX_QUEUED = "sandbox_queued";
    public static final String SANDBOX_RUNNING = "sandbox_running";
    public static final String PENDING_REVIEW = "pending_review";
    public static final String ALLOWED = "allowed";
    public static final String APPROVED = "approved";
    public static final String BLOCKED = "blocked";
    public static final String REJECTED = "rejected";
    public static final String EXPIRED = "expired";
    public static final String SCAN_ERROR = "scan_error";

    private SftpScanStatus() {
    }

    public static boolean isNonTerminal(String scanStatus) {
        return SANDBOX_QUEUED.equals(scanStatus)
                || SANDBOX_RUNNING.equals(scanStatus)
                || PENDING_REVIEW.equals(scanStatus);
    }

    public static boolean isTerminalAllow(String scanStatus, boolean allowed) {
        if (allowed) {
            return true;
        }
        if (ALLOWED.equals(scanStatus) || APPROVED.equals(scanStatus)
                || "bypassed".equals(scanStatus)) {
            return true;
        }
        return SCAN_ERROR.equals(scanStatus) && allowed;
    }

    public static boolean isTerminalBlock(String scanStatus, boolean allowed) {
        if (isTerminalAllow(scanStatus, allowed)) {
            return false;
        }
        return BLOCKED.equals(scanStatus)
                || REJECTED.equals(scanStatus)
                || EXPIRED.equals(scanStatus)
                || (SCAN_ERROR.equals(scanStatus) && !allowed);
    }
}
