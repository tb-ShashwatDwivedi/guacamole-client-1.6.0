package com.local.guac.sftp.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

public final class SftpScanJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SftpScanJson() {
    }

    public static String holdResponse(SftpScanHoldEntry entry) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode data = root.putObject("data");
        data.put("eventId", entry.getEventId());
        data.put("terminal", entry.getPhase() != SftpScanHoldEntry.Phase.POLLING);
        data.put("scanStatus", entry.getScanStatus());
        data.put("phase", entry.getPhase().name());
        data.put("pollAfterSeconds", entry.getPollAfterSeconds());
        data.put("filename", entry.getMetadata().getFilename());
        if (entry.getUserMessage() != null) {
            data.put("userMessage", entry.getUserMessage());
        }
        data.put("allowed", entry.getPhase() == SftpScanHoldEntry.Phase.READY);
        if (entry.getPhase() == SftpScanHoldEntry.Phase.REJECTED) {
            data.put("allowed", false);
            data.put("terminal", true);
        }
        return root.toString();
    }

    public static String activeHolds(List<SftpScanHoldEntry> entries) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode data = root.putArray("data");
        for (SftpScanHoldEntry entry : entries) {
            ObjectNode item = data.addObject();
            item.put("eventId", entry.getEventId());
            item.put("scanStatus", entry.getScanStatus());
            item.put("phase", entry.getPhase().name());
            item.put("filename", entry.getMetadata().getFilename());
            if (entry.getUserMessage() != null) {
                item.put("userMessage", entry.getUserMessage());
            }
        }
        root.put("count", entries.size());
        return root.toString();
    }
}
