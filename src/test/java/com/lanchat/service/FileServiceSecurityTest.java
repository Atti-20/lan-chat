package com.lanchat.service;

import com.lanchat.dto.FileCheckDTO;
import com.lanchat.dto.FileUploadVO;
import com.lanchat.entity.FileMetadata;
import com.lanchat.mapper.ChatGroupMapper;
import com.lanchat.mapper.FileAccessGrantMapper;
import com.lanchat.mapper.UserMapper;
import com.lanchat.service.impl.FileServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileServiceSecurityTest {

    @TempDir
    Path storage;

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
        com.lanchat.mapper.FileMetadataMapper metadataMapper =
                mock(com.lanchat.mapper.FileMetadataMapper.class);
        ReflectionTestUtils.setField(service, "fileAccessGrantMapper", grantMapper);
        ReflectionTestUtils.setField(service, "fileMetadataMapper", metadataMapper);
        ReflectionTestUtils.setField(service, "allowedTypes", "pdf");
        ReflectionTestUtils.setField(service, "maxFileSize", 1024L);
        ReflectionTestUtils.setField(service, "minimumFreeSpace", 0L);
        ReflectionTestUtils.setField(service, "maxImagePixels", 40_000_000L);
        ReflectionTestUtils.setField(service, "filePath", storage.toString());
        FileMetadata existing = new FileMetadata();
        existing.setId(8L);
        existing.setFilePath("0123456789abcdef0123456789abcdef.pdf");
        existing.setFileName("manual.pdf");
        existing.setFileSuffix(".pdf");
        existing.setFileSize(7L);
        when(metadataMapper.selectByHashForUpdate(
                "66ebae4f8038efcf6a3d0a9cdd07b4956c9094caaef2fd669f5f7c2f5e7176ad"))
                .thenReturn(existing);
        MockMultipartFile upload = new MockMultipartFile(
                "file", "manual.pdf", "application/pdf", "%PDF-payload".getBytes());

        FileUploadVO result = service.uploadFile(upload, 42L);

        verify(grantMapper).grant(8L, 42L, "UPLOAD_PROOF");
        assertTrue(result.getInstantUpload());
    }

    @Test
    void groupAvatarRemainsReadableAfterItsOriginalUploaderLeaves() {
        FileServiceImpl service = spy(new FileServiceImpl());
        FileAccessGrantMapper grantMapper = mock(FileAccessGrantMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        ChatGroupMapper groupMapper = mock(ChatGroupMapper.class);
        ReflectionTestUtils.setField(service, "fileAccessGrantMapper", grantMapper);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "chatGroupMapper", groupMapper);

        FileMetadata metadata = new FileMetadata();
        metadata.setId(8L);
        metadata.setUploadUserId(7L);
        metadata.setFilePath("0123456789abcdef0123456789abcdef.png");
        doReturn(metadata).when(service).getByStoredName(metadata.getFilePath());
        when(grantMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(groupMapper.selectCount(any())).thenReturn(1L);

        assertTrue(service.canAccessFile(metadata.getFilePath(), 42L));
    }
}
