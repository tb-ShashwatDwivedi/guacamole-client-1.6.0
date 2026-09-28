package com.local.guac.sftp.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Runnable self-check for scan-hold lifecycle. No JUnit required.
 * Run: java -cp ... com.local.guac.sftp.scan.SftpScanHoldSelfCheck
 */
public final class SftpScanHoldSelfCheck {

    private static int passed;
    private static int failed;

    private SftpScanHoldSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path holdRoot = Files.createTempDirectory("scan-hold-selfcheck-");
        System.setProperty("tbpam-scan-hold-dir", holdRoot.toString());
        System.setProperty("tbpam-backend-url", "https://127.0.0.1:1");

        assertNoCapeReferences();
        assertPollUsesPamStatusOnly();
        assertHoldIsNotDeny();
        assertCancelOutcomeContract();

        runScenario("immediate allow leaves no hold file", () -> {
            SftpScanHoldStore store = new SftpScanHoldStore();
            assertEmpty(store, holdRoot);
        });

        runScenario("held file written and deleted on discard", () -> {
            String sessionId = uuid();
            String eventId = "42";
            SftpScanHoldStore store = new SftpScanHoldStore();
            SftpScanHoldMetadata meta = sampleMeta(sessionId, eventId);
            store.writeHold(sessionId, eventId, "payload".getBytes(), meta);
            assertTrue(store.holdExists(sessionId, eventId));
            store.deleteHold(sessionId, eventId);
            assertFalse(store.holdExists(sessionId, eventId));
            assertEmptySession(store, holdRoot, sessionId);
        });

        runScenario("sandbox allow path uses held file on disk", () -> {
            String sessionId = uuid();
            String eventId = "100";
            byte[] bytes = "approved-content".getBytes();
            SftpScanRequest request = sampleRequest(sessionId, bytes);
            SftpScanResult held = SftpScanResult.held(eventId, SftpScanStatus.SANDBOX_QUEUED,
                    5, "Sandbox analysis in progress…");
            SftpScanHoldEntry entry = SftpScanHoldService.createHold(request, 1, sessionId, held, bytes);
            SftpScanPoller.cancelPoll(eventId);
            Path path = SftpScanHoldService.heldFilePath(sessionId, eventId);
            assertTrue(Files.isRegularFile(path));
            entry.setPhase(SftpScanHoldEntry.Phase.READY);
            SftpScanHoldService.markCompleted(sessionId, eventId);
            assertFalse(Files.exists(path));
            assertNull(SftpScanHoldRegistry.get(eventId));
        });

        runScenario("sandbox block deletes held bytes", () -> {
            String sessionId = uuid();
            String eventId = "101";
            byte[] bytes = "blocked".getBytes();
            SftpScanRequest request = sampleRequest(sessionId, bytes);
            SftpScanResult held = SftpScanResult.held(eventId, SftpScanStatus.PENDING_REVIEW,
                    5, "Waiting for administrator approval");
            SftpScanHoldService.createHold(request, 2, sessionId, held, bytes);
            SftpScanPoller.cancelPoll(eventId);
            SftpScanHoldEntry entry = SftpScanHoldRegistry.get(eventId);
            entry.setPhase(SftpScanHoldEntry.Phase.REJECTED);
            SftpScanHoldService.deleteHeldBytes(sessionId, eventId);
            assertFalse(SftpScanHoldService.holdFileExists(sessionId, eventId));
            SftpScanHoldRegistry.remove(sessionId, eventId);
        });

        runScenario("session detach keeps disk until TTL sweeper", () -> {
            String sessionId = uuid();
            String eventId = "102";
            byte[] bytes = "queued".getBytes();
            SftpScanHoldService.createHold(sampleRequest(sessionId, bytes), 3, sessionId,
                    SftpScanResult.held(eventId, SftpScanStatus.SANDBOX_RUNNING, 5,
                            "Sandbox analysis in progress…"),
                    bytes);
            SftpScanPoller.cancelPoll(eventId);
            SftpScanHoldService.detachSession(sessionId);
            assertTrue(SftpScanHoldService.holdFileExists(sessionId, eventId));
            assertTrue(SftpScanHoldRegistry.listSession(sessionId).isEmpty());
        });

        runScenario("startup purge removes leftover bytes", () -> {
            String sessionId = uuid();
            String eventId = "103";
            SftpScanHoldStore store = new SftpScanHoldStore();
            store.writeHold(sessionId, eventId, "stale".getBytes(), sampleMeta(sessionId, eventId));
            assertTrue(store.holdExists(sessionId, eventId));
            store.purgeAll();
            assertFalse(store.holdExists(sessionId, eventId));
        });

        runScenario("TTL sweeper discards expired holds", () -> {
            String sessionId = uuid();
            String eventId = "104";
            SftpScanHoldStore store = new SftpScanHoldStore();
            SftpScanHoldMetadata meta = sampleMeta(sessionId, eventId);
            meta.setCreatedAtMs(System.currentTimeMillis() - SftpScanConfig.getHoldTtlMs() - 1000L);
            store.writeHold(sessionId, eventId, "old".getBytes(), meta);
            List<SftpScanHoldStore.ExpiredHold> expired =
                    store.findExpiredHolds(SftpScanConfig.getHoldTtlMs());
            assertTrue(expired.stream().anyMatch(h -> eventId.equals(h.getEventId())));
            for (SftpScanHoldStore.ExpiredHold hold : expired) {
                SftpScanHoldService.discardHold(hold.getSessionId(), hold.getEventId(), "expired");
            }
            assertFalse(store.holdExists(sessionId, eventId));
        });

        assertEmpty(new SftpScanHoldStore(), holdRoot);
        deleteRecursive(holdRoot);

        System.out.println("SftpScanHoldSelfCheck: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void assertNoCapeReferences() throws IOException {
        Path src = Path.of("sftp-scan-gate/src/main/java");
        if (!Files.isDirectory(src)) {
            src = Path.of(System.getProperty("user.dir"), "sftp-scan-gate/src/main/java");
        }
        try (Stream<Path> files = Files.walk(src)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("SftpScanHoldSelfCheck.java"))
                    .forEach(p -> {
                        try {
                            String text = Files.readString(p);
                            assertFalse(text.toLowerCase().contains("cape"));
                        }
                        catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        pass("core never references CAPE");
    }

    private static void assertCancelOutcomeContract() {
        String msg = SftpScanConfig.CANCELLED_BY_USER_MESSAGE;
        assertTrue(msg != null && msg.toLowerCase().contains("cancelled by user"));
        assertTrue(SftpScanConfig.OUTCOME_PATCH_MAX_ATTEMPTS >= 2);
        pass("user cancel outcome uses Cancelled by user + retry");
    }

    private static void assertPollUsesPamStatusOnly() {
        String url = SftpScanConfig.getStatusUrl("99");
        assertTrue(url != null && url.endsWith("/v1/tbpam/file-scan-events/99/status"));
        assertFalse(url.contains("tasks/view"));
        assertFalse(url.contains("get/report"));
        pass("poll uses only PAM file-scan-events status endpoint");
    }

    private static void assertHoldIsNotDeny() throws IOException {
        SftpScanResult review = SftpScanClient.parseBody(
                "{\"data\":{\"eventId\":123,\"terminal\":false,\"allowed\":false,"
                        + "\"scanStatus\":\"pending_review\",\"pollAfterSeconds\":5,"
                        + "\"userMessage\":\"Malware detected. Waiting for administrator approval before transfer.\"}}",
                "fallback");
        assertTrue(review.isHeld());
        assertFalse(review.isTerminal());
        assertFalse(review.isAllowed());
        assertTrue(SftpScanStatus.PENDING_REVIEW.equals(review.getScanStatus()));
        pass("pending_review + allowed false is a hold, not a block");

        SftpScanResult sandbox = SftpScanClient.parseBody(
                "{\"data\":{\"eventId\":456,\"terminal\":false,\"allowed\":false,"
                        + "\"scanStatus\":\"sandbox_queued\",\"pollAfterSeconds\":5,"
                        + "\"userMessage\":\"File held while sandbox analysis is in progress.\"}}",
                "fallback");
        assertTrue(sandbox.isHeld());
        assertTrue(SftpScanStatus.SANDBOX_QUEUED.equals(sandbox.getScanStatus()));
        pass("sandbox_queued + allowed false is a hold, not a block");

        SftpScanResult allowNow = SftpScanClient.parseBody(
                "{\"data\":{\"eventId\":1,\"terminal\":true,\"allowed\":true,"
                        + "\"scanStatus\":\"allowed\"}}",
                "fallback");
        assertTrue(allowNow.isAllowed());
        assertFalse(allowNow.isHeld());
        pass("terminal allowed transfers immediately");

        SftpScanResult blockNow = SftpScanClient.parseBody(
                "{\"data\":{\"eventId\":2,\"terminal\":true,\"allowed\":false,"
                        + "\"scanStatus\":\"blocked\",\"userMessage\":\"blocked\"}}",
                "fallback");
        assertFalse(blockNow.isAllowed());
        assertFalse(blockNow.isHeld());
        assertTrue(blockNow.isTerminal());
        pass("terminal blocked is a deny");

        SftpScanResult allowMalware = SftpScanClient.parseBody(
                "{\"data\":{\"eventId\":10,\"terminal\":true,\"allowed\":true,"
                        + "\"scanStatus\":\"allowed\",\"malwareAction\":\"allow\","
                        + "\"infected\":true,\"scanResult\":\"malicious\","
                        + "\"actionTaken\":\"allowed\",\"failureAction\":\"block\","
                        + "\"reason\":\"malicious\","
                        + "\"userMessage\":\"Malware detected: EICAR. Transfer allowed by administrator policy.\"}}",
                "fallback");
        assertTrue(allowMalware.isAllowed());
        assertFalse(allowMalware.isHeld());
        assertTrue(allowMalware.isTerminal());
        pass("allow-malware: infected true + allowed true → transfer (ignore infected)");

        SftpScanResult blockMalware = SftpScanClient.parseBody(
                "{\"data\":{\"eventId\":11,\"terminal\":true,\"allowed\":false,"
                        + "\"scanStatus\":\"blocked\",\"infected\":true,"
                        + "\"scanResult\":\"malicious\",\"malwareAction\":\"block\","
                        + "\"userMessage\":\"Malware detected: EICAR. Transfer blocked.\"}}",
                "fallback");
        assertFalse(blockMalware.isAllowed());
        assertFalse(blockMalware.isHeld());
        pass("block-malware: allowed false → deny");

        SftpScanResult reviewMalware = SftpScanClient.parseBody(
                "{\"status\":202,\"data\":{\"eventId\":12,\"terminal\":false,\"allowed\":false,"
                        + "\"scanStatus\":\"pending_review\",\"pollAfterSeconds\":5,"
                        + "\"malwareAction\":\"review\",\"infected\":true,"
                        + "\"userMessage\":\"Waiting for administrator approval\"}}",
                "fallback");
        assertTrue(reviewMalware.isHeld());
        assertFalse(reviewMalware.isTerminal());
        pass("review-malware: terminal false → hold, not block");
    }

    private static SftpScanRequest sampleRequest(String sessionId, byte[] bytes) {
        return new SftpScanRequest(bytes, "sample.bin", "application/octet-stream", "tester",
                sessionId, UUID.randomUUID().toString(), SftpScanRequest.DIRECTION_UPLOAD,
                "/local/sample.bin", "/remote/sample.bin", "conn-1", "Test Connection",
                "host.example", "10.0.0.1");
    }

    private static SftpScanHoldMetadata sampleMeta(String sessionId, String eventId) {
        SftpScanHoldMetadata meta = new SftpScanHoldMetadata();
        meta.setSessionId(sessionId);
        meta.setEventId(eventId);
        meta.setCreatedAtMs(System.currentTimeMillis());
        meta.setFilename("sample.bin");
        meta.setDirection(SftpScanRequest.DIRECTION_UPLOAD);
        return meta;
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    @FunctionalInterface
    private interface Scenario {
        void run() throws Exception;
    }

    private static void runScenario(String name, Scenario scenario) {
        try {
            scenario.run();
            pass(name);
        }
        catch (Exception | AssertionError e) {
            failed++;
            System.err.println("FAIL: " + name + " -> " + e.getMessage());
        }
    }

    private static void assertEmpty(SftpScanHoldStore store, Path holdRoot) throws IOException {
        if (!Files.isDirectory(holdRoot)) {
            return;
        }
        try (Stream<Path> entries = Files.list(holdRoot)) {
            assertFalse(entries.findAny().isPresent());
        }
    }

    private static void assertEmptySession(SftpScanHoldStore store, Path holdRoot,
            String sessionId) throws IOException {
        Path sessionDir = holdRoot.resolve(sessionId);
        assertFalse(Files.exists(sessionDir));
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                }
                catch (IOException ignored) {
                }
            });
        }
    }

    private static void pass(String name) {
        passed++;
        System.out.println("PASS: " + name);
    }

    private static void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("expected true");
        }
    }

    private static void assertFalse(boolean condition) {
        if (condition) {
            throw new AssertionError("expected false");
        }
    }

    private static void assertNull(Object value) {
        if (value != null) {
            throw new AssertionError("expected null");
        }
    }
}
