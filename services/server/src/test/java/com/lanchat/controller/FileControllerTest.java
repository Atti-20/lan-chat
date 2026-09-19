package com.lanchat.controller;

import com.lanchat.dto.FilePreviewGrant;
import com.lanchat.entity.FileMetadata;
import com.lanchat.service.FileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        when(fileService.resolvePreviewToken(token)).thenReturn(new FilePreviewGrant(storedName, 7L));
        when(fileService.getByStoredName(storedName)).thenReturn(metadata);
        when(fileService.canAccessFile(storedName, 7L)).thenReturn(true);

        FileController controller = new FileController();
        ReflectionTestUtils.setField(controller, "fileService", fileService);
        ReflectionTestUtils.setField(controller, "filePath", storage.toString());

        var response = controller.previewFile(token, true);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(12, response.getHeaders().getContentLength());
        assertTrue(response.getHeaders().getContentDisposition().isAttachment());
        assertEquals("report.xlsx", response.getHeaders().getContentDisposition().getFilename());
        assertEquals("no-store, private", response.getHeaders().getFirst("Cache-Control"));
        assertEquals("cross-origin", response.getHeaders().getFirst("Cross-Origin-Resource-Policy"));
        assertNull(response.getHeaders().getFirst("X-Frame-Options"));
    }

    @Test
    void invalidSignedUrlIsRejected() {
        FileService fileService = mock(FileService.class);
        FileController controller = new FileController();
        ReflectionTestUtils.setField(controller, "fileService", fileService);

        var response = controller.previewFile("expired", false);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void activeContentIsForcedToDownload() throws Exception {
        String token = "fedcba9876543210fedcba9876543210";
        String storedName = "unsafe.html";
        Files.writeString(storage.resolve(storedName), "<script>alert(1)</script>");
        FileMetadata metadata = new FileMetadata();
        metadata.setFileName("unsafe.html");
        metadata.setFilePath(storedName);
        FileService fileService = mock(FileService.class);
        when(fileService.resolvePreviewToken(token)).thenReturn(new FilePreviewGrant(storedName, 7L));
        when(fileService.getByStoredName(storedName)).thenReturn(metadata);
        when(fileService.canAccessFile(storedName, 7L)).thenReturn(true);
        FileController controller = new FileController();
        ReflectionTestUtils.setField(controller, "fileService", fileService);
        ReflectionTestUtils.setField(controller, "filePath", storage.toString());

        var response = controller.previewFile(token, false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getHeaders().getContentDisposition().isAttachment());
        assertEquals("application/octet-stream", response.getHeaders().getContentType().toString());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
    }

    @Test
    void signedUrlIsRejectedAfterFilePermissionIsRevoked() {
        String token = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String storedName = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.pdf";
        FileService fileService = mock(FileService.class);
        when(fileService.resolvePreviewToken(token)).thenReturn(new FilePreviewGrant(storedName, 7L));
        when(fileService.canAccessFile(storedName, 7L)).thenReturn(false);
        FileController controller = new FileController();
        ReflectionTestUtils.setField(controller, "fileService", fileService);

        var response = controller.previewFile(token, false);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }
}
