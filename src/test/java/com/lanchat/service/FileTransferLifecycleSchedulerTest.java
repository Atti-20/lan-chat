package com.lanchat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileTransferLifecycleSchedulerTest {

    @Test
    void delegatesExpiryScanToDomainService() {
        FileTransferService service = mock(FileTransferService.class);
        when(service.expirePendingTransfers()).thenReturn(3);

        new FileTransferLifecycleScheduler(service).expirePendingTransfers();

        verify(service).expirePendingTransfers();
    }

    @Test
    void keepsSchedulerAliveWhenScanFails() {
        FileTransferService service = mock(FileTransferService.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(service).expirePendingTransfers();

        assertDoesNotThrow(() ->
                new FileTransferLifecycleScheduler(service).expirePendingTransfers());
    }
}
