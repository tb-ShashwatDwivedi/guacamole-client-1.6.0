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

package org.apache.guacamole.auth.jdbc.livemonitoring;

import com.google.inject.Inject;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduler that periodically syncs live_monitoring_keys.is_active with
 * guacamole_connection_history, marking sessions as inactive when the
 * connection history has end_date set.
 */
public class LiveMonitoringSyncScheduler {

    private static final Logger logger = LoggerFactory.getLogger(LiveMonitoringSyncScheduler.class);

    private static final int SYNC_INTERVAL_MINUTES = 1;

    @Inject
    private LiveMonitoringKeyService liveMonitoringKeyService;

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

    @Inject
    public void start() {
        // Run sync immediately on startup to fix any stale data
        try {
            liveMonitoringKeyService.syncClosedSessionsFromHistory();
        } catch (Throwable t) {
            logger.warn("Initial live monitoring sync failed: {}", t.getMessage());
        }
        executor.scheduleAtFixedRate(() -> {
            try {
                liveMonitoringKeyService.syncClosedSessionsFromHistory();
            } catch (Throwable t) {
                logger.warn("Live monitoring sync task failed: {}", t.getMessage());
            }
        }, SYNC_INTERVAL_MINUTES, SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES);
        logger.info("Live monitoring sync scheduler started (interval: {} min)", SYNC_INTERVAL_MINUTES);
    }

}
