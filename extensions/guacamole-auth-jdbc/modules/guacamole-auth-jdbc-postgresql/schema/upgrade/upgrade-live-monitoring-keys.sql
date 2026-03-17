--
-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--   http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.
--

--
-- Create live_monitoring_keys table for storing share keys when sessions start.
-- Run this only if the table does not already exist.
--
-- Schema matches: id | session_id | monitoring_key | sharing_profile_id |
--                 sharing_profile_name | created_at | expires_at | is_active
--

CREATE TABLE IF NOT EXISTS live_monitoring_keys (
    id                      serial       NOT NULL,
    session_id              varchar(64)  NOT NULL,
    monitoring_key          varchar(256) NOT NULL,
    sharing_profile_id      integer      DEFAULT NULL,
    sharing_profile_name    varchar(128) DEFAULT NULL,
    created_at             timestamptz  NOT NULL,
    expires_at              timestamptz  NOT NULL,
    is_active               boolean      NOT NULL DEFAULT true,

    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS live_monitoring_keys_session_id
    ON live_monitoring_keys(session_id);

CREATE INDEX IF NOT EXISTS live_monitoring_keys_is_active
    ON live_monitoring_keys(is_active);
