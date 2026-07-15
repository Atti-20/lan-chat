package com.lanchat.service;

import com.lanchat.dto.FileCheckDTO;
import com.lanchat.dto.FileUploadVO;
import com.lanchat.entity.FileMetadata;
import com.lanchat.mapper.FileAccessGrantMapper;
import com.lanchat.service.impl.FileServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class FileServiceSecurityTest {

    @Test
    void hashCheckDoesNotGrantAccessToAnotherUsersFile() {
        FileServiceImpl service = spy(new FileServiceImpl());
        FileCheckDTO request = new FileCheckDTO();
        request.setFileHash("a".repeat(64));
        FileMetadata existing = new FileMetadata();
        existing.setFilePath("0123456789abcdef0123456789abcdef.pdf");

        doReturn(existing).when(service).getByHash(request.getFileHash());
        doReturn(false).when(service).canAccessFile(existing.getFilePath(), 42L);

        assertNull(service.checkFile(request, 42L));
    }

    @Test
    void completeDuplicateUploadCreatesAnExplicitGrant() {
        FileServiceImpl service = spy(new FileServiceImpl());
        FileAccessGrantMapper grantMapper = mock(FileAccessGrantMapper.class);
        ReflectionTestUtils.setField(service, "fileAccessGrantMapper", grantMapper);
        ReflectionTestUtils.setField(service, "allowedTypes", "pdf");
        ReflectionTestUtils.setField(service, "maxFileSize", 1024L);
        FileMetadata existing = new FileMetadata();
        existing.setId(8L);
        existing.setFilePath("0123456789abcdef0123456789abcdef.pdf");
        existing.setFileName("manual.pdf");
        existing.setFileSuffix(".pdf");
        existing.setFileSize(7L);
        doReturn(existing).when(service).getByHash(
                "239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5");
        MockMultipartFile upload = new MockMultipartFile(
                "file", "manual.pdf", "application/pdf", "payload".getBytes());

        FileUploadVO result = service.uploadFile(upload, 42L);

        verify(grantMapper).grant(8L, 42L, "UPLOAD_PROOF");
        assertTrue(result.getInstantUpload());
    }
}
