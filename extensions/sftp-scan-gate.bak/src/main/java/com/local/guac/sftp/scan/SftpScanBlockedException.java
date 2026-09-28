package com.local.guac.sftp.scan;

import org.apache.guacamole.language.Translatable;
import org.apache.guacamole.language.TranslatableMessage;
import org.apache.guacamole.protocol.GuacamoleStatus;
import org.apache.guacamole.tunnel.GuacamoleStreamException;

public class SftpScanBlockedException extends GuacamoleStreamException implements Translatable {

    private final TranslatableMessage translatableMessage;

    public SftpScanBlockedException(GuacamoleStatus status, TranslatableMessage translatableMessage,
            String logMessage) {
        super(status, logMessage);
        this.translatableMessage = translatableMessage;
    }

    @Override
    public TranslatableMessage getTranslatableMessage() {
        return translatableMessage;
    }
}
