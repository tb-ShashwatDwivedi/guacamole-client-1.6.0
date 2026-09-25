package com.local.guac.sftp.scan;

public final class SftpScanConfig {

    public static final String SCAN_API_PATH = "/v1/tbpam/scanners/scan";
    public static final String OUTCOME_API_PATH = "/v1/tbpam/file-scan-events/";
    public static final String AUDIT_API_PATH = "/v1/tbpam/file-scan-events/audit";
    public static final String STATUS_API_SUFFIX = "/status";
    public static final String DEFAULT_API_KEY = "Jigar@926";
    /** Required phrase for PAM File Review (decision_reason cancelled_by_user). */
    public static final String CANCELLED_BY_USER_MESSAGE = "Cancelled by user";
    public static final int OUTCOME_PATCH_MAX_ATTEMPTS = 3;
    public static final String DEFAULT_HOLD_DIR = "/var/lib/guacamole/scan-hold";
    public static final boolean SCAN_ENABLED = true;
    public static final int SCAN_TIMEOUT_SEC = 300;
    public static final int DEFAULT_POLL_AFTER_SEC = 5;
    public static final int SWEEPER_INTERVAL_SEC = 60;
    /** Safety cap for orphaned holds on disk; session close / cancel still expire earlier. */
    public static final long DEFAULT_HOLD_TTL_MS = 24L * 60L * 60L * 1000L;
    public static final long DEFAULT_MAX_SCAN_BYTES = 1024L * 1024L * 1024L;

    private SftpScanConfig() {
    }

    public static String getBackendBaseUrl() {
        String url = System.getenv("TBPAM_BACKEND_BASE_URL");
        if (url == null || url.isBlank()) {
            url = System.getProperty("tbpam-backend-url");
        }
        if (url == null || url.isBlank()) {
            return null;
        }
        return url.replaceAll("/+$", "");
    }

    public static String getApiKey() {
        String key = System.getenv("TBPAM_BACKEND_API_KEY");
        if (key == null || key.isBlank()) {
            key = System.getProperty("tbpam-backend-api-key");
        }
        if (key == null || key.isBlank()) {
            key = DEFAULT_API_KEY;
        }
        return key;
    }

    public static String getHoldDir() {
        String dir = System.getenv("TBPAM_SCAN_HOLD_DIR");
        if (dir == null || dir.isBlank()) {
            dir = System.getProperty("tbpam-scan-hold-dir");
        }
        if (dir == null || dir.isBlank()) {
            dir = DEFAULT_HOLD_DIR;
        }
        return dir;
    }

    public static long getHoldTtlMs() {
        String hours = System.getenv("TBPAM_SCAN_HOLD_TTL_HOURS");
        if (hours == null || hours.isBlank()) {
            hours = System.getProperty("tbpam-scan-hold-ttl-hours");
        }
        if (hours != null && !hours.isBlank()) {
            try {
                long h = Long.parseLong(hours.trim());
                if (h > 0) {
                    return Math.min(h * 60L * 60L * 1000L, DEFAULT_HOLD_TTL_MS);
                }
            }
            catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return DEFAULT_HOLD_TTL_MS;
    }

    public static String getScanUrl() {
        String baseUrl = getBackendBaseUrl();
        if (baseUrl == null) {
            return null;
        }
        return baseUrl + SCAN_API_PATH;
    }

    public static String getAuditUrl() {
        String baseUrl = getBackendBaseUrl();
        if (baseUrl == null) {
            return null;
        }
        return baseUrl + AUDIT_API_PATH;
    }

    public static String getOutcomeUrl(String eventId) {
        String baseUrl = getBackendBaseUrl();
        if (baseUrl == null || eventId == null || eventId.isBlank()) {
            return null;
        }
        return baseUrl + OUTCOME_API_PATH + eventId + "/outcome";
    }

    public static String getStatusUrl(String eventId) {
        String baseUrl = getBackendBaseUrl();
        if (baseUrl == null || eventId == null || eventId.isBlank()) {
            return null;
        }
        return baseUrl + OUTCOME_API_PATH + eventId + STATUS_API_SUFFIX;
    }
}
