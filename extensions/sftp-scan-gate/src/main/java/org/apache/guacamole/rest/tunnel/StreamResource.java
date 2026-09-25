package org.apache.guacamole.rest.tunnel;

import com.local.guac.sftp.scan.SftpScanBlockedException;
import com.local.guac.sftp.scan.SftpScanBufferManager;
import com.local.guac.sftp.scan.SftpScanContext;
import com.local.guac.sftp.scan.SftpScanDecision;
import com.local.guac.sftp.scan.SftpScanGate;
import com.local.guac.sftp.scan.SftpScanHoldService;
import com.local.guac.sftp.scan.SftpScanJson;
import com.local.guac.sftp.scan.SftpScanRequest;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.net.auth.AuthenticatedUser;
import org.apache.guacamole.rest.APIException;
import org.apache.guacamole.rest.RequestSizeFilter;
import org.apache.guacamole.tunnel.UserTunnel;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

@Produces("application/json")
@Consumes("application/json")
public class StreamResource {

    private final UserTunnel tunnel;
    private final AuthenticatedUser authenticatedUser;
    private final int streamIndex;
    private final String mediaType;
    private final String filename;

    public StreamResource(UserTunnel tunnel, AuthenticatedUser authenticatedUser,
            int streamIndex, String mediaType, String filename) {
        this.tunnel = tunnel;
        this.authenticatedUser = authenticatedUser;
        this.streamIndex = streamIndex;
        this.mediaType = mediaType;
        this.filename = filename;
    }

    @GET
    public Response getStreamContents(@Context HttpHeaders headers) throws GuacamoleException {
        // Downloads are never malware-scanned. Stream bytes to the browser the
        // same way stock Guacamole does, then log outbound in PAM.
        boolean audit = SftpScanGate.shouldScan(tunnel);
        SftpScanRequest auditRequest = audit
                ? SftpScanContext.buildDownloadRequest(tunnel, authenticatedUser,
                        filename, mediaType, headers, new byte[0])
                : null;

        StreamingOutput stream = output -> {
            CountingOutputStream counted = new CountingOutputStream(output);
            try {
                tunnel.interceptStream(streamIndex, counted);
            }
            catch (GuacamoleException e) {
                throw new IOException(e);
            }
            if (auditRequest != null) {
                SftpScanGate.auditOutboundDownload(auditRequest, counted.count);
            }
        };

        String safeName = filename == null ? "download" : filename.replace("\"", "_");
        return Response.ok(stream, "application/octet-stream")
                .header("Content-Disposition", "attachment; filename=\"" + safeName + "\"")
                .build();
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private long count;

        private CountingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }
    }

    private Response evaluateAndRespond(SftpScanRequest scanRequest, byte[] fileBytes,
            boolean isDownload) throws GuacamoleException {

        SftpScanDecision decision;
        try {
            decision = SftpScanGate.evaluateScan(scanRequest);
        }
        catch (SftpScanBlockedException e) {
            throw new APIException(e);
        }

        if (decision.isHeld()) {
            try {
                SftpScanHoldService.createHold(scanRequest, streamIndex,
                        tunnel.getUUID().toString(), decision.getResult(), fileBytes);
                return Response.status(202)
                        .entity(SftpScanJson.holdResponse(
                                SftpScanHoldService.getEntry(decision.getEventId())))
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }
            catch (IOException e) {
                throw new GuacamoleException("Unable to store file for sandbox review", e);
            }
        }

        try {
            if (isDownload) {
                return buildDownloadResponse(fileBytes, decision);
            }
            tunnel.interceptStream(streamIndex, new ByteArrayInputStream(fileBytes));
            SftpScanGate.reportCompleted(decision.getEventId());
            return buildUploadSuccess(decision);
        }
        catch (GuacamoleException e) {
            SftpScanGate.reportFailed(decision.getEventId(), e.getMessage());
            throw new APIException(e);
        }
    }

    private Response buildDownloadResponse(byte[] fileBytes, SftpScanDecision decision) {
        SftpScanGate.reportCompleted(decision.getEventId());
        Response.ResponseBuilder responseBuilder = Response.ok(
                (StreamingOutput) output -> output.write(fileBytes), mediaType);
        if ("application/octet-stream".equals(mediaType)) {
            responseBuilder.header("Content-Disposition", "attachment");
        }
        if (decision.hasWarning()) {
            responseBuilder.header(SftpScanDecision.WARNING_HEADER, decision.getWarningMessage());
        }
        return responseBuilder.build();
    }

    private Response buildUploadSuccess(SftpScanDecision decision) {
        Response.ResponseBuilder responseBuilder = Response.noContent();
        if (decision.hasWarning()) {
            responseBuilder.header(SftpScanDecision.WARNING_HEADER, decision.getWarningMessage());
        }
        return responseBuilder.build();
    }

    private Response buildStreamingResponse(StreamingWriter writer) throws GuacamoleException {
        StreamingOutput stream = output -> {
            try {
                writer.write(output);
            }
            catch (GuacamoleException e) {
                throw new IOException(e);
            }
        };
        return buildResponse(stream);
    }

    private Response buildResponse(StreamingOutput stream) {
        Response.ResponseBuilder responseBuilder = Response.ok(stream, mediaType);
        if ("application/octet-stream".equals(mediaType)) {
            responseBuilder.header("Content-Disposition", "attachment");
        }
        return responseBuilder.build();
    }

    @FunctionalInterface
    private interface StreamingWriter {
        void write(OutputStream output) throws GuacamoleException;
    }

    @POST
    @Consumes("*/*")
    @RequestSizeFilter.DoNotLimit
    public Response setStreamContents(InputStream data, @Context HttpHeaders headers)
            throws GuacamoleException {

        byte[] chunk = readAllBytes(data);
        String sessionId = tunnel.getUUID().toString();

        if (!SftpScanGate.shouldScan(tunnel)) {
            tunnel.interceptStream(streamIndex, new ByteArrayInputStream(chunk));
            return Response.noContent().build();
        }

        long fileSize = parseHeaderLong(headers, "X-File-Size", chunk.length);
        long chunkOffset = parseHeaderLong(headers, "X-Chunk-Offset", 0);

        if (chunkOffset == 0) {
            SftpScanBufferManager.beginUpload(sessionId, streamIndex, fileSize);
        }

        try {
            SftpScanBufferManager.appendChunk(sessionId, streamIndex, chunk, chunkOffset);
        }
        catch (IOException e) {
            SftpScanBufferManager.clear(sessionId, streamIndex);
            throw new GuacamoleException("Unable to buffer upload for malware scan", e);
        }

        if (!SftpScanBufferManager.isUploadComplete(sessionId, streamIndex)) {
            return Response.noContent().build();
        }

        byte[] fileBytes = SftpScanBufferManager.getAndClear(sessionId, streamIndex);
        SftpScanRequest scanRequest = SftpScanContext.buildUploadRequest(tunnel,
                authenticatedUser, filename, mediaType, headers, fileBytes);

        return evaluateAndRespond(scanRequest, fileBytes, false);
    }

    private static long parseHeaderLong(HttpHeaders headers, String name, long defaultValue) {
        String value = headers.getHeaderString(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        }
        catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static byte[] readAllBytes(InputStream input) throws GuacamoleException {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
        catch (IOException e) {
            throw new GuacamoleException("Unable to read uploaded stream", e);
        }
    }
}
