package org.apache.guacamole.tunnel;

import com.local.guac.sftp.scan.SftpScanGate;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.io.GuacamoleReader;
import org.apache.guacamole.net.DelegatingGuacamoleTunnel;
import org.apache.guacamole.net.GuacamoleTunnel;
import org.apache.guacamole.protocol.FilteredGuacamoleReader;
import org.apache.guacamole.protocol.GuacamoleFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class StreamInterceptingTunnel extends DelegatingGuacamoleTunnel {

    private static final Logger logger = LoggerFactory.getLogger(StreamInterceptingTunnel.class);

    private final InputStreamInterceptingFilter inputStreamFilter =
            new InputStreamInterceptingFilter(this);
    private final OutputStreamInterceptingFilter outputStreamFilter =
            new OutputStreamInterceptingFilter(this);

    public StreamInterceptingTunnel(GuacamoleTunnel tunnel) {
        super(tunnel);
    }

    public void interceptStream(int index, OutputStream stream) throws GuacamoleException {
        logger.debug("Intercepting output stream #{} of tunnel \"{}\".", index, getUUID());
        try {
            outputStreamFilter.interceptStream(index, new BufferedOutputStream(stream));
        }
        finally {
            logger.debug("Intercepted output stream #{} of tunnel \"{}\" ended.", index, getUUID());
        }
    }

    public void interceptStream(int index, InputStream stream) throws GuacamoleException {
        logger.debug("Intercepting input stream #{} of tunnel \"{}\".", index, getUUID());
        try {
            inputStreamFilter.interceptStream(index, new BufferedInputStream(stream));
        }
        finally {
            logger.debug("Intercepted input stream #{} of tunnel \"{}\" ended.", index, getUUID());
        }
    }

    @Override
    public GuacamoleReader acquireReader() {
        GuacamoleReader reader = super.acquireReader();
        reader = new FilteredGuacamoleReader(reader, (GuacamoleFilter) inputStreamFilter);
        reader = new FilteredGuacamoleReader(reader, (GuacamoleFilter) outputStreamFilter);
        return reader;
    }

    @Override
    public synchronized void close() throws GuacamoleException {
        try {
            super.close();
        }
        finally {
            inputStreamFilter.closeAllInterceptedStreams();
            outputStreamFilter.closeAllInterceptedStreams();
            // Idle timeout / disconnect — detach holds; PAM review expires on 24h sweeper only.
            try {
                SftpScanGate.onSessionClosed(getUUID().toString());
            }
            catch (Exception e) {
                logger.warn("Scan-hold expire on tunnel close failed: {}", e.getMessage());
            }
        }
    }
}
