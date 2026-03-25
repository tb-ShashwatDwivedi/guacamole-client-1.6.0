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
/**
* A directive for displaying a non-global notification describing the status
* of a specific Guacamole client, including prompts for any information
* necessary to continue the connection.
*/
angular.module('client').directive('guacClientNotification', [function guacClientNotification() {
    const directive = {
        restrict: 'E',
        replace: true,
        templateUrl: 'app/client/templates/guacClientNotification.html'
    };
    directive.scope = {
        client : '='
    };
    directive.controller = ['$scope', '$injector',
        function guacClientNotificationController($scope, $injector) {
        const ManagedClient      = $injector.get('ManagedClient');
        const ManagedClientState = $injector.get('ManagedClientState');
        const Protocol           = $injector.get('Protocol');
        const authenticationService = $injector.get('authenticationService');
        const guacClientManager     = $injector.get('guacClientManager');
        const guacTranslate         = $injector.get('guacTranslate');
        const requestService        = $injector.get('requestService');
        $scope.status = false;
        const CLIENT_AUTO_RECONNECT = {
            0x0200: true,
            0x0202: true,
            0x0203: true,
            0x0207: true,
            0x0208: true,
            0x0301: true,
            0x0308: true
        };
        const TUNNEL_AUTO_RECONNECT = {
            0x0200: true,
            0x0202: true,
            0x0203: true,
            0x0207: true,
            0x0208: true,
            0x0308: true
        };
        /* ================================
         * HOME ACTION (FORCED REDIRECT)
         * ================================ */
        const NAVIGATE_HOME_ACTION = {
            name      : "CLIENT.ACTION_NAVIGATE_HOME",
            className : "home button",
            callback  : function () {
                window.location.href = "https://192.168.0.54:9000/pam";
            }
        };
        /* ================================
         * RECONNECT ACTION (UNCHANGED)
         * ================================ */
        const RECONNECT_ACTION = {
            name      : "CLIENT.ACTION_RECONNECT",
            className : "reconnect button",
            callback  : function () {
                $scope.client = guacClientManager.replaceManagedClient($scope.client.id);
                $scope.status = false;
            }
        };
        const RECONNECT_COUNTDOWN = {
            text: "CLIENT.TEXT_RECONNECT_COUNTDOWN",
            callback: RECONNECT_ACTION.callback,
            remaining: 15
        };
        const notifyConnectionClosed = function (status) {
            authenticationService.updateCurrentToken({})
            ['catch'](requestService.IGNORE)
            ['finally'](function () {
                $scope.status = status;
            });
        };
        const notifyConnectionState = function (connectionState) {
            $scope.status = false;
            if (!connectionState) return;
            /* 🔥 LOGOUT REMOVED HERE */
            const actions = [ NAVIGATE_HOME_ACTION, RECONNECT_ACTION ];
            const status = $scope.client.clientState.statusCode;
            if (connectionState === ManagedClientState.ConnectionState.CONNECTING ||
                connectionState === ManagedClientState.ConnectionState.WAITING) {
                $scope.status = {
                    className : "connecting",
                    title: "CLIENT.DIALOG_HEADER_CONNECTING",
                    text: { key : "CLIENT.TEXT_CLIENT_STATUS_" + connectionState.toUpperCase() }
                };
            }
            else if (connectionState === ManagedClientState.ConnectionState.CLIENT_ERROR) {
                const errorPrefix = "CLIENT.ERROR_CLIENT_";
                const errorId = errorPrefix + status.toString(16).toUpperCase();
                const defaultErrorId = errorPrefix + "DEFAULT";
                const countdown = (status in CLIENT_AUTO_RECONNECT) ? RECONNECT_COUNTDOWN : null;
                guacTranslate(errorId, defaultErrorId).then(result =>
                    notifyConnectionClosed({
                        className : "error",
                        title     : "CLIENT.DIALOG_HEADER_CONNECTION_ERROR",
                        text      : { key : result.id },
                        countdown : countdown,
                        actions   : actions
                    })
                );
            }
            else if (connectionState === ManagedClientState.ConnectionState.TUNNEL_ERROR) {
                const errorPrefix = "CLIENT.ERROR_TUNNEL_";
                const errorId = errorPrefix + status.toString(16).toUpperCase();
                const defaultErrorId = errorPrefix + "DEFAULT";
                const countdown = (status in TUNNEL_AUTO_RECONNECT) ? RECONNECT_COUNTDOWN : null;
                guacTranslate(errorId, defaultErrorId).then(result =>
                    notifyConnectionClosed({
                        className : "error",
                        title     : "CLIENT.DIALOG_HEADER_CONNECTION_ERROR",
                        text      : { key : result.id },
                        countdown : countdown,
                        actions   : actions
                    })
                );
            }
            else if (connectionState === ManagedClientState.ConnectionState.DISCONNECTED) {
                notifyConnectionClosed({
                    title   : "CLIENT.DIALOG_HEADER_DISCONNECTED",
                    text    : { key : "CLIENT.TEXT_CLIENT_STATUS_DISCONNECTED" },
                    actions : actions
                });
            }
            else {
                $scope.status = false;
            }
        };
        $scope.$watchGroup([
            'client.clientState.connectionState',
            'client.requiredParameters',
            'client.protocol',
            'client.forms'
        ], function (values) {
            const connectionState = values[0];
            const requiredParameters = values[1];
            if (requiredParameters &&
                (connectionState === ManagedClientState.ConnectionState.WAITING ||
                 connectionState === ManagedClientState.ConnectionState.CONNECTED)) {
                $scope.status = {
                    className : "parameters-required",
                    formNamespace : Protocol.getNamespace($scope.client.protocol),
                    forms : $scope.client.forms,
                    formModel : requiredParameters
                };
            }
            else {
                notifyConnectionState(connectionState);
            }
        });
    }];
    return directive;
}]);
