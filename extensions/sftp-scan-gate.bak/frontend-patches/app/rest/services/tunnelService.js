/*
 * Patched tunnelService: chunk headers for upload scan buffering,
 * fetch-based tracked downloads for scan error display.
 */
angular.module('rest').factory('tunnelService', ['$injector',
        function tunnelService($injector) {

    var Error = $injector.get('Error');
    var $q                    = $injector.get('$q');
    var $window               = $injector.get('$window');
    var authenticationService = $injector.get('authenticationService');
    var requestService        = $injector.get('requestService');

    var service = {};
    var document = $window.document;
    var DOWNLOAD_CLEANUP_WAIT = 5000;
    const CHUNK_SIZE = 1024 * 1024 * 4;

    service.getTunnels = function getTunnels() {
        return authenticationService.request({
            method  : 'GET',
            url     : 'api/session/tunnels'
        });
    };

    service.getProtocol = function getProtocol(tunnel) {
        return authenticationService.request({
            method  : 'GET',
            url     : 'api/session/tunnels/' + encodeURIComponent(tunnel) + '/protocol'
        });
    };

    service.getSharingProfiles = function getSharingProfiles(tunnel) {
        return authenticationService.request({
            method  : 'GET',
            url     : 'api/session/tunnels/' + encodeURIComponent(tunnel)
                        + '/activeConnection/connection/sharingProfiles'
        });
    };

    service.getSharingCredentials = function getSharingCredentials(tunnel, sharingProfile) {
        return authenticationService.request({
            method  : 'GET',
            url     : 'api/session/tunnels/' + encodeURIComponent(tunnel)
                        + '/activeConnection/sharingCredentials/'
                        + encodeURIComponent(sharingProfile)
        });
    };

    var sanitizeFilename = function sanitizeFilename(filename) {
        return filename.replace(/[\\\/]+/g, '_');
    };

    var getStreamOrigin = function getStreamOrigin() {
        if (!$window.location.origin)
            return $window.location.protocol + '//' + $window.location.hostname
                + ($window.location.port ? (':' + $window.location.port) : '');
        return $window.location.origin;
    };

    var buildStreamUrl = function buildStreamUrl(tunnel, streamIndex, filename) {
        return getStreamOrigin()
                + $window.location.pathname
                + 'api/session/tunnels/' + encodeURIComponent(tunnel)
                + '/streams/' + encodeURIComponent(streamIndex)
                + '/' + encodeURIComponent(sanitizeFilename(filename))
                + '?token=' + encodeURIComponent(authenticationService.getCurrentToken());
    };

    service.downloadStream = function downloadStream(tunnel, stream, mimetype, filename) {
        var iframe = document.createElement('iframe');
        iframe.style.position = 'fixed';
        iframe.style.border = 'none';
        iframe.style.width = '1px';
        iframe.style.height = '1px';
        iframe.style.left = '-1px';
        iframe.style.top = '-1px';
        document.body.appendChild(iframe);

        iframe.onload = function downloadComplete() {
            document.body.removeChild(iframe);
        };

        stream.onblob = function acknowledgeData() {
            stream.sendAck('OK', Guacamole.Status.Code.SUCCESS);
        };

        stream.onend = function downloadComplete() {
            $window.setTimeout(function cleanupIframe() {
                if (iframe.parentElement)
                    document.body.removeChild(iframe);
            }, DOWNLOAD_CLEANUP_WAIT);
        };

        iframe.src = buildStreamUrl(tunnel, stream.index, filename);
    };

    service.downloadStreamTracked = function downloadStreamTracked(tunnel, stream, mimetype,
        filename, callbacks) {

        stream.onblob = function acknowledgeData() {
            stream.sendAck('OK', Guacamole.Status.Code.SUCCESS);
        };

        fetch(buildStreamUrl(tunnel, stream.index, filename), {
            method: 'GET',
            credentials: 'same-origin'
        }).then(function handleResponse(response) {
            if (!response.ok)
                return response.text().then(function parseError(body) {
                    var error = body ? angular.fromJson(body) : {};
                    throw new Error(error);
                });

            var total = parseInt(response.headers.get('Content-Length'), 10);
            if (callbacks.onProgress)
                callbacks.onProgress(0, total || 0);

            return response.blob().then(function deliverBlob(blob) {
                if (callbacks.onProgress)
                    callbacks.onProgress(blob.size, blob.size);
                if (callbacks.onSuccess)
                    callbacks.onSuccess(blob);
            });
        }).catch(function handleFailure(error) {
            var payload = error;
            if (error && error.message && typeof error.message === 'object')
                payload = error.message;
            if (callbacks.onError)
                callbacks.onError(payload);
        });
    };

    service.uploadToStream = function uploadToStream(tunnel, stream, file, progressCallback) {
        var deferred = $q.defer();
        var url = buildStreamUrl(tunnel, stream.index, file.name);

        const createChunk = (offset) => file.slice(offset, Math.min(offset + CHUNK_SIZE, file.size));

        const uploadChunk = (chunk, offset) => {
            var xhr = new XMLHttpRequest();
            xhr.open('POST', url, true);
            xhr.setRequestHeader('X-Chunk-Offset', offset.toString());
            xhr.setRequestHeader('X-File-Size', file.size.toString());

            if (progressCallback && xhr.upload) {
                xhr.upload.addEventListener('progress', function updateProgress(e) {
                    progressCallback(e.loaded + offset);
                });
            }

            xhr.onreadystatechange = function uploadStatusChanged() {
                if (xhr.readyState !== 4)
                    return;

                if (xhr.status >= 200 && xhr.status < 300) {
                    offset += CHUNK_SIZE;
                    if (offset < file.size)
                        uploadHandler(offset);
                    else
                        deferred.resolve();
                }
                else if (xhr.getResponseHeader('Content-Type') === 'application/json')
                    deferred.reject(new Error(angular.fromJson(xhr.responseText)));
                else if (xhr.status >= 400 && xhr.status < 500)
                    deferred.reject(new Error({
                        type: Error.Type.STREAM_ERROR,
                        statusCode: Guacamole.Status.Code.CLIENT_FORBIDDEN,
                        message: 'HTTP ' + xhr.status
                    }));
                else
                    deferred.reject(new Error({
                        type: Error.Type.STREAM_ERROR,
                        statusCode: Guacamole.Status.Code.INTERNAL_ERROR,
                        message: 'HTTP ' + xhr.status
                    }));
            };

            xhr.send(chunk);
        };

        const uploadHandler = (offset) => uploadChunk(createChunk(offset), offset);
        uploadHandler(0);
        return deferred.promise;
    };

    return service;
}]);
