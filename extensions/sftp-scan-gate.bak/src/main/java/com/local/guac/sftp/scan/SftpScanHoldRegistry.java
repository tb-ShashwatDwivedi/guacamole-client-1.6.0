package com.local.guac.sftp.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class SftpScanHoldRegistry {

    private static final ConcurrentHashMap<String, SftpScanHoldEntry> BY_EVENT =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, SftpScanHoldEntry>> BY_SESSION =
            new ConcurrentHashMap<>();

    private SftpScanHoldRegistry() {
    }

    public static void register(SftpScanHoldEntry entry) {
        BY_EVENT.put(entry.getEventId(), entry);
        BY_SESSION.computeIfAbsent(entry.getSessionId(), ignored -> new ConcurrentHashMap<>())
                .put(entry.getEventId(), entry);
    }

    public static SftpScanHoldEntry get(String eventId) {
        return BY_EVENT.get(eventId);
    }

    public static List<SftpScanHoldEntry> listSession(String sessionId) {
        ConcurrentHashMap<String, SftpScanHoldEntry> session = BY_SESSION.get(sessionId);
        if (session == null) {
            return List.of();
        }
        return new ArrayList<>(session.values());
    }

    public static List<SftpScanHoldEntry> listActive() {
        List<SftpScanHoldEntry> active = new ArrayList<>();
        for (SftpScanHoldEntry entry : BY_EVENT.values()) {
            if (entry.getPhase() == SftpScanHoldEntry.Phase.POLLING
                    || entry.getPhase() == SftpScanHoldEntry.Phase.READY) {
                active.add(entry);
            }
        }
        return active;
    }

    public static void remove(String sessionId, String eventId) {
        BY_EVENT.remove(eventId);
        ConcurrentHashMap<String, SftpScanHoldEntry> session = BY_SESSION.get(sessionId);
        if (session != null) {
            session.remove(eventId);
            if (session.isEmpty()) {
                BY_SESSION.remove(sessionId);
            }
        }
    }

    public static void removeSession(String sessionId) {
        ConcurrentHashMap<String, SftpScanHoldEntry> session = BY_SESSION.remove(sessionId);
        if (session != null) {
            session.keySet().forEach(BY_EVENT::remove);
        }
    }

    public static void clearAll() {
        BY_EVENT.clear();
        BY_SESSION.clear();
    }
}
