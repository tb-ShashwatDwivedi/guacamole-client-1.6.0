package com.local.guac.sftp.scan;

import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.net.auth.ActiveConnection;
import org.apache.guacamole.net.auth.AuthenticatedUser;
import org.apache.guacamole.net.auth.Connection;
import org.apache.guacamole.net.auth.UserContext;
import org.apache.guacamole.protocol.GuacamoleConfiguration;
import org.apache.guacamole.tunnel.UserTunnel;

import javax.ws.rs.core.HttpHeaders;
import java.util.UUID;

public final class SftpScanContext {

    private SftpScanContext() {
    }

    public static SftpScanRequest buildUploadRequest(UserTunnel tunnel,
            AuthenticatedUser authenticatedUser, String filename, String mediaType,
            HttpHeaders headers, byte[] fileBytes) {

        String sourcePath = headerOr(headers, "X-Source-Path", filename);
        String destinationPath = headerOr(headers, "X-Destination-Path", filename);
        String contentType = headerOr(headers, "X-File-Content-Type", mediaType);

        return buildRequest(tunnel, authenticatedUser, fileBytes, filename, contentType,
                SftpScanRequest.DIRECTION_UPLOAD, sourcePath, destinationPath);
    }

    public static SftpScanRequest buildDownloadRequest(UserTunnel tunnel,
            AuthenticatedUser authenticatedUser, String filename, String mediaType,
            HttpHeaders headers, byte[] fileBytes) {

        String sourcePath = headerOr(headers, "X-Source-Path", filename);
        String destinationPath = headerOr(headers, "X-Destination-Path", filename);
        String contentType = headerOr(headers, "X-File-Content-Type", mediaType);

        return buildRequest(tunnel, authenticatedUser, fileBytes, filename, contentType,
                SftpScanRequest.DIRECTION_DOWNLOAD, sourcePath, destinationPath);
    }

    private static SftpScanRequest buildRequest(UserTunnel tunnel,
            AuthenticatedUser authenticatedUser, byte[] fileBytes, String filename,
            String contentType, String direction, String sourcePath,
            String destinationPath) {

        ConnectionInfo info = resolveConnectionInfo(tunnel);
        String username = authenticatedUser.getIdentifier();
        String sessionId = tunnel.getUUID().toString();
        String transferId = UUID.randomUUID().toString();

        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }

        return new SftpScanRequest(fileBytes, filename, contentType, username, sessionId,
                transferId, direction, sourcePath, destinationPath, info.connectionId,
                info.connectionName, info.assetHostname, info.assetIp);
    }

    private static String headerOr(HttpHeaders headers, String name, String defaultValue) {
        if (headers == null) {
            return defaultValue;
        }
        String value = headers.getHeaderString(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static ConnectionInfo resolveConnectionInfo(UserTunnel tunnel) {
        ConnectionInfo info = new ConnectionInfo();
        try {
            ActiveConnection activeConnection = tunnel.getActiveConnection();
            if (activeConnection == null) {
                return info;
            }

            info.connectionId = activeConnection.getConnectionIdentifier();
            info.assetIp = activeConnection.getRemoteHost();

            UserContext userContext = tunnel.getUserContext();
            Connection connection = userContext.getConnectionDirectory().get(info.connectionId);
            if (connection == null) {
                return info;
            }

            info.connectionName = connection.getName();
            GuacamoleConfiguration configuration = connection.getConfiguration();
            if (configuration != null) {
                String hostname = configuration.getParameter("hostname");
                if (hostname != null && !hostname.isBlank()) {
                    info.assetHostname = hostname;
                }
                if (info.assetIp == null || info.assetIp.isBlank()) {
                    info.assetIp = hostname;
                }
            }
        }
        catch (GuacamoleException ignored) {
            // leave defaults
        }
        return info;
    }

    private static final class ConnectionInfo {
        private String connectionId;
        private String connectionName;
        private String assetHostname;
        private String assetIp;
    }
}
