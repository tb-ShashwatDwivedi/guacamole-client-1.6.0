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

import org.apache.ibatis.annotations.Param;

/**
 * Mapper for the live_monitoring_keys table.
 */
public interface LiveMonitoringKeyMapper {

    /**
     * Inserts a new live monitoring key record.
     *
     * @param record
     *     The record to insert.
     *
     * @return
     *     The number of rows inserted.
     */
    int insert(@Param("record") LiveMonitoringKeyModel record);

    /**
     * Deletes all live monitoring key records for the given session.
     * Used to make recordLiveMonitoringKeys idempotent when called
     * multiple times for the same session.
     *
     * @param sessionId
     *     The session ID whose records should be deleted.
     *
     * @return
     *     The number of rows deleted.
     */
    int deleteBySessionId(@Param("sessionId") String sessionId);

    /**
     * Sets is_active to false for all records matching the given session.
     * Called when a session is closed so that closed sessions can be identified.
     *
     * @param sessionId
     *     The session ID whose records should be marked inactive.
     *
     * @return
     *     The number of rows updated.
     */
    int updateIsActiveFalseForSession(@Param("sessionId") String sessionId);

}
