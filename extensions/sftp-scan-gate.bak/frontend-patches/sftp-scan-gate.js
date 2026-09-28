/**
 * SFTP malware scan gate - runtime patches for bundled tbPAM.war
 */
var sftpScanExtractUserMessage = function(error) {
    if (error.message)
        return error.message;
    if (error.translatableMessage
            && error.translatableMessage.key === 'APP.TEXT_UNTRANSLATED'
            && error.translatableMessage.variables
            && error.translatableMessage.variables.MESSAGE) {
        return error.translatableMessage.variables.MESSAGE;
    }
    return null;
};

var sftpScanHoldState = { active: {}, panelOpen: false };
var sftpScanCancelledByUserMessage = 'Cancelled by user';

var sftpScanHoldStatusText = function(scanStatus) {
    if (scanStatus === 'pending_review')
        return 'Waiting for administrator approval';
    if (scanStatus === 'sandbox_running' || scanStatus === 'sandbox_queued')
        return 'Sandbox analysis in progress…';
    return 'File scan in progress…';
};

var sftpScanIsHoldPanelEntry = function(entry) {
    if (!entry || !entry.phase)
        return true;
    return entry.phase !== 'READY' && entry.phase !== 'REJECTED';
};

/** Uses internal panelOpen so XHR/fetch polls cannot skip auto-open via stale $root counts. */
var sftpScanResolveHoldPanelOpen = function(panelOpen, entryCount) {
    if (!entryCount || entryCount < 1)
        return false;
    return !!panelOpen;
};

var sftpScanUpdateHoldPanel = function($rootScope) {
    var entries = Object.keys(sftpScanHoldState.active).map(function(key) {
        return sftpScanHoldState.active[key];
    }).filter(sftpScanIsHoldPanelEntry);
    if (!entries.length)
        sftpScanHoldState.panelOpen = false;
    var open = sftpScanResolveHoldPanelOpen(sftpScanHoldState.panelOpen, entries.length);
    $rootScope.$broadcast('sftpScanHoldUpdate', {
        count: entries.length,
        entries: entries,
        open: open
    });
    if (!$rootScope.$$phase)
        $rootScope.$applyAsync(function() {});
};

var sftpScanRegisterHold = function($rootScope, entry) {
    if (!entry || !entry.eventId)
        return;
    var existing = sftpScanHoldState.active[entry.eventId];
    var isNewEvent = !existing;
    if (existing) {
        entry.tunnel = entry.tunnel || existing.tunnel;
        entry.cancelUrl = entry.cancelUrl || existing.cancelUrl;
        entry.pollCancel = entry.pollCancel || existing.pollCancel;
        entry.rejectTransfer = entry.rejectTransfer || existing.rejectTransfer;
        if (!entry.phase)
            entry.phase = existing.phase;
    }
    entry.statusLabel = sftpScanHoldStatusText(entry.scanStatus);
    if (!entry.userMessage)
        entry.userMessage = entry.statusLabel;
    if (isNewEvent)
        sftpScanHoldState.panelOpen = true;
    sftpScanHoldState.active[entry.eventId] = entry;
    sftpScanUpdateHoldPanel($rootScope);
};

var sftpScanClearHold = function($rootScope, eventId) {
    if (eventId)
        delete sftpScanHoldState.active[eventId];
    sftpScanUpdateHoldPanel($rootScope);
};

var sftpScanAttachHoldTransferControls = function(eventId, pollPromise, rejectFn) {
    var slot = sftpScanHoldState.active[eventId];
    if (!slot)
        return;
    slot.pollCancel = function() {
        if (pollPromise && pollPromise.cancel)
            pollPromise.cancel();
    };
    slot.rejectTransfer = rejectFn;
};

var sftpScanCancelHold = function($rootScope, eventId) {
    var entry = sftpScanHoldState.active[eventId];
    if (!entry)
        return;
    if (entry.cancelUrl) {
        fetch(entry.cancelUrl, { method: 'DELETE', credentials: 'same-origin' })
            .catch(function() { /* server still owns PAM PATCH */ });
    }
    if (entry.pollCancel)
        entry.pollCancel();
    if (entry.rejectTransfer)
        entry.rejectTransfer(sftpScanCancelledByUserMessage);
    else
        sftpScanClearHold($rootScope, eventId);
};

var sftpScanApplyTransferError = function(transferState, error, ErrorType) {
    if (error.translatableMessage)
        transferState.translatableMessage = error.translatableMessage;

    var userMessage = sftpScanExtractUserMessage(error);
    if (userMessage)
        transferState.userMessage = userMessage;

    var statusCode = error.statusCode || Guacamole.Status.Code.CLIENT_FORBIDDEN;
    if (error.type === ErrorType.STREAM_ERROR || error.statusCode)
        transferState.statusCode = statusCode;
    else
        transferState.statusCode = Guacamole.Status.Code.INTERNAL_ERROR;
};

angular.module('rest')
.config(['$provide', function sftpScanGateRestConfig($provide) {

    $provide.decorator('tunnelService', ['$delegate', '$injector', function($delegate, $injector) {
        var Error = $injector.get('Error');
        var $window = $injector.get('$window');
        var authenticationService = $injector.get('authenticationService');
        var CHUNK_SIZE = 1024 * 1024 * 4;
        var SCAN_WARNING_HEADER = 'X-Scan-User-Message';

        var sanitizeFilename = function(filename) {
            return filename.replace(/[\\\/]+/g, '_');
        };

        var getStreamOrigin = function() {
            if (!$window.location.origin)
                return $window.location.protocol + '//' + $window.location.hostname
                    + ($window.location.port ? (':' + $window.location.port) : '');
            return $window.location.origin;
        };

        var buildStreamUrl = function(tunnel, streamIndex, filename) {
            return getStreamOrigin()
                + $window.location.pathname
                + 'api/session/tunnels/' + encodeURIComponent(tunnel)
                + '/streams/' + encodeURIComponent(streamIndex)
                + '/' + encodeURIComponent(sanitizeFilename(filename))
                + '?token=' + encodeURIComponent(authenticationService.getCurrentToken());
        };

        var buildScanHoldUrl = function(tunnel, suffix) {
            return getStreamOrigin()
                + $window.location.pathname
                + 'api/session/tunnels/' + encodeURIComponent(tunnel)
                + '/scan-hold' + (suffix || '')
                + '?token=' + encodeURIComponent(authenticationService.getCurrentToken());
        };

        var parseHoldPayload = function(body) {
            var payload = angular.fromJson(body || '{}');
            return payload.data || payload;
        };

        var pollScanHold = function(tunnel, eventId, onUpdate) {
            var deferred = $injector.get('$q').defer();
            var cancelled = false;
            deferred.promise.cancel = function() { cancelled = true; };

            var poll = function() {
                if (cancelled) {
                    deferred.reject({ cancelled: true });
                    return;
                }
                fetch(buildScanHoldUrl(tunnel, '/' + encodeURIComponent(eventId) + '/status'), {
                    credentials: 'same-origin'
                }).then(function(response) {
                    return response.text().then(function(body) {
                        if (!response.ok)
                            throw new Error(body || 'Scan status poll failed');
                        var data = parseHoldPayload(body);
                        if (onUpdate)
                            onUpdate(data);
                        if (data.phase === 'READY') {
                            deferred.resolve(data);
                            return;
                        }
                        if (data.phase === 'REJECTED') {
                            deferred.reject({
                                userMessage: data.userMessage || 'File transfer blocked.'
                            });
                            return;
                        }
                        var delay = (data.pollAfterSeconds || 5) * 1000;
                        $window.setTimeout(poll, delay);
                    });
                }).catch(function(error) {
                    deferred.reject(error);
                });
            };

            poll();
            return deferred.promise;
        };

        var resumeHeldUpload = function(tunnel, eventId) {
            return fetch(buildScanHoldUrl(tunnel, '/' + encodeURIComponent(eventId) + '/resume'), {
                method: 'POST',
                credentials: 'same-origin'
            }).then(function(response) {
                if (!response.ok)
                    return response.text().then(function(body) {
                        throw parseApiError({ status: response.status, responseText: body });
                    });
            });
        };

        var fetchHeldDownload = function(tunnel, eventId) {
            return fetch(buildScanHoldUrl(tunnel, '/' + encodeURIComponent(eventId) + '/content'), {
                credentials: 'same-origin'
            }).then(function(response) {
                if (!response.ok)
                    return response.text().then(function(body) {
                        throw new Error(body || 'Held download failed');
                    });
                return response.blob();
            });
        };

        var cleanupScanHoldSession = function(tunnel) {
            if (!tunnel)
                return;
            fetch(buildScanHoldUrl(tunnel, ''), {
                method: 'DELETE',
                credentials: 'same-origin'
            }).catch(function() { /* best effort */ });
        };

        $delegate.cleanupScanHoldSession = cleanupScanHoldSession;
        $delegate.pollScanHold = pollScanHold;

        var showScanWarning = function(message) {
            if (!message)
                return;
            try {
                var guacNotification = $injector.get('guacNotification');
                guacNotification.showStatus({
                    className: 'warning',
                    title: 'CLIENT.DIALOG_HEADER_FILE_SCAN_WARNING',
                    text: {
                        key: 'APP.TEXT_UNTRANSLATED',
                        variables: { MESSAGE: message }
                    },
                    actions: [{
                        name: 'APP.ACTION_ACKNOWLEDGE',
                        callback: function() { guacNotification.showStatus(false); }
                    }]
                });
            }
            catch (e) {
                console.warn('Scan warning:', message);
            }
        };

        var applyAuditHeaders = function(xhr, direction, transferMeta) {
            transferMeta = transferMeta || {};
            xhr.setRequestHeader('X-Transfer-Direction', direction);
            xhr.setRequestHeader('X-File-Content-Type',
                transferMeta.contentType || 'application/octet-stream');
            if (transferMeta.sourcePath)
                xhr.setRequestHeader('X-Source-Path', transferMeta.sourcePath);
            if (transferMeta.destinationPath)
                xhr.setRequestHeader('X-Destination-Path', transferMeta.destinationPath);
        };

        var parseApiError = function(xhr) {
            if (xhr.responseText) {
                try {
                    return new Error(angular.fromJson(xhr.responseText));
                }
                catch (e) {
                    // fall through
                }
            }
            return new Error({
                type: Error.Type.STREAM_ERROR,
                statusCode: xhr.status >= 500
                    ? Guacamole.Status.Code.SERVER_ERROR
                    : (xhr.status >= 400 ? Guacamole.Status.Code.CLIENT_FORBIDDEN
                        : Guacamole.Status.Code.INTERNAL_ERROR),
                message: xhr.status >= 500
                    ? 'Upload failed due to a server error (HTTP ' + xhr.status + '). Contact your administrator.'
                    : 'HTTP ' + xhr.status
            });
        };

        $delegate.uploadToStream = function uploadToStream(tunnel, stream, file,
                progressCallback, transferMeta) {

            var deferred = $injector.get('$q').defer();
            var url = buildStreamUrl(tunnel, stream.index, file.name);
            var lastWarning = null;
            transferMeta = transferMeta || {
                contentType: file.type,
                sourcePath: file.name,
                destinationPath: file.name
            };

            var uploadChunk = function(chunk, offset) {
                var xhr = new XMLHttpRequest();
                xhr.open('POST', url, true);
                xhr.setRequestHeader('X-Chunk-Offset', offset.toString());
                xhr.setRequestHeader('X-File-Size', file.size.toString());
                applyAuditHeaders(xhr, 'upload', transferMeta);

                if (progressCallback && xhr.upload) {
                    xhr.upload.addEventListener('progress', function(e) {
                        progressCallback(e.loaded + offset);
                    });
                }

                xhr.onreadystatechange = function() {
                    if (xhr.readyState !== 4)
                        return;

                    if (xhr.status === 202) {
                        var holdData = parseHoldPayload(xhr.responseText);
                        var $rootScope = $injector.get('$rootScope');
                        sftpScanRegisterHold($rootScope, {
                            eventId: holdData.eventId,
                            tunnel: tunnel,
                            filename: holdData.filename || file.name,
                            scanStatus: holdData.scanStatus,
                            userMessage: holdData.userMessage,
                            phase: 'POLLING',
                            cancelUrl: buildScanHoldUrl(tunnel,
                                '/' + encodeURIComponent(holdData.eventId))
                        });
                        var uploadPollPromise = pollScanHold(tunnel, holdData.eventId,
                            function updateHold(data) {
                            sftpScanRegisterHold($rootScope, {
                                eventId: data.eventId,
                                filename: data.filename || file.name,
                                scanStatus: data.scanStatus,
                                userMessage: data.userMessage,
                                phase: data.phase || 'POLLING'
                            });
                        });
                        sftpScanAttachHoldTransferControls(holdData.eventId,
                            uploadPollPromise, function(message) {
                            sftpScanClearHold($rootScope, holdData.eventId);
                            deferred.reject(new Error({ message: message }));
                        });
                        uploadPollPromise.then(function() {
                            sftpScanClearHold($rootScope, holdData.eventId);
                            showScanWarning('File approved. Transfer continuing.');
                            return resumeHeldUpload(tunnel, holdData.eventId);
                        }).then(function() {
                            deferred.resolve();
                        }, function(error) {
                            sftpScanClearHold($rootScope, holdData.eventId);
                            if (error && error.cancelled)
                                deferred.reject(new Error({
                                    message: sftpScanCancelledByUserMessage
                                }));
                            else if (error && error.userMessage)
                                deferred.reject(new Error({ message: error.userMessage }));
                            else
                                deferred.reject(parseApiError(xhr));
                        });
                        return;
                    }

                    if (xhr.status >= 200 && xhr.status < 300) {
                        var warning = xhr.getResponseHeader(SCAN_WARNING_HEADER);
                        if (warning)
                            lastWarning = warning;

                        offset += CHUNK_SIZE;
                        if (offset < file.size)
                            uploadHandler(offset);
                        else {
                            if (lastWarning)
                                showScanWarning(lastWarning);
                            deferred.resolve();
                        }
                    }
                    else
                        deferred.reject(parseApiError(xhr));
                };

                xhr.send(chunk);
            };

            var uploadHandler = function(offset) {
                uploadChunk(file.slice(offset, Math.min(offset + CHUNK_SIZE, file.size)), offset);
            };

            uploadHandler(0);
            return deferred.promise;
        };

        $delegate.downloadStream = function downloadStream(tunnel, stream, mimetype, filename) {
            var iframe = $window.document.createElement('iframe');
            iframe.style.position = 'fixed';
            iframe.style.border = 'none';
            iframe.style.width = '1px';
            iframe.style.height = '1px';
            iframe.style.left = '-1px';
            iframe.style.top = '-1px';
            $window.document.body.appendChild(iframe);

            iframe.onload = function() {
                if (iframe.parentElement)
                    iframe.parentElement.removeChild(iframe);
            };

            stream.onblob = function acknowledgeData() {
                stream.sendAck('OK', Guacamole.Status.Code.SUCCESS);
            };

            stream.onend = function() {
                $window.setTimeout(function() {
                    if (iframe.parentElement)
                        iframe.parentElement.removeChild(iframe);
                }, 5000);
            };

            iframe.src = buildStreamUrl(tunnel, stream.index, filename);
        };

        $delegate.downloadStreamTracked = function downloadStreamTracked(tunnel, stream, mimetype,
            filename, callbacks, transferMeta) {

            stream.onblob = function acknowledgeData() {
                stream.sendAck('OK', Guacamole.Status.Code.SUCCESS);
            };

            transferMeta = transferMeta || {
                contentType: mimetype,
                sourcePath: filename,
                destinationPath: filename
            };

            fetch(buildStreamUrl(tunnel, stream.index, filename), {
                method: 'GET',
                credentials: 'same-origin',
                headers: {
                    'X-Transfer-Direction': 'download',
                    'X-File-Content-Type': transferMeta.contentType || mimetype || 'application/octet-stream',
                    'X-Source-Path': transferMeta.sourcePath || filename,
                    'X-Destination-Path': transferMeta.destinationPath || filename
                }
            }).then(function(response) {
                if (response.status === 202)
                    return response.text().then(function(body) {
                        var holdData = parseHoldPayload(body);
                        var $rootScope = $injector.get('$rootScope');
                        sftpScanRegisterHold($rootScope, {
                            eventId: holdData.eventId,
                            tunnel: tunnel,
                            filename: filename,
                            scanStatus: holdData.scanStatus,
                            userMessage: holdData.userMessage,
                            phase: 'POLLING',
                            cancelUrl: buildScanHoldUrl(tunnel,
                                '/' + encodeURIComponent(holdData.eventId))
                        });
                        var downloadPollPromise = pollScanHold(tunnel, holdData.eventId,
                            function updateHold(data) {
                            sftpScanRegisterHold($rootScope, {
                                eventId: data.eventId,
                                filename: filename,
                                scanStatus: data.scanStatus,
                                userMessage: data.userMessage,
                                phase: data.phase || 'POLLING'
                            });
                        });
                        sftpScanAttachHoldTransferControls(holdData.eventId,
                            downloadPollPromise, function(message) {
                            sftpScanClearHold($rootScope, holdData.eventId);
                            if (callbacks.onError)
                                callbacks.onError({ message: message });
                        });
                        return downloadPollPromise.then(function() {
                            sftpScanClearHold($rootScope, holdData.eventId);
                            showScanWarning('File approved. Transfer continuing.');
                            return fetchHeldDownload(tunnel, holdData.eventId);
                        }).then(function(blob) {
                            if (callbacks.onProgress)
                                callbacks.onProgress(blob.size, blob.size);
                            if (callbacks.onSuccess)
                                callbacks.onSuccess(blob);
                        }).catch(function(error) {
                            sftpScanClearHold($rootScope, holdData.eventId);
                            if (error && error.cancelled) {
                                if (callbacks.onError)
                                    callbacks.onError({
                                        message: sftpScanCancelledByUserMessage
                                    });
                                return;
                            }
                            if (error && error.userMessage && callbacks.onError)
                                callbacks.onError({ message: error.userMessage });
                            else if (callbacks.onError)
                                callbacks.onError(error);
                        });
                    });

                if (!response.ok)
                    return response.text().then(function(body) {
                        throw new Error(body ? angular.fromJson(body) : {});
                    });

                var warning = response.headers.get(SCAN_WARNING_HEADER);
                if (warning)
                    showScanWarning(warning);

                return response.blob().then(function(blob) {
                    if (callbacks.onProgress)
                        callbacks.onProgress(blob.size, blob.size);
                    if (callbacks.onSuccess)
                        callbacks.onSuccess(blob);
                });
            }).catch(function(error) {
                var payload = error;
                if (error && error.message && typeof error.message === 'object')
                    payload = error.message;
                if (callbacks.onError)
                    callbacks.onError(payload);
            });
        };

        return $delegate;
    }]);
}]);

angular.module('client')
.config(['$provide', function sftpScanGateClientConfig($provide) {

    $provide.decorator('ManagedFileUpload', ['$delegate', '$injector', function($delegate, $injector) {
        var $rootScope = $injector.get('$rootScope');
        var Error = $injector.get('Error');
        var ManagedFileTransferState = $injector.get('ManagedFileTransferState');
        var requestService = $injector.get('requestService');
        var tunnelService = $injector.get('tunnelService');

        $delegate.getInstance = function(managedClient, file, object, streamName) {
            var managedFileUpload = new $delegate();
            var client = managedClient.client;
            var tunnel = managedClient.tunnel;
            var stream;

            if (!object)
                stream = client.createFileStream(file.type, file.name);
            else
                stream = object.createOutputStream(file.type, streamName);

            $rootScope.$evalAsync(function uploadStreamOpen() {
                managedFileUpload.filename = file.name;
                managedFileUpload.mimetype = file.type;
                managedFileUpload.progress = 0;
                managedFileUpload.length = file.size;
                ManagedFileTransferState.setStreamState(managedFileUpload.transferState,
                    ManagedFileTransferState.StreamState.OPEN);
            });

            stream.onack = function beginUpload(status) {
                if (status.isError()) {
                    $rootScope.$apply(function uploadStreamError() {
                        ManagedFileTransferState.setStreamState(managedFileUpload.transferState,
                            ManagedFileTransferState.StreamState.ERROR, status.code);
                    });
                    return;
                }

                var transferMeta = {
                    contentType: file.type || 'application/octet-stream',
                    sourcePath: file.name,
                    destinationPath: streamName || file.name
                };

                tunnelService.uploadToStream(tunnel.uuid, stream, file, function uploadContinuing(length) {
                    $rootScope.$apply(function uploadStreamProgress() {
                        managedFileUpload.progress = length;
                    });
                }, transferMeta).then(function uploadSuccessful() {
                    managedFileUpload.progress = file.size;
                    stream.sendEnd();
                    ManagedFileTransferState.setStreamState(managedFileUpload.transferState,
                        ManagedFileTransferState.StreamState.CLOSED);
                    $rootScope.$broadcast('guacUploadComplete', file.name);
                }, requestService.createErrorCallback(function uploadFailed(error) {
                    sftpScanApplyTransferError(managedFileUpload.transferState, error, Error.Type);
                    ManagedFileTransferState.setStreamState(managedFileUpload.transferState,
                        ManagedFileTransferState.StreamState.ERROR,
                        managedFileUpload.transferState.statusCode);
                    stream.sendEnd();
                }));

                stream.onack = null;
            };

            return managedFileUpload;
        };

        return $delegate;
    }]);

    $provide.decorator('guacFileTransferDirective', ['$delegate', '$injector', function($delegate, $injector) {
        var directive = $delegate[0];

        // Replace controller entirely — original statusCode $watch overwrites PAM userMessage
        directive.controller = ['$scope', '$injector', function($scope, $injector) {
            var $translate = $injector.get('$translate');
            var guacTranslate = $injector.get('guacTranslate');
            var ManagedFileTransferState = $injector.get('ManagedFileTransferState');

            $scope.getProgressUnit = function getProgressUnit() {
                var bytes = $scope.transfer.progress;
                if (bytes > 1000000000) return 'gb';
                if (bytes > 1000000) return 'mb';
                if (bytes > 1000) return 'kb';
                return 'b';
            };

            $scope.getProgressValue = function getProgressValue() {
                var bytes = $scope.transfer.progress;
                if (!bytes) return bytes;
                switch ($scope.getProgressUnit()) {
                    case 'gb': return (bytes / 1000000000).toFixed(1);
                    case 'mb': return (bytes / 1000000).toFixed(1);
                    case 'kb': return (bytes / 1000).toFixed(1);
                    default: return bytes;
                }
            };

            $scope.getPercentDone = function getPercentDone() {
                return $scope.transfer.progress / $scope.transfer.length * 100;
            };

            $scope.isInProgress = function isInProgress() {
                if (!$scope.transfer) return false;
                switch ($scope.transfer.transferState.streamState) {
                    case ManagedFileTransferState.StreamState.IDLE:
                    case ManagedFileTransferState.StreamState.OPEN:
                        return true;
                    default:
                        return false;
                }
            };

            $scope.isSavable = function isSavable() {
                return !!$scope.transfer.blob;
            };

            $scope.save = function save() {
                if ($scope.transfer.blob)
                    saveAs($scope.transfer.blob, $scope.transfer.filename);
            };

            $scope.hasError = function hasError() {
                return $scope.transfer.transferState.streamState
                    === ManagedFileTransferState.StreamState.ERROR;
            };

            $scope.translatedErrorMessage = '';

            var updateErrorMessage = function() {
                var transferState = $scope.transfer && $scope.transfer.transferState;
                if (!transferState || transferState.streamState !== ManagedFileTransferState.StreamState.ERROR)
                    return;

                if (transferState.userMessage) {
                    $scope.translatedErrorMessage = transferState.userMessage;
                    return;
                }

                if (transferState.translatableMessage) {
                    if (transferState.translatableMessage.key === 'APP.TEXT_UNTRANSLATED'
                            && transferState.translatableMessage.variables
                            && transferState.translatableMessage.variables.MESSAGE) {
                        $scope.translatedErrorMessage = transferState.translatableMessage.variables.MESSAGE;
                        return;
                    }
                    $translate(transferState.translatableMessage.key,
                        transferState.translatableMessage.variables || {})
                        .then(function(message) {
                            $scope.translatedErrorMessage = message;
                        });
                    return;
                }

                var prefix = $scope.transfer.isDownload ? 'CLIENT.ERROR_DOWNLOAD_' : 'CLIENT.ERROR_UPLOAD_';
                var errorName = prefix + transferState.statusCode.toString(16).toUpperCase();
                guacTranslate(errorName, prefix + 'DEFAULT').then(function(result) {
                    $scope.translatedErrorMessage = result.message;
                });
            };

            $scope.$watch('transfer.transferState', updateErrorMessage, true);
        }];

        return $delegate;
    }]);
}])
.run(['$injector', function sftpScanGateRun($injector) {
    var $rootScope = $injector.get('$rootScope');
    var $window = $injector.get('$window');
    var ManagedFileTransferState = $injector.get('ManagedFileTransferState');
    var Error = $injector.get('Error');
    var tunnelService = $injector.get('tunnelService');
    var ManagedClientState = $injector.get('ManagedClientState');

    var ManagedFilesystem = $injector.get('ManagedFilesystem');
    // Use stock iframe download — registering ManagedFileDownload in run() is too
    // late for Angular and was throwing, so the Download button did nothing.
    ManagedFilesystem.downloadFile = function(managedFilesystem, path) {
        managedFilesystem.object.requestInputStream(path, function downloadStreamReceived(stream, mimetype) {
            var filename = path.match(/(.*[\\/])?(.*)/)[2];
            tunnelService.downloadStream(managedFilesystem.client.tunnel.uuid, stream, mimetype, filename);
        });
    };

    var ManagedClient = $injector.get('ManagedClient');
    var originalHasTransfers = ManagedClient.hasTransfers;
    ManagedClient.hasTransfers = function(client) {
        return originalHasTransfers(client)
            || !!(client && client.downloads && client.downloads.length);
    };

    $rootScope.sftpSelectedFile = null;

    $rootScope.downloadSftpSelectedFile = function() {
        var selected = $rootScope.sftpSelectedFile;
        if (!selected || !selected.filesystem || !selected.streamName)
            return;
        ManagedFilesystem.downloadFile(selected.filesystem, selected.streamName);
    };

    $rootScope.sftpScanHoldPanel = { count: 0, entries: [], open: false };
    $rootScope.$on('sftpScanHoldUpdate', function(event, panel) {
        $rootScope.sftpScanHoldPanel = {
            count: panel.count || 0,
            entries: panel.entries || [],
            open: panel.count > 0 ? !!panel.open : false
        };
    });
    $rootScope.toggleSftpScanHoldPanel = function() {
        if (!$rootScope.sftpScanHoldPanel || !$rootScope.sftpScanHoldPanel.count)
            return;
        sftpScanHoldState.panelOpen = !$rootScope.sftpScanHoldPanel.open;
        $rootScope.sftpScanHoldPanel.open = sftpScanHoldState.panelOpen;
    };
    $rootScope.closeSftpScanHoldPanel = function() {
        sftpScanHoldState.panelOpen = false;
        if ($rootScope.sftpScanHoldPanel)
            $rootScope.sftpScanHoldPanel.open = false;
    };
    $rootScope.cancelSftpScanHoldEntry = function(entry) {
        if (!entry || !entry.eventId)
            return;
        sftpScanCancelHold($rootScope, entry.eventId);
    };

    var cleanupTrackedTunnels = function() {
        var seen = {};
        Object.keys(sftpScanHoldState.active).forEach(function(eventId) {
            var entry = sftpScanHoldState.active[eventId];
            if (entry && entry.tunnel && !seen[entry.tunnel]) {
                seen[entry.tunnel] = true;
                tunnelService.cleanupScanHoldSession(entry.tunnel);
            }
        });
        if ($rootScope.focusedClient && $rootScope.focusedClient.tunnel) {
            var uuid = $rootScope.focusedClient.tunnel.uuid;
            if (!seen[uuid])
                tunnelService.cleanupScanHoldSession(uuid);
        }
    };

    $window.addEventListener('beforeunload', cleanupTrackedTunnels);

    $rootScope.$watch(function() {
        var client = $rootScope.focusedClient;
        if (!client || !client.clientState)
            return null;
        return client.clientState.connectionState + ':' + (client.tunnel ? client.tunnel.uuid : '');
    }, function(state, previous) {
        if (!previous || !state || state === previous)
            return;
        var parts = previous.split(':');
        if (parts.length < 2)
            return;
        if (parseInt(parts[0], 10) === ManagedClientState.ConnectionState.DISCONNECTED
                || parseInt(parts[0], 10) === ManagedClientState.ConnectionState.TUNNEL_ERROR
                || parseInt(parts[0], 10) === ManagedClientState.ConnectionState.CLIENT_ERROR) {
            tunnelService.cleanupScanHoldSession(parts[1]);
        }
    });

    $rootScope.$watch('applicationState', function(state) {
        if (state === 'sessionEnded')
            cleanupTrackedTunnels();
    });
}]);
