package com.local.guac.sftp.scan;

public class SftpScanRequest {

    public static final String DIRECTION_UPLOAD = "upload";
    public static final String DIRECTION_DOWNLOAD = "download";

    private final byte[] fileBytes;
    private final String filename;
    private final String contentType;
    private final String username;
    private final String sessionId;
    private final String transferId;
    private final String direction;
    private final String sourcePath;
    private final String destinationPath;
    private final String connectionId;
    private final String connectionName;
    private final String assetHostname;
    private final String assetIp;

    public SftpScanRequest(byte[] fileBytes, String filename, String contentType,
            String username, String sessionId, String transferId, String direction,
            String sourcePath, String destinationPath, String connectionId,
            String connectionName, String assetHostname, String assetIp) {
        this.fileBytes = fileBytes;
        this.filename = filename;
        this.contentType = contentType;
        this.username = username;
        this.sessionId = sessionId;
        this.transferId = transferId;
        this.direction = direction;
        this.sourcePath = sourcePath;
        this.destinationPath = destinationPath;
        this.connectionId = connectionId;
        this.connectionName = connectionName;
        this.assetHostname = assetHostname;
        this.assetIp = assetIp;
    }

    public byte[] getFileBytes() {
        return fileBytes;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public String getUsername() {
        return username;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getTransferId() {
        return transferId;
    }

    public String getDirection() {
        return direction;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public String getDestinationPath() {
        return destinationPath;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public String getConnectionName() {
        return connectionName;
    }

    public String getAssetHostname() {
        return assetHostname;
    }

    public String getAssetIp() {
        return assetIp;
    }
}
