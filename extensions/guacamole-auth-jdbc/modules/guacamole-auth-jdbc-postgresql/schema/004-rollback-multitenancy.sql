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
-- Roll back TBPAM multitenancy mapping data.
--
-- IMPORTANT:
--   This script removes tenant/object mapping rows and optional test tenants.
--   Run only if you want to return to non-tenant behavior.
--
-- Usage:
--   psql -U <user> -h <host> -d <db> -f 004-rollback-multitenancy.sql
--

BEGIN;

-- Remove all tenant-object mappings created for visibility filters.
DELETE FROM tbpam_tenant_connection_group_map;
DELETE FROM tbpam_tenant_connection_map;
DELETE FROM tbpam_tenant_user_group_map;
DELETE FROM tbpam_tenant_user_map;
DELETE FROM tenant_guac_users;

-- Optional cleanup: remove common test/bootstrap tenant records.
DELETE FROM tenants
WHERE code IN ('tenant-a', 'default', 'acme', 'globex');

COMMIT;
