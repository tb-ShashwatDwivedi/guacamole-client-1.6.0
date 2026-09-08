/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.guacamole.auth.restrict;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.io.GuacamoleWriter;
import org.apache.guacamole.net.DelegatingGuacamoleTunnel;
import org.apache.guacamole.net.GuacamoleTunnel;
import org.apache.guacamole.protocol.GuacamoleInstruction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tunnel wrapper that periodically re-verifies restrictions and forcefully
 * terminates the tunnel as soon as restrictions are no longer satisfied.
 */
public class TimeRestrictedGuacamoleTunnel extends DelegatingGuacamoleTunnel {

    private static final Logger LOGGER = LoggerFactory.getLogger(TimeRestrictedGuacamoleTunnel.class);

    /**
     * Custom status code emitted when access window expires during an active
     * connection.
     */
    private static final int STATUS_ACCESS_WINDOW_EXPIRED = 0x020C;

    private static final String ACCESS_WINDOW_EXPIRED_MESSAGE =
            "Connection access window expired. Please reconnect during an allowed time.";

    /**
     * Frequency for active restriction checks.
     */
    private static final int RESTRICTION_CHECK_INTERVAL_SECONDS = 15;

    private static final ScheduledExecutorService RESTRICTION_CHECKER =
            Executors.newSingleThreadScheduledExecutor(new RestrictionCheckerThreadFactory());

    private final Restrictable restrictable;
    private final String remoteAddress;
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private final ScheduledFuture<?> restrictionCheckTask;

    public TimeRestrictedGuacamoleTunnel(GuacamoleTunnel tunnel, Restrictable restrictable,
            String remoteAddress) {
        super(tunnel);
        this.restrictable = restrictable;
        this.remoteAddress = remoteAddress;

        this.restrictionCheckTask = RESTRICTION_CHECKER.scheduleAtFixedRate(this::enforceRestrictionWindow,
                RESTRICTION_CHECK_INTERVAL_SECONDS,
                RESTRICTION_CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    private void enforceRestrictionWindow() {
        if (terminated.get() || !isOpen()) {
            return;
        }

        try {
            RestrictionVerificationService.verifyConnectionRestrictions(restrictable, remoteAddress);
        } catch (GuacamoleException e) {
            terminateWithRestrictionError(e);
        }
    }

    private void terminateWithRestrictionError(GuacamoleException cause) {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
        restrictionCheckTask.cancel(false);

        LOGGER.info("Terminating connection {} due to restriction expiry: {}",
                getUUID(), cause.getMessage());

        GuacamoleWriter writer = null;
        try {
            writer = acquireWriter();
            writer.writeInstruction(new GuacamoleInstruction("error",
                    ACCESS_WINDOW_EXPIRED_MESSAGE,
                    Integer.toString(STATUS_ACCESS_WINDOW_EXPIRED)));
        } catch (GuacamoleException e) {
            LOGGER.debug("Unable to send restriction expiry error for tunnel {}.",
                    getUUID(), e);
        } finally {
            if (writer != null) {
                releaseWriter();
            }
        }

        try {
            super.close();
        } catch (GuacamoleException e) {
            LOGGER.debug("Unable to close tunnel {} after restriction expiry.",
                    getUUID(), e);
        }
    }

    @Override
    public void close() throws GuacamoleException {
        terminated.set(true);
        restrictionCheckTask.cancel(false);
        super.close();
    }

    private static class RestrictionCheckerThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "guac-restrict-connection-checker");
            thread.setDaemon(true);
            return thread;
        }
    }
}
