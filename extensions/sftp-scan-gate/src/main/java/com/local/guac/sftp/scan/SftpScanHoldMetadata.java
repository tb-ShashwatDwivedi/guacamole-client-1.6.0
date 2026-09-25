package com.local.guac.sftp.scan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SftpScanHoldMetadata {

    private String eventId;
    private String sessionId;
    private String tunnelUuid;
    private String transferId;
    private String filename;
    private String contentType;
    private String direction;
    private String sourcePath;
    private String destinationPath;
    private int streamIndex;
    private long createdAtMs;

    public SftpScanHoldMetadata() {
    }

    public static SftpScanHoldMetadata fromRequest(SftpScanRequest request, int streamIndex,
            String tunnelUuid) {
        SftpScanHoldMetadata meta = new SftpScanHoldMetadata();
        meta.sessionId = request.getSessionId();
        meta.tunnelUuid = tunnelUuid;
        meta.transferId = request.getTransferId();
        meta.filename = request.getFilename();
        meta.contentType = request.getContentType();
        meta.direction = request.getDirection();
        meta.sourcePath = request.getSourcePath();
        meta.destinationPath = request.getDestinationPath();
        meta.streamIndex = streamIndex;
        meta.createdAtMs = System.currentTimeMillis();
        return meta;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTunnelUuid() {
        return tunnelUuid;
    }

    public void setTunnelUuid(String tunnelUuid) {
        this.tunnelUuid = tunnelUuid;
    }

    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public String getDestinationPath() {
        return destinationPath;
    }

    public void setDestinationPath(String destinationPath) {
        this.destinationPath = destinationPath;
    }

    public int getStreamIndex() {
        return streamIndex;
    }

    public void setStreamIndex(int streamIndex) {
        this.streamIndex = streamIndex;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public void setCreatedAtMs(long createdAtMs) {
        this.createdAtMs = createdAtMs;
    }
}
