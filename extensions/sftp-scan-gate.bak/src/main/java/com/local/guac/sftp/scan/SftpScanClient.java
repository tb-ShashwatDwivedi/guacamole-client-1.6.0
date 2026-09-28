package com.local.guac.sftp.scan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.glassfish.jersey.client.HttpUrlConnectorProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;

public class SftpScanClient {

    private static final Logger logger = LoggerFactory.getLogger(SftpScanClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile Client jerseyOutcomeClient;

    public SftpScanResult scan(SftpScanRequest request) {
        String scanUrl = SftpScanConfig.getScanUrl();
        if (scanUrl == null) {
            logger.warn("TBPAM_BACKEND_BASE_URL is not set; proceeding without scan");
            return SftpScanResult.noScannerProceed();
        }

        HttpURLConnection connection = null;
        try {
            connection = openConnection(scanUrl);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(SftpScanConfig.SCAN_TIMEOUT_SEC * 1000);
            connection.setReadTimeout(SftpScanConfig.SCAN_TIMEOUT_SEC * 1000);
            applyScanHeaders(connection, request);
            logger.info("SFTP scan request: url={}, direction={}, transferId={}, sourcePath={}, "
                    + "destinationPath={}, connectionId={}, connectionName={}, assetHostname={}, assetIp={}",
                    scanUrl, request.getDirection(), request.getTransferId(),
                    request.getSourcePath(), request.getDestinationPath(),
                    request.getConnectionId(), request.getConnectionName(),
                    request.getAssetHostname(), request.getAssetIp());

            try (OutputStream output = connection.getOutputStream()) {
                output.write(request.getFileBytes());
            }

            int status = connection.getResponseCode();
            String body = readBody(connection, status);

            if (status == 404) {
                logger.info("No antimalware scanner configured; proceeding without scan");
                return SftpScanResult.noScannerProceed();
            }

            SftpScanResult result;
            if (status == 413) {
                result = SftpScanResult.blocked(extractMessage(body), SftpScanStatus.SCAN_ERROR,
                        null, null, SftpScanStatus.BLOCKED);
            }
            else if (status == 202 || (status >= 200 && status < 300)) {
                result = parseBody(body, "Malware scan failed");
            }
            else {
                result = parseBody(body, extractMessage(body));
            }
            logScanResponse(result);
            return result;
        }
        catch (IOException e) {
            logger.warn("Malware scan request failed: {}", e.getMessage());
            return SftpScanResult.blocked(
                    "Malware scanner is unavailable. Try again later.",
                    SftpScanResult.REASON_SCAN_ERROR, null, null, SftpScanStatus.SCAN_ERROR);
        }
        finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public SftpScanResult pollStatus(String eventId) throws IOException {
        String statusUrl = SftpScanConfig.getStatusUrl(eventId);
        if (statusUrl == null) {
            throw new IOException("TBPAM_BACKEND_BASE_URL is not configured");
        }

        HttpURLConnection connection = null;
        try {
            connection = openConnection(statusUrl);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(SftpScanConfig.SCAN_TIMEOUT_SEC * 1000);
            connection.setReadTimeout(SftpScanConfig.SCAN_TIMEOUT_SEC * 1000);
            connection.setRequestProperty("X-API-Key", SftpScanConfig.getApiKey());

            int status = connection.getResponseCode();
            String body = readBody(connection, status);
            if (status < 200 || status >= 300) {
                throw new IOException("Status poll HTTP " + status + ": " + body);
            }
            SftpScanResult result = parseBody(body, "Scan status poll failed");
            logScanResponse(result);
            return result;
        }
        finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Log an outbound (download) transfer without scanning file bytes.
     * Best-effort: failures are logged and never block the download.
     * Uses HttpURLConnection (same as scan) — Jersey HTTPS breaks under Java modules.
     */
    public void auditOutbound(SftpScanRequest request, long fileSizeBytes) {
        String auditUrl = SftpScanConfig.getAuditUrl();
        if (auditUrl == null) {
            logger.warn("Cannot audit outbound transfer: backend URL not configured");
            return;
        }

        HttpURLConnection connection = null;
        try {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("fileName", nullToEmpty(request.getFilename()));
            payload.put("username", nullToEmpty(request.getUsername()));
            payload.put("sessionId", nullToEmpty(request.getSessionId()));
            payload.put("transferId", nullToEmpty(request.getTransferId()));
            payload.put("direction", "download");
            payload.put("fileSizeBytes", fileSizeBytes);
            putIfPresent(payload, "contentType", request.getContentType());
            putIfPresent(payload, "sourcePath", request.getSourcePath());
            putIfPresent(payload, "destinationPath", request.getDestinationPath());
            putIfPresent(payload, "connectionId", request.getConnectionId());
            putIfPresent(payload, "connectionName", request.getConnectionName());
            putIfPresent(payload, "assetHostname", request.getAssetHostname());
            putIfPresent(payload, "assetIp", request.getAssetIp());

            byte[] bodyBytes = MAPPER.writeValueAsBytes(payload);
            connection = openConnection(auditUrl);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("X-API-Key", SftpScanConfig.getApiKey());
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));

            try (OutputStream output = connection.getOutputStream()) {
                output.write(bodyBytes);
            }

            int status = connection.getResponseCode();
            String body = readBody(connection, status);
            if (status < 200 || status >= 300) {
                logger.warn("Outbound audit failed httpStatus={} body={}", status, body);
            }
            else {
                logger.info("Outbound audit OK: file={}, bytes={}, body={}",
                        request.getFilename(), fileSizeBytes, body);
            }
        }
        catch (Exception e) {
            logger.warn("Outbound audit failed for file={}: {}",
                    request.getFilename(), e.getMessage());
        }
        finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void putIfPresent(ObjectNode payload, String field, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(field, value);
        }
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    public void reportOutcome(String eventId, String status, String errorMessage) {
        reportOutcome(eventId, status, errorMessage, 1);
    }

    /**
     * @return true if PAM returned HTTP 200
     */
    public boolean reportOutcome(String eventId, String status, String errorMessage,
            int maxAttempts) {
        if (eventId == null || eventId.isBlank()) {
            return false;
        }

        String outcomeUrl = SftpScanConfig.getOutcomeUrl(eventId);
        if (outcomeUrl == null) {
            logger.warn("Cannot report scan outcome: backend URL not configured");
            return false;
        }

        int attempts = Math.max(1, maxAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                ObjectNode payload = MAPPER.createObjectNode();
                payload.put("status", status);
                if (errorMessage != null && !errorMessage.isBlank()) {
                    payload.put("errorMessage", errorMessage);
                }
                String jsonBody = MAPPER.writeValueAsString(payload);

                // HttpURLConnection PATCH reflection still sends POST on the wire (PAM returns
                // 403 TOKEN_REQUIRED). Jersey + SET_METHOD_WORKAROUND sends real PATCH; requires
                // Tomcat --add-opens for sun.net.www.protocol.https (docker-entrypoint.sh).
                Client client = jerseyOutcomeClient();
                Response response = client.target(outcomeUrl)
                        .request()
                        .header("X-API-Key", SftpScanConfig.getApiKey())
                        .method("PATCH", Entity.json(jsonBody));

                int responseStatus = response.getStatus();
                String responseBody = response.readEntity(String.class);
                if (responseStatus == 200) {
                    logger.info(
                            "Scan outcome PATCH OK: eventId={}, status={}, attempt={}, body={}",
                            eventId, status, attempt, responseBody);
                    return true;
                }
                logger.warn(
                        "Scan outcome PATCH failed for eventId={} attempt={}/{} httpStatus={} body={}",
                        eventId, attempt, attempts, responseStatus, responseBody);
            }
            catch (Exception e) {
                logger.warn("Scan outcome PATCH failed for eventId={} attempt={}/{}: {}",
                        eventId, attempt, attempts, e.toString());
            }
            if (attempt < attempts) {
                try {
                    Thread.sleep(500L * attempt);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private static void logScanResponse(SftpScanResult result) {
        logger.info("SFTP scan response: eventId={}, terminal={}, held={}, allowed={}, "
                + "scanStatus={}, pollAfterSeconds={}",
                result.getEventId(), result.isTerminal(), result.isHeld(), result.isAllowed(),
                result.getScanStatus(), result.getPollAfterSeconds());
    }

    private static void applyScanHeaders(HttpURLConnection connection, SftpScanRequest request) {
        connection.setRequestProperty("X-API-Key", SftpScanConfig.getApiKey());
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        setHeader(connection, "X-File-Content-Type", request.getContentType());
        setHeader(connection, "X-File-Name", request.getFilename());
        setHeader(connection, "X-Username", request.getUsername());
        setHeader(connection, "X-Session-Id", request.getSessionId());
        setHeader(connection, "X-Transfer-Id", request.getTransferId());
        setHeader(connection, "X-Transfer-Direction", request.getDirection());
        setHeader(connection, "X-Source-Path", request.getSourcePath());
        setHeader(connection, "X-Destination-Path", request.getDestinationPath());
        setHeader(connection, "X-Connection-Id", request.getConnectionId());
        setHeader(connection, "X-Connection-Name", request.getConnectionName());
        setHeader(connection, "X-Asset-Hostname", request.getAssetHostname());
        setHeader(connection, "X-Asset-Ip", request.getAssetIp());
    }

    private static void setHeader(HttpURLConnection connection, String name, String value) {
        if (value != null && !value.isBlank()) {
            connection.setRequestProperty(name, value);
        }
    }

    /**
     * Decision uses only terminal + allowed + scanStatus.
     * infected / scanResult / malwareAction / failureAction / actionTaken / reason / threat
     * are audit fields and must not block an allowed transfer.
     */
    static SftpScanResult parseBody(String body, String fallbackMessage) throws IOException {
        JsonNode root = MAPPER.readTree(body);
        JsonNode data = root.path("data");

        if (data.isMissingNode() || data.isNull()) {
            if (!root.path("success").asBoolean(false)) {
                return SftpScanResult.blocked(extractMessage(body),
                        SftpScanResult.REASON_SCAN_ERROR, null, null, SftpScanStatus.BLOCKED);
            }
            return SftpScanResult.proceedSilently(null, SftpScanResult.REASON_CLEAN,
                    SftpScanResult.ACTION_ALLOWED, SftpScanStatus.ALLOWED);
        }

        Boolean terminal = readOptionalBoolean(data, "terminal");
        boolean allowed = data.path("allowed").asBoolean(false);
        String userMessage = textOrNull(data, "userMessage");
        String eventId = textOrNull(data, "eventId");
        String scanStatus = textOrNull(data, "scanStatus");
        // audit only — never used for go/no-go:
        // infected, scanResult, malwareAction, failureAction, actionTaken, reason, threat
        String reason = data.path("reason").asText("");
        String actionTaken = data.path("actionTaken").asText("");
        String threat = textOrNull(data, "threat");

        int pollAfterSeconds = data.path("pollAfterSeconds").asInt(
                SftpScanConfig.DEFAULT_POLL_AFTER_SEC);
        if (pollAfterSeconds <= 0) {
            pollAfterSeconds = SftpScanConfig.DEFAULT_POLL_AFTER_SEC;
        }

        // Hold: explicit terminal:false OR known non-terminal scanStatus
        boolean hold = Boolean.FALSE.equals(terminal)
                || SftpScanStatus.isNonTerminal(scanStatus);
        if (hold) {
            if (scanStatus == null || scanStatus.isBlank()) {
                scanStatus = SftpScanStatus.PENDING_REVIEW;
            }
            if (userMessage == null || userMessage.isBlank()) {
                userMessage = "File is being analyzed before transfer.";
            }
            return SftpScanResult.held(eventId, scanStatus, pollAfterSeconds, userMessage);
        }

        // Terminal allow: allowed:true wins even when infected/malicious audit fields are set
        if (allowed || SftpScanStatus.isTerminalAllow(scanStatus, allowed)) {
            if (userMessage != null && !userMessage.isBlank()
                    && (SftpScanResult.ACTION_SKIPPED.equals(actionTaken)
                    || SftpScanStatus.SCAN_ERROR.equals(scanStatus))) {
                return SftpScanResult.proceedWithWarning(userMessage, eventId,
                        reason.isBlank() ? SftpScanResult.REASON_SCAN_ERROR : reason,
                        actionTaken.isBlank() ? SftpScanResult.ACTION_SKIPPED : actionTaken,
                        scanStatus != null ? scanStatus : SftpScanStatus.ALLOWED);
            }
            return SftpScanResult.proceedSilently(eventId,
                    reason.isBlank() ? SftpScanResult.REASON_CLEAN : reason,
                    actionTaken.isBlank() ? SftpScanResult.ACTION_ALLOWED : actionTaken,
                    scanStatus != null ? scanStatus : SftpScanStatus.ALLOWED);
        }

        // Terminal deny
        if (userMessage == null || userMessage.isBlank()) {
            userMessage = fallbackMessage != null ? fallbackMessage : extractMessage(body);
        }
        return SftpScanResult.blocked(userMessage,
                reason.isBlank() ? SftpScanResult.REASON_MALICIOUS : reason,
                threat, eventId,
                scanStatus != null ? scanStatus : SftpScanStatus.BLOCKED);
    }

    private static Boolean readOptionalBoolean(JsonNode data, String field) {
        if (!data.has(field) || data.get(field).isNull()) {
            return null;
        }
        JsonNode node = data.get(field);
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.intValue() != 0;
        }
        String text = node.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static Client jerseyOutcomeClient() throws Exception {
        Client existing = jerseyOutcomeClient;
        if (existing != null) {
            return existing;
        }
        synchronized (SftpScanClient.class) {
            if (jerseyOutcomeClient == null) {
                jerseyOutcomeClient = createJerseyClient();
            }
            return jerseyOutcomeClient;
        }
    }

    private static Client createJerseyClient() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[] {
            new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }
        }, null);
        return ClientBuilder.newBuilder()
                .sslContext(sslContext)
                .hostnameVerifier((hostname, session) -> true)
                .property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true)
                .build();
    }

    private static SSLContext trustAllSslContext() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[] {
            new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }
        }, null);
        return sslContext;
    }

    private static HttpURLConnection openConnection(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (connection instanceof HttpsURLConnection) {
            try {
                SSLContext sslContext = trustAllSslContext();
                ((HttpsURLConnection) connection).setSSLSocketFactory(sslContext.getSocketFactory());
                ((HttpsURLConnection) connection).setHostnameVerifier((hostname, session) -> true);
            }
            catch (Exception e) {
                throw new IOException("Unable to configure HTTPS for scan request", e);
            }
        }
        return connection;
    }

    private static String readBody(HttpURLConnection connection, int status) throws IOException {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    private static String extractMessage(String body) {
        if (body == null || body.isBlank()) {
            return "Malware scan failed";
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            String message = root.path("message").asText(null);
            if (message != null && !message.isBlank()) {
                return message;
            }
            String userMessage = root.path("data").path("userMessage").asText(null);
            if (userMessage != null && !userMessage.isBlank()) {
                return userMessage;
            }
        }
        catch (IOException ignored) {
            // fall through
        }
        return body;
    }
}
