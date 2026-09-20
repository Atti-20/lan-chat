package com.lanchat.service.storage;

import java.io.InputStream;
import java.nio.file.Path;

/** Private object storage used by attachments, thumbnails and upload parts. */
public interface FileObjectStorage {

    String type();

    void put(String objectKey, Path source, String contentType);

    InputStream open(String objectKey);

    boolean exists(String objectKey);

    long size(String objectKey);

    void delete(String objectKey);

    /** Fails fast when the configured provider cannot be read/written. */
    void checkAvailable();

    /** Sanitized provider label for administrator diagnostics. */
    String location();
}
