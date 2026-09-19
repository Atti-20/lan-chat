package com.lanchat.service.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileObjectStorageTest {

    @TempDir
    Path root;

    @Test
    void storesBothLegacyFilesAndNestedUploadPartsPrivately() throws Exception {
        LocalFileObjectStorage storage = new LocalFileObjectStorage(root);
        Path source = Files.write(root.resolve("source.tmp"), new byte[]{1, 2, 3});

        storage.put("multipart/0123456789abcdef0123456789abcdef/1-abc.part",
                source, "application/octet-stream");

        assertTrue(storage.exists("multipart/0123456789abcdef0123456789abcdef/1-abc.part"));
        assertArrayEquals(new byte[]{1, 2, 3}, storage.open(
                "multipart/0123456789abcdef0123456789abcdef/1-abc.part").readAllBytes());
        storage.delete("multipart/0123456789abcdef0123456789abcdef/1-abc.part");
        assertFalse(storage.exists("multipart/0123456789abcdef0123456789abcdef/1-abc.part"));
    }
}
