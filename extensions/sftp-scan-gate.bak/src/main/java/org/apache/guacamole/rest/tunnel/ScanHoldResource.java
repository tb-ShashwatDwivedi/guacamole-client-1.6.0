package org.apache.guacamole.rest.tunnel;

import com.local.guac.sftp.scan.SftpScanGate;
import com.local.guac.sftp.scan.SftpScanHoldEntry;
import com.local.guac.sftp.scan.SftpScanHoldMetadata;
import com.local.guac.sftp.scan.SftpScanHoldRegistry;
import com.local.guac.sftp.scan.SftpScanHoldService;
import com.local.guac.sftp.scan.SftpScanJson;
import com.local.guac.sftp.scan.SftpScanRequest;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.net.auth.AuthenticatedUser;
import org.apache.guacamole.rest.APIException;
import org.apache.guacamole.tunnel.UserTunnel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

@Produces(MediaType.APPLICATION_JSON)
public class ScanHoldResource {

    private static final Logger logger = LoggerFactory.getLogger(ScanHoldResource.class);

    private final UserTunnel tunnel;
    private final AuthenticatedUser authenticatedUser;

    public ScanHoldResource(UserTunnel tunnel, AuthenticatedUser authenticatedUser) {
        this.tunnel = tunnel;
        this.authenticatedUser = authenticatedUser;
    }

    @GET
    public Response listActive() {
        String sessionId = tunnel.getUUID().toString();
        return Response.ok(SftpScanJson.activeHolds(
                SftpScanHoldRegistry.listSession(sessionId)), MediaType.APPLICATION_JSON).build();
    }

    @DELETE
    public Response cleanupSession() {
        SftpScanGate.onSessionClosed(tunnel.getUUID().toString());
        return Response.noContent().build();
    }

    @DELETE
    @Path("{eventId}")
    public Response cancelHold(@PathParam("eventId") String eventId) {
        String sessionId = tunnel.getUUID().toString();
        SftpScanHoldEntry entry = SftpScanHoldService.getEntry(eventId);
        if (entry != null) {
            if (!sessionId.equals(entry.getSessionId())) {
                throw new javax.ws.rs.ForbiddenException("Scan hold belongs to another session");
            }
            SftpScanHoldEntry.Phase phase = entry.getPhase();
            if (phase == SftpScanHoldEntry.Phase.TRANSFERRING
                    || phase == SftpScanHoldEntry.Phase.COMPLETED) {
                return Response.status(409)
                        .entity("{\"message\":\"Transfer already in progress or finished\"}")
                        .build();
            }
        }
        else {
            try {
                if (!SftpScanHoldService.holdFileExists(sessionId, eventId)
                        && !SftpScanHoldService.store().listEventIds(sessionId).contains(eventId)) {
                    logger.debug("Cancel with no local hold; still reporting outcome to PAM: eventId={}",
                            eventId);
                }
            }
            catch (IOException e) {
                logger.warn("Unable to verify hold files for cancel eventId={}: {}", eventId,
                        e.getMessage());
            }
        }
        SftpScanHoldService.cancelHoldByUser(sessionId, eventId);
        return Response.noContent().build();
    }

    @GET
    @Path("{eventId}/status")
    public Response getStatus(@PathParam("eventId") String eventId) {
        SftpScanHoldEntry entry = requireEntry(eventId);
        String body = SftpScanJson.holdResponse(entry);
        if (entry.getPhase() == SftpScanHoldEntry.Phase.REJECTED) {
            SftpScanHoldRegistry.remove(entry.getSessionId(), eventId);
        }
        return Response.ok(body, MediaType.APPLICATION_JSON).build();
    }

    @POST
    @Path("{eventId}/resume")
    public Response resumeUpload(@PathParam("eventId") String eventId) throws GuacamoleException {
        SftpScanHoldEntry entry = requireEntry(eventId);
        if (entry.getPhase() != SftpScanHoldEntry.Phase.READY) {
            return Response.status(409).entity("{\"message\":\"Transfer not approved yet\"}").build();
        }

        SftpScanHoldMetadata metadata = entry.getMetadata();
        if (!SftpScanRequest.DIRECTION_UPLOAD.equals(metadata.getDirection())) {
            return Response.status(400).entity("{\"message\":\"Hold is not an upload\"}").build();
        }

        entry.setPhase(SftpScanHoldEntry.Phase.TRANSFERRING);
        java.nio.file.Path heldPath = SftpScanHoldService.heldFilePath(
                entry.getSessionId(), eventId);
        if (!java.nio.file.Files.isRegularFile(heldPath)) {
            SftpScanGate.reportFailed(eventId, "Held file missing");
            SftpScanHoldService.discardHold(entry.getSessionId(), eventId, null);
            throw new GuacamoleException("Held file no longer available");
        }
        try (InputStream heldStream = Files.newInputStream(heldPath)) {
            tunnel.interceptStream(metadata.getStreamIndex(), heldStream);
            SftpScanGate.reportCompleted(eventId);
            SftpScanHoldService.markCompleted(entry.getSessionId(), eventId);
            return Response.noContent().build();
        }
        catch (GuacamoleException e) {
            SftpScanGate.reportFailed(eventId, e.getMessage());
            SftpScanHoldService.discardHold(entry.getSessionId(), eventId, null);
            throw new APIException(e);
        }
        catch (IOException e) {
            SftpScanGate.reportFailed(eventId, e.getMessage());
            SftpScanHoldService.discardHold(entry.getSessionId(), eventId, null);
            throw new GuacamoleException("Unable to read held file", e);
        }
    }

    @GET
    @Path("{eventId}/content")
    @Produces("*/*")
    public Response downloadContent(@PathParam("eventId") String eventId) throws GuacamoleException {
        SftpScanHoldEntry entry = requireEntry(eventId);
        if (entry.getPhase() != SftpScanHoldEntry.Phase.READY) {
            return Response.status(409).entity("{\"message\":\"Transfer not approved yet\"}").build();
        }

        SftpScanHoldMetadata metadata = entry.getMetadata();
        if (!SftpScanRequest.DIRECTION_DOWNLOAD.equals(metadata.getDirection())) {
            return Response.status(400).entity("{\"message\":\"Hold is not a download\"}").build();
        }

        entry.setPhase(SftpScanHoldEntry.Phase.TRANSFERRING);
        java.nio.file.Path path = SftpScanHoldService.heldFilePath(entry.getSessionId(), eventId);
        if (!Files.isRegularFile(path)) {
            SftpScanGate.reportFailed(eventId, "Held file missing");
            SftpScanHoldService.discardHold(entry.getSessionId(), eventId, null);
            throw new GuacamoleException("Held file no longer available");
        }
        final String sessionId = entry.getSessionId();
        StreamingOutput stream = output -> {
            try (InputStream input = Files.newInputStream(path)) {
                input.transferTo(output);
                SftpScanGate.reportCompleted(eventId);
                SftpScanHoldService.markCompleted(sessionId, eventId);
            }
            catch (IOException e) {
                SftpScanGate.reportFailed(eventId, e.getMessage());
                SftpScanHoldService.discardHold(sessionId, eventId, null);
                throw new java.io.UncheckedIOException(e);
            }
        };

        Response.ResponseBuilder builder = Response.ok(stream, metadata.getContentType());
        if ("application/octet-stream".equals(metadata.getContentType())) {
            builder.header("Content-Disposition", "attachment");
        }
        return builder.build();
    }

    private SftpScanHoldEntry requireEntry(String eventId) {
        SftpScanHoldEntry entry = SftpScanHoldService.getEntry(eventId);
        if (entry == null) {
            throw new javax.ws.rs.NotFoundException("Scan hold not found");
        }
        String sessionId = tunnel.getUUID().toString();
        if (!sessionId.equals(entry.getSessionId())) {
            throw new javax.ws.rs.ForbiddenException("Scan hold belongs to another session");
        }
        return entry;
    }
}
