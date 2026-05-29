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

package org.apache.guacamole.auth.jdbc.tunnel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.auth.jdbc.connection.ConnectionModel;
import org.apache.guacamole.auth.jdbc.connection.ConnectionParameterMapper;
import org.apache.guacamole.auth.jdbc.connection.ConnectionParameterModel;
import org.apache.guacamole.auth.jdbc.connection.ModeledConnection;
import org.apache.guacamole.auth.jdbc.connectiongroup.ModeledConnectionGroup;
import org.apache.guacamole.auth.jdbc.sharingprofile.SharingProfileParameterMapper;
import org.apache.guacamole.auth.jdbc.sharingprofile.SharingProfileParameterModel;
import org.apache.guacamole.auth.jdbc.user.RemoteAuthenticatedUser;
import org.apache.guacamole.protocol.GuacamoleConfiguration;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for asset-aware tunnel configuration generation.
 */
public class AbstractGuacamoleTunnelServiceTest {

    /**
     * Minimal concrete service used to invoke AbstractGuacamoleTunnelService
     * behavior under test.
     */
    private static class TestTunnelService extends AbstractGuacamoleTunnelService {

        @Override
        protected ModeledConnection acquire(RemoteAuthenticatedUser user,
                List<ModeledConnection> connections, boolean includeFailoverOnly)
                throws GuacamoleException {
            return null;
        }

        @Override
        protected void release(RemoteAuthenticatedUser user,
                ModeledConnection connection) {
        }

        @Override
        protected void acquire(RemoteAuthenticatedUser user,
                ModeledConnectionGroup connectionGroup)
                throws GuacamoleException {
        }

        @Override
        protected void release(RemoteAuthenticatedUser user,
                ModeledConnectionGroup connectionGroup) {
        }

    }

    /**
     * Simple mapper that returns a fixed parameter set for a connection.
     */
    private static class StubConnectionParameterMapper
            implements ConnectionParameterMapper {

        @Override
        public Collection<ConnectionParameterModel> select(String identifier) {
            ConnectionParameterModel hostname = new ConnectionParameterModel();
            hostname.setConnectionIdentifier(identifier);
            hostname.setName("hostname");
            hostname.setValue("192.168.0.19");

            ConnectionParameterModel username = new ConnectionParameterModel();
            username.setConnectionIdentifier(identifier);
            username.setName("username");
            username.setValue("root");

            return Arrays.asList(hostname, username);
        }

        @Override
        public int insert(Collection<ConnectionParameterModel> parameters) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int delete(String identifier) {
            throw new UnsupportedOperationException();
        }

    }

    /**
     * Empty sharing-profile mapper for reflective invocation safety.
     */
    private static class StubSharingProfileParameterMapper
            implements SharingProfileParameterMapper {

        @Override
        public Collection<SharingProfileParameterModel> select(String identifier) {
            return Collections.emptyList();
        }

        @Override
        public int insert(Collection<SharingProfileParameterModel> parameters) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int delete(String identifier) {
            throw new UnsupportedOperationException();
        }

    }

    /**
     * Sets a private field on the given service instance.
     */
    private static void setField(Object target, String fieldName, Object value)
            throws Exception {
        Field field = AbstractGuacamoleTunnelService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * Verifies tunnel configuration generation includes the stable connection
     * identifier as the asset-id parameter.
     */
    @Test
    public void testGetGuacamoleConfigurationIncludesAssetId() throws Exception {

        TestTunnelService service = new TestTunnelService();
        setField(service, "connectionParameterMapper",
                new StubConnectionParameterMapper());
        setField(service, "sharingProfileParameterMapper",
                new StubSharingProfileParameterMapper());

        ConnectionModel model = new ConnectionModel();
        model.setObjectID(42);
        model.setName("monday");
        model.setProtocol("ssh");

        ModeledConnection connection = new ModeledConnection();
        connection.setModel(model);

        Method method = AbstractGuacamoleTunnelService.class.getDeclaredMethod(
                "getGuacamoleConfiguration", ModeledConnection.class,
                String.class,
                org.apache.guacamole.auth.jdbc.sharingprofile.ModeledSharingProfile.class);
        method.setAccessible(true);

        GuacamoleConfiguration config = (GuacamoleConfiguration) method.invoke(
                service, connection, null, null);

        assertEquals("42", config.getParameter("asset-id"));
        assertEquals("192.168.0.19", config.getParameter("hostname"));

    }

}
