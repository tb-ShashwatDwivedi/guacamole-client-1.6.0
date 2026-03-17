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
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.Set;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.auth.jdbc.connection.ModeledConnection;
import org.apache.guacamole.auth.jdbc.sharing.ConnectionSharingService;
import org.apache.guacamole.auth.jdbc.sharing.connection.SharedConnectionDefinition;
import org.apache.guacamole.auth.jdbc.sharingprofile.ModeledSharingProfile;
import org.apache.guacamole.auth.jdbc.sharingprofile.SharingProfileMapper;
import org.apache.guacamole.auth.jdbc.sharingprofile.SharingProfileModel;
import org.apache.guacamole.auth.jdbc.tunnel.ActiveConnectionRecord;
import org.apache.guacamole.auth.jdbc.user.ModeledAuthenticatedUser;
import org.apache.guacamole.auth.jdbc.user.RemoteAuthenticatedUser;
import org.apache.guacamole.properties.CaseSensitivity;
import org.apache.guacamole.auth.jdbc.JDBCEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for inserting live monitoring keys into the live_monitoring_keys
 * table whenever a session is created.
 */
public class LiveMonitoringKeyService {

    /**
     * Logger for this class.
     */
    private static final Logger logger = LoggerFactory.getLogger(LiveMonitoringKeyService.class);

    /**
     * Message to store when no sharing profile exists for a connection.
     */
    public static final String NO_SHARING_PROFILE_FOUND = "no sharing profile found";

    /**
     * Default expiration period in hours.
     */
    private static final int EXPIRATION_HOURS = 24;

    @Inject
    private LiveMonitoringKeyMapper liveMonitoringKeyMapper;

    @Inject
    private ConnectionSharingService connectionSharingService;

    @Inject
    private SharingProfileMapper sharingProfileMapper;

    @Inject
    private JDBCEnvironment environment;

    /**
     * Records live monitoring keys for the given active connection. For each
     * sharing profile associated with the connection, generates a share key and
     * inserts a record. If no sharing profiles exist, inserts a single record
     * with "no sharing profile found" in the monitoring_key column.
     *
     * @param activeConnection
     *     The active connection record for the newly started session.
     *
     * @param sessionId
     *     The UUID of the connection history/session.
     */
    public void recordLiveMonitoringKeys(ActiveConnectionRecord activeConnection,
            String sessionId) {

        try {
            ModeledConnection connection = activeConnection.getConnection();
            Set<String> sharingProfileIds = connection.getSharingProfileIdentifiers();

            Date now = new Date();
            Date expiresAt = addHours(now, EXPIRATION_HOURS);

            if (sharingProfileIds == null || sharingProfileIds.isEmpty()) {
                insertNoSharingProfileRecord(sessionId, now, expiresAt);
            } else {
                ModeledAuthenticatedUser user = getModeledUser(activeConnection);
                if (user == null) {
                    logger.warn("Cannot record live monitoring keys: user is not ModeledAuthenticatedUser");
                    insertNoSharingProfileRecord(sessionId, now, expiresAt);
                    return;
                }

                Collection<SharingProfileModel> sharingProfiles = sharingProfileMapper.select(
                        sharingProfileIds, environment.getCaseSensitivity());

                for (SharingProfileModel profile : sharingProfiles) {
                    try {
                        SharedConnectionDefinition definition = connectionSharingService
                                .shareConnection(user, activeConnection, profile.getIdentifier());
                        insertRecord(sessionId, definition.getShareKey(),
                                profile.getObjectID(), profile.getName(), now, expiresAt);
                    } catch (GuacamoleException e) {
                        logger.warn("Failed to generate share key for profile {}: {}",
                                profile.getName(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to record live monitoring keys for session {}: {}",
                    sessionId, e.getMessage(), e);
        }
    }

    /**
     * Returns the ModeledAuthenticatedUser from the active connection's user,
     * or null if the user is not a ModeledAuthenticatedUser.
     */
    private ModeledAuthenticatedUser getModeledUser(ActiveConnectionRecord activeConnection) {
        RemoteAuthenticatedUser user = activeConnection.getUser();
        if (user instanceof ModeledAuthenticatedUser) {
            return (ModeledAuthenticatedUser) user;
        }
        return null;
    }

    /**
     * Inserts a record for a connection with no sharing profiles.
     */
    private void insertNoSharingProfileRecord(String sessionId, Date createdAt, Date expiresAt) {
        LiveMonitoringKeyModel record = new LiveMonitoringKeyModel();
        record.setSessionId(sessionId);
        record.setMonitoringKey(NO_SHARING_PROFILE_FOUND);
        record.setSharingProfileId(null);
        record.setSharingProfileName(null);
        record.setCreatedAt(createdAt);
        record.setExpiresAt(expiresAt);
        record.setIsActive(true);
        liveMonitoringKeyMapper.insert(record);
    }

    /**
     * Inserts a record for a connection with a valid share key.
     */
    private void insertRecord(String sessionId, String monitoringKey,
            Integer sharingProfileId, String sharingProfileName,
            Date createdAt, Date expiresAt) {
        LiveMonitoringKeyModel record = new LiveMonitoringKeyModel();
        record.setSessionId(sessionId);
        record.setMonitoringKey(monitoringKey);
        record.setSharingProfileId(sharingProfileId);
        record.setSharingProfileName(sharingProfileName);
        record.setCreatedAt(createdAt);
        record.setExpiresAt(expiresAt);
        record.setIsActive(true);
        liveMonitoringKeyMapper.insert(record);
    }

    /**
     * Adds the given number of hours to a date.
     */
    private static Date addHours(Date date, int hours) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.add(Calendar.HOUR_OF_DAY, hours);
        return cal.getTime();
    }

}
