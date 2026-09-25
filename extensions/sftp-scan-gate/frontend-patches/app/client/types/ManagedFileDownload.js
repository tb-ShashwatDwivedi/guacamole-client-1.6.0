/*
 * SFTP malware scan gate - tracked download with user-visible errors.
 */
angular.module('client').factory('ManagedFileDownload', ['$rootScope', '$injector',
    function defineManagedFileDownload($rootScope, $injector) {

    var Error                    = $injector.get('Error');
    var ManagedFileTransferState = $injector.get('ManagedFileTransferState');
    var tunnelService            = $injector.get('tunnelService');

    var ManagedFileDownload = function ManagedFileDownload(template) {
        template = template || {};
        this.transferState = template.transferState || new ManagedFileTransferState();
        this.mimetype = template.mimetype;
        this.filename = template.filename;
        this.progress = template.progress || 0;
        this.length = template.length || 0;
        this.blob = template.blob || null;
        this.isDownload = true;
    };

    ManagedFileDownload.getInstance = function getInstance(managedClient, stream, mimetype, filename) {
        var managedFileDownload = new ManagedFileDownload();
        var tunnel = managedClient.tunnel;

        $rootScope.$evalAsync(function downloadStarted() {
            managedFileDownload.filename = filename;
            managedFileDownload.mimetype = mimetype;
            ManagedFileTransferState.setStreamState(managedFileDownload.transferState,
                ManagedFileTransferState.StreamState.OPEN);
        });

        tunnelService.downloadStreamTracked(tunnel.uuid, stream, mimetype, filename, {
            onProgress: function onProgress(loaded, total) {
                $rootScope.$apply(function updateProgress() {
                    managedFileDownload.progress = loaded;
                    if (total)
                        managedFileDownload.length = total;
                });
            },
            onSuccess: function onSuccess(blob) {
                $rootScope.$apply(function downloadComplete() {
                    managedFileDownload.blob = blob;
                    managedFileDownload.progress = blob.size;
                    managedFileDownload.length = blob.size;
                    ManagedFileTransferState.setStreamState(managedFileDownload.transferState,
                        ManagedFileTransferState.StreamState.CLOSED);
                });
            },
            onError: function onError(error) {
                $rootScope.$apply(function downloadFailed() {
                    if (error.translatableMessage)
                        managedFileDownload.transferState.translatableMessage = error.translatableMessage;
                    if (error.type === Error.Type.STREAM_ERROR)
                        ManagedFileTransferState.setStreamState(managedFileDownload.transferState,
                            ManagedFileTransferState.StreamState.ERROR, error.statusCode);
                    else
                        ManagedFileTransferState.setStreamState(managedFileDownload.transferState,
                            ManagedFileTransferState.StreamState.ERROR,
                            Guacamole.Status.Code.INTERNAL_ERROR);
                });
            }
        });

        return managedFileDownload;
    };

    return ManagedFileDownload;
}]);
