package com.lanchat.service;

import com.lanchat.entity.FileMetadata;
import com.lanchat.mapper.FileMetadataMapper;
import com.lanchat.service.impl.FileServiceImpl;
import com.lanchat.service.storage.FileObjectStorageRegistry;
import com.lanchat.service.storage.LocalFileObjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileServiceStorageProviderTest {

    @TempDir
    Path storageRoot;

    @Test
    void legacyMetadataWithoutStorageTypeStillReadsFromLocalProvider() throws Exception {
        String storedName = "0123456789abcdef0123456789abcdef.pdf";
        Files.write(storageRoot.resolve(storedName), "%PDF-test".getBytes());
        FileMetadata metadata = new FileMetadata();
        metadata.setFileName("manual.pdf");
        metadata.setFilePath(storedName);
        metadata.setFileType("application/pdf");
        metadata.setStorageType(null);

        FileMetadataMapper mapper = mock(FileMetadataMapper.class);
        when(mapper.selectOne(any())).thenReturn(metadata);
        LocalFileObjectStorage local = new LocalFileObjectStorage(storageRoot);
        FileServiceImpl service = new FileServiceImpl();
        ReflectionTestUtils.setField(service, "fileMetadataMapper", mapper);
        ReflectionTestUtils.setField(service, "filePath", storageRoot.toString());
        ReflectionTestUtils.setField(service, "storageRegistry",
                new FileObjectStorageRegistry(List.of(local), "LOCAL"));

        var content = service.openContent(storedName);

        assertEquals(9, content.contentLength());
        assertEquals("manual.pdf", content.originalName());
        assertArrayEquals("%PDF-test".getBytes(), content.resource().getInputStream().readAllBytes());
    }
}
