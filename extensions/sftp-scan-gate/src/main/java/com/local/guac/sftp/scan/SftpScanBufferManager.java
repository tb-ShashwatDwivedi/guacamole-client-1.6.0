package com.local.guac.sftp.scan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SftpScanBufferManager {

    private static final Map<String, ByteArrayOutputStream> BUFFERS = new ConcurrentHashMap<>();
    private static final Map<String, Long> EXPECTED_SIZES = new ConcurrentHashMap<>();

    private SftpScanBufferManager() {
    }

    private static String key(String sessionId, int streamIndex) {
        return sessionId + ":" + streamIndex;
    }

    public static void beginUpload(String sessionId, int streamIndex, long fileSize) {
        String bufferKey = key(sessionId, streamIndex);
        BUFFERS.put(bufferKey, new ByteArrayOutputStream(Math.toIntExact(Math.min(fileSize, Integer.MAX_VALUE))));
        EXPECTED_SIZES.put(bufferKey, fileSize);
    }

    public static void appendChunk(String sessionId, int streamIndex, byte[] chunk,
            long offset) throws IOException {
        String bufferKey = key(sessionId, streamIndex);
        ByteArrayOutputStream buffer = BUFFERS.get(bufferKey);
        if (buffer == null) {
            buffer = new ByteArrayOutputStream();
            BUFFERS.put(bufferKey, buffer);
        }

        int currentSize = buffer.size();
        if (offset > currentSize) {
            buffer.write(new byte[(int) (offset - currentSize)]);
        }
        else if (offset < currentSize) {
            byte[] existing = buffer.toByteArray();
            buffer.reset();
            buffer.write(Arrays.copyOf(existing, (int) offset));
        }
        buffer.write(chunk);
    }

    public static boolean isUploadComplete(String sessionId, int streamIndex) {
        String bufferKey = key(sessionId, streamIndex);
        Long expected = EXPECTED_SIZES.get(bufferKey);
        ByteArrayOutputStream buffer = BUFFERS.get(bufferKey);
        if (buffer == null || expected == null) {
            return false;
        }
        return buffer.size() >= expected;
    }

    public static byte[] getAndClear(String sessionId, int streamIndex) {
        String bufferKey = key(sessionId, streamIndex);
        ByteArrayOutputStream buffer = BUFFERS.remove(bufferKey);
        EXPECTED_SIZES.remove(bufferKey);
        if (buffer == null) {
            return new byte[0];
        }
        return buffer.toByteArray();
    }

    public static void clear(String sessionId, int streamIndex) {
        String bufferKey = key(sessionId, streamIndex);
        BUFFERS.remove(bufferKey);
        EXPECTED_SIZES.remove(bufferKey);
    }
}
