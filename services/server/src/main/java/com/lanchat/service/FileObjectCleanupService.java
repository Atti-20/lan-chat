package com.lanchat.service;

public interface FileObjectCleanupService {

    /** Persists cleanup intent in the caller transaction and attempts it after commit. */
    void enqueue(String storageType, String objectKey, String reason);

    /** Persists rollback cleanup in a transaction detached from the rolled-back caller. */
    void enqueueDetached(String storageType, String objectKey, String reason);

    /**
     * Persists an object intent before a part write. A delayed worker first
     * reconciles the exact part reference and deletes only unreferenced bytes.
     */
    void enqueuePartReconciliation(String storageType, String objectKey,
                                   String uploadId, int partNumber, String reason);

    /** Claims and resolves a reconciliation intent in the part-row transaction. */
    boolean claimPartReference(String storageType, String objectKey,
                               String uploadId, int partNumber);

    /** Attempts one durable task. Deletion is idempotent. */
    boolean attemptTask(Long taskId);

    /** Retries due tasks and returns the number processed successfully. */
    int retryPending();
}
