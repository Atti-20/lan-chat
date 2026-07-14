package com.lanchat.controller;

import com.lanchat.entity.FileMetadata;
import com.lanchat.service.FileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileControllerTest {

    @TempDir
    Path storage;

    @Test
    void signedUrlStreamsDownloadWithoutAuthenticatedContext() throws Exception {
        String token = "0123456789abcdef0123456789abcdef";
        String storedName = "stored.xlsx";
        Files.writeString(storage.resolve(storedName), "file-content");

        FileMetadata metadata = new FileMetadata();
        metadata.setFileName("report.xlsx");
        metadata.setFilePath(storedName);

        FileService fileService = mock(FileService.class);
        when(fileService.getFileNameFromToken(token)).thenReturn(storedName);
        when(fileService.getByStoredName(storedName)).thenReturn(metadata);

        FileController controller = new FileController();
        ReflectionTestUtils.setField(controller, "fileService", fileService);
        ReflectionTestUtils.setField(controller, "filePath", storage.toString());

        var response = controller.previewFile(token, true);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(12, response.getHeaders().getContentLength());
        assertTrue(response.getHeaders().getContentDisposition().isAttachment());
        assertEquals("report.xlsx", response.getHeaders().getContentDisposition().getFilename());
        assertEquals("public, max-age=600, s-maxage=600", response.getHeaders().getFirst("Cache-Control"));
    }

    @Test
    void invalidSignedUrlIsRejected() {
        FileService fileService = mock(FileService.class);
        FileController controller = new FileController();
        ReflectionTestUtils.setField(controller, "fileService", fileService);

        var response = controller.previewFile("expired", false);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
}
