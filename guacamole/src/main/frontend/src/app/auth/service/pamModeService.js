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
 * Detects whether the current Guacamole page was launched from Cybersio/tbPAM
 * (tokenized client, share-key live monitor, or recording player). Once detected,
 * the mode is sticky for the lifetime of the browser tab so later URL cleanup
 * cannot re-enable Guacamole home/login UI.
 */
angular.module('auth').factory('pamModeService', ['$injector',
        function pamModeService($injector) {

    const $location = $injector.get('$location');
    const $window   = $injector.get('$window');

    /**
     * sessionStorage key used to remember PAM mode across in-tab navigations.
     *
     * @type {!string}
     */
    const PAM_MODE_STORAGE_KEY = 'GUAC_PAM_MODE';

    /**
     * BroadcastChannel / storage event name used by Cybersio logout
     * defense-in-depth signaling.
     *
     * @type {!string}
     */
    const LOGOUT_CHANNEL = 'cybersio-pam-session';

    /**
     * localStorage key written by Cybersio on dashboard logout.
     *
     * @type {!string}
     */
    const LOGOUT_STORAGE_KEY = 'cybersio-pam-logout';

    /**
     * @returns {!boolean}
     *     Whether the current URL (or sticky session flag) indicates PAM mode.
     */
    const detectFromLocation = function detectFromLocation() {

        const search = $location.search() || {};
        const path = $location.path() || '';
        const href = $window.location.href || '';
        const hash = $window.location.hash || '';

        return !!(
            search.token ||
            search.key ||
            path.indexOf('/client/') === 0 ||
            path.indexOf('/recording/') !== -1 ||
            /[?&#]token=/.test(href) ||
            /[?&#]key=/.test(href) ||
            /#\/client\//.test(hash) ||
            /\/recording\//.test(hash)
        );

    };

    /**
     * Persists PAM mode so it remains active after query params are consumed.
     */
    const persistPamMode = function persistPamMode() {
        try {
            $window.sessionStorage.setItem(PAM_MODE_STORAGE_KEY, '1');
        }
        catch (e) {
            // Ignore storage failures (private mode, etc.)
        }
    };

    const service = {};

    /**
     * Channel / storage identifiers for Cybersio logout signaling.
     */
    service.LOGOUT_CHANNEL = LOGOUT_CHANNEL;
    service.LOGOUT_STORAGE_KEY = LOGOUT_STORAGE_KEY;

    /**
     * Returns whether this Guacamole surface is operating in PAM / kiosk mode.
     * Detection is sticky once true for the current tab.
     *
     * @returns {!boolean}
     */
    service.isPamMode = function isPamMode() {

        try {
            if ($window.sessionStorage.getItem(PAM_MODE_STORAGE_KEY) === '1')
                return true;
        }
        catch (e) {
            // Ignore storage failures
        }

        if (detectFromLocation()) {
            persistPamMode();
            return true;
        }

        return false;

    };

    /**
     * Explicitly marks the current tab as PAM mode.
     */
    service.enablePamMode = function enablePamMode() {
        persistPamMode();
    };

    return service;

}]);
