package org.apache.guacamole.rest.tunnel;

import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleResourceNotFoundException;
import org.apache.guacamole.environment.Environment;
import org.apache.guacamole.net.auth.ActiveConnection;
import org.apache.guacamole.net.auth.AuthenticatedUser;
import org.apache.guacamole.net.auth.Directory;
import org.apache.guacamole.net.auth.UserContext;
import org.apache.guacamole.protocols.ProtocolInfo;
import org.apache.guacamole.rest.activeconnection.APIActiveConnection;
import org.apache.guacamole.rest.directory.DirectoryObjectResource;
import org.apache.guacamole.rest.directory.DirectoryObjectResourceFactory;
import org.apache.guacamole.tunnel.UserTunnel;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;

@Produces("application/json")
@Consumes("application/json")
public class TunnelResource {

    private static final String DEFAULT_MEDIA_TYPE = "application/octet-stream";

    private final AuthenticatedUser authenticatedUser;
    private final UserTunnel tunnel;

    @Inject
    private Environment environment;

    @Inject
    private DirectoryObjectResourceFactory<ActiveConnection, APIActiveConnection> activeConnectionResourceFactory;

    @AssistedInject
    public TunnelResource(@Assisted AuthenticatedUser authenticatedUser,
            @Assisted UserTunnel tunnel) {
        this.authenticatedUser = authenticatedUser;
        this.tunnel = tunnel;
    }

    @Path("activeConnection")
    public DirectoryObjectResource<ActiveConnection, APIActiveConnection> getActiveConnection()
            throws GuacamoleException {
        UserContext userContext = tunnel.getUserContext();
        ActiveConnection activeConnection = tunnel.getActiveConnection();
        if (activeConnection == null) {
            throw new GuacamoleResourceNotFoundException("No readable active connection for tunnel.");
        }
        return activeConnectionResourceFactory.create(authenticatedUser, userContext,
                (Directory<ActiveConnection>) userContext.getActiveConnectionDirectory(),
                activeConnection);
    }

    @GET
    @Path("protocol")
    public ProtocolInfo getProtocol() throws GuacamoleException {
        String protocol = tunnel.getSocket().getProtocol();
        if (protocol == null) {
            throw new GuacamoleResourceNotFoundException("Protocol of tunnel is not known/exposed.");
        }
        ProtocolInfo info = environment.getProtocol(protocol);
        if (info == null) {
            return new ProtocolInfo(protocol);
        }
        return info;
    }

    @Path("streams/{index}/{filename}")
    public StreamResource getStream(@PathParam("index") int streamIndex,
            @QueryParam("type") @DefaultValue(DEFAULT_MEDIA_TYPE) String mediaType,
            @PathParam("filename") String filename) throws GuacamoleException {
        return new StreamResource(tunnel, authenticatedUser, streamIndex, mediaType, filename);
    }

    @Path("scan-hold")
    public ScanHoldResource getScanHold() {
        return new ScanHoldResource(tunnel, authenticatedUser);
    }
}
