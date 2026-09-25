package com.local.guac.sftp.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public final class SftpScanHoldStore {

    private static final Logger logger = LoggerFactory.getLogger(SftpScanHoldStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<PosixFilePermission> DIR_PERMS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMS =
            PosixFilePermissions.fromString("rw-------");

    private final Path root;

    public SftpScanHoldStore() {
        root = Paths.get(SftpScanConfig.getHoldDir());
    }

    public void writeHold(String sessionId, String eventId, byte[] bytes,
            SftpScanHoldMetadata metadata) throws IOException {
        validateId(sessionId, "sessionId");
        validateId(eventId, "eventId");

        Path sessionDir = root.resolve(sessionId);
        Files.createDirectories(sessionDir);
        setPosixDir(sessionDir);

        Path part = sessionDir.resolve(eventId + ".part");
        Path finalPath = sessionDir.resolve(eventId);
        Path metaPath = sessionDir.resolve(eventId + ".meta");

        metadata.setEventId(eventId);
        metadata.setSessionId(sessionId);

        try (FileChannel channel = FileChannel.open(part, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        setPosixFile(part);
        Files.move(part, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        setPosixFile(finalPath);

        MAPPER.writeValue(metaPath.toFile(), metadata);
        setPosixFile(metaPath);
    }

    public byte[] readHoldBytes(String sessionId, String eventId) throws IOException {
        return Files.readAllBytes(holdFilePath(sessionId, eventId));
    }

    public Path holdFilePath(String sessionId, String eventId) {
        validateId(sessionId, "sessionId");
        validateId(eventId, "eventId");
        return root.resolve(sessionId).resolve(eventId);
    }

    public SftpScanHoldMetadata readMetadata(String sessionId, String eventId) throws IOException {
        Path metaPath = root.resolve(sessionId).resolve(eventId + ".meta");
        if (!Files.exists(metaPath)) {
            return null;
        }
        return MAPPER.readValue(metaPath.toFile(), SftpScanHoldMetadata.class);
    }

    public boolean holdExists(String sessionId, String eventId) {
        return Files.isRegularFile(holdFilePath(sessionId, eventId));
    }

    public void deleteHold(String sessionId, String eventId) {
        if (sessionId == null || eventId == null) {
            return;
        }
        try {
            Path sessionDir = root.resolve(sessionId);
            Files.deleteIfExists(sessionDir.resolve(eventId));
            Files.deleteIfExists(sessionDir.resolve(eventId + ".meta"));
            Files.deleteIfExists(sessionDir.resolve(eventId + ".part"));
            if (Files.isDirectory(sessionDir) && isEmptyDir(sessionDir)) {
                Files.deleteIfExists(sessionDir);
            }
        }
        catch (IOException e) {
            logger.warn("Failed to delete hold sessionId={} eventId={}: {}",
                    sessionId, eventId, e.getMessage());
        }
    }

    public List<String> listEventIds(String sessionId) throws IOException {
        Path sessionDir = root.resolve(sessionId);
        if (!Files.isDirectory(sessionDir)) {
            return List.of();
        }
        List<String> eventIds = new ArrayList<>();
        try (Stream<Path> stream = Files.list(sessionDir)) {
            stream.filter(p -> !p.getFileName().toString().endsWith(".meta")
                    && !p.getFileName().toString().endsWith(".part"))
                    .filter(Files::isRegularFile)
                    .forEach(p -> eventIds.add(p.getFileName().toString()));
        }
        return eventIds;
    }

    public void deleteSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            Path sessionDir = root.resolve(sessionId);
            if (Files.exists(sessionDir)) {
                deleteRecursive(sessionDir);
            }
        }
        catch (IOException e) {
            logger.warn("Failed to delete session hold dir {}: {}", sessionId, e.getMessage());
        }
    }

    public void purgeAll() {
        try {
            if (!Files.exists(root)) {
                Files.createDirectories(root);
                setPosixDir(root);
                return;
            }
            try (Stream<Path> stream = Files.list(root)) {
                stream.filter(Files::isDirectory).forEach(dir -> {
                    try {
                        deleteRecursive(dir);
                    }
                    catch (IOException e) {
                        logger.warn("Startup purge failed for {}: {}", dir, e.getMessage());
                    }
                });
            }
            try (Stream<Path> stream = Files.list(root)) {
                stream.filter(Files::isRegularFile).forEach(file -> {
                    try {
                        Files.deleteIfExists(file);
                    }
                    catch (IOException e) {
                        logger.warn("Startup purge failed for {}: {}", file, e.getMessage());
                    }
                });
            }
            logger.info("Scan hold startup purge completed for {}", root);
        }
        catch (IOException e) {
            logger.warn("Scan hold startup purge failed: {}", e.getMessage());
        }
    }

    public List<ExpiredHold> findExpiredHolds(long ttlMs) throws IOException {
        List<ExpiredHold> expired = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return expired;
        }
        long cutoff = System.currentTimeMillis() - ttlMs;
        try (Stream<Path> sessions = Files.list(root)) {
            sessions.filter(Files::isDirectory).forEach(sessionDir -> {
                String sessionId = sessionDir.getFileName().toString();
                try (Stream<Path> files = Files.list(sessionDir)) {
                    files.filter(p -> p.getFileName().toString().endsWith(".meta"))
                            .forEach(metaPath -> {
                                try {
                                    SftpScanHoldMetadata meta = MAPPER.readValue(metaPath.toFile(),
                                            SftpScanHoldMetadata.class);
                                    if (meta.getCreatedAtMs() > 0 && meta.getCreatedAtMs() < cutoff) {
                                        expired.add(new ExpiredHold(sessionId, meta.getEventId()));
                                    }
                                }
                                catch (IOException e) {
                                    logger.warn("Unable to read hold metadata {}: {}",
                                            metaPath, e.getMessage());
                                }
                            });
                }
                catch (IOException e) {
                    logger.warn("Unable to sweep session dir {}: {}", sessionDir, e.getMessage());
                }
            });
        }
        return expired;
    }

    public List<ExpiredHold> collectMetaEventIdsForPurge() throws IOException {
        List<ExpiredHold> all = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return all;
        }
        try (Stream<Path> sessions = Files.list(root)) {
            sessions.filter(Files::isDirectory).forEach(sessionDir -> {
                String sessionId = sessionDir.getFileName().toString();
                try (Stream<Path> files = Files.list(sessionDir)) {
                    files.filter(p -> p.getFileName().toString().endsWith(".meta"))
                            .forEach(metaPath -> {
                                try {
                                    SftpScanHoldMetadata meta = MAPPER.readValue(metaPath.toFile(),
                                            SftpScanHoldMetadata.class);
                                    if (meta.getEventId() != null) {
                                        all.add(new ExpiredHold(sessionId, meta.getEventId()));
                                    }
                                }
                                catch (IOException ignored) {
                                    // meta unreadable; bytes still deleted by purgeAll
                                }
                            });
                }
                catch (IOException ignored) {
                }
            });
        }
        return all;
    }

    private static void validateId(String value, String label) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9\\-]+")) {
            throw new IllegalArgumentException("Invalid " + label + " for hold path");
        }
    }

    private static void setPosixDir(Path path) {
        try {
            Files.setPosixFilePermissions(path, DIR_PERMS);
        }
        catch (Exception ignored) {
            // ponytail: non-POSIX FS may ignore mode bits
        }
    }

    private static void setPosixFile(Path path) {
        try {
            Files.setPosixFilePermissions(path, FILE_PERMS);
        }
        catch (Exception ignored) {
        }
    }

    private static boolean isEmptyDir(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.findAny().isEmpty();
        }
    }

    private static void deleteRecursive(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exc) throws IOException {
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static final class ExpiredHold {
        private final String sessionId;
        private final String eventId;

        public ExpiredHold(String sessionId, String eventId) {
            this.sessionId = sessionId;
            this.eventId = eventId;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getEventId() {
            return eventId;
        }
    }
}
