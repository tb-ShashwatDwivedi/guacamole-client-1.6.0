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

import java.util.Date;

/**
 * Model representing a record in the live_monitoring_keys table, which stores
 * share keys for live monitoring of active connections.
 */
public class LiveMonitoringKeyModel {

    /**
     * The database ID of this record.
     */
    private Integer id;

    /**
     * The session/connection history UUID.
     */
    private String sessionId;

    /**
     * The monitoring share key, or "no sharing profile found" if none exists.
     */
    private String monitoringKey;

    /**
     * The sharing profile identifier, or null if no sharing profile.
     */
    private Integer sharingProfileId;

    /**
     * The sharing profile name, or null if no sharing profile.
     */
    private String sharingProfileName;

    /**
     * When this record was created.
     */
    private Date createdAt;

    /**
     * When this key expires.
     */
    private Date expiresAt;

    /**
     * Whether this key is currently active.
     */
    private Boolean isActive;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getMonitoringKey() {
        return monitoringKey;
    }

    public void setMonitoringKey(String monitoringKey) {
        this.monitoringKey = monitoringKey;
    }

    public Integer getSharingProfileId() {
        return sharingProfileId;
    }

    public void setSharingProfileId(Integer sharingProfileId) {
        this.sharingProfileId = sharingProfileId;
    }

    public String getSharingProfileName() {
        return sharingProfileName;
    }

    public void setSharingProfileName(String sharingProfileName) {
        this.sharingProfileName = sharingProfileName;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Date getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Date expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

}
