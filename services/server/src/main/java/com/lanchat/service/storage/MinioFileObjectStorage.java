package com.lanchat.service.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class MinioFileObjectStorage implements FileObjectStorage {

    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String bucket;
    private final String region;
    private final AtomicBoolean bucketReady = new AtomicBoolean(false);
    private volatile MinioClient client;

    public MinioFileObjectStorage(
            @Value("${file.minio.endpoint:}") String endpoint,
            @Value("${file.minio.access-key:}") String accessKey,
            @Value("${file.minio.secret-key:}") String secretKey,
            @Value("${file.minio.bucket:lanchat}") String bucket,
            @Value("${file.minio.region:}") String region) {
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.bucket = bucket;
        this.region = region;
    }

    @Override
    public String type() {
        return "MINIO";
    }

    @Override
    public void put(String objectKey, Path source, String contentType) {
        String key = StorageObjectKey.normalize(objectKey);
        try {
            ensureBucket();
            try (InputStream input = Files.newInputStream(source)) {
                client().putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(key)
                        .stream(input, Files.size(source), -1L)
                        .contentType(StringUtils.hasText(contentType)
                                ? contentType : "application/octet-stream")
                        .build());
            }
        } catch (Exception exception) {
            throw storageFailure("MinIO 文件写入失败", exception);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            ensureBucket();
            return client().getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(StorageObjectKey.normalize(objectKey))
                    .build());
        } catch (Exception exception) {
            throw storageFailure("MinIO 文件读取失败", exception);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        try {
            ensureBucket();
            client().statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(StorageObjectKey.normalize(objectKey))
                    .build());
            return true;
        } catch (ErrorResponseException exception) {
            return exception.errorResponse() == null
                    || !"NoSuchKey".equals(exception.errorResponse().code())
                    ? failExists(exception) : false;
        } catch (Exception exception) {
            throw storageFailure("MinIO 文件状态读取失败", exception);
        }
    }

    @Override
    public long size(String objectKey) {
        try {
            ensureBucket();
            return client().statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(StorageObjectKey.normalize(objectKey))
                    .build()).size();
        } catch (Exception exception) {
            throw storageFailure("MinIO 文件状态读取失败", exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            ensureBucket();
            client().removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(StorageObjectKey.normalize(objectKey))
                    .build());
        } catch (Exception exception) {
            throw storageFailure("MinIO 文件清理失败", exception);
        }
    }

    @Override
    public void checkAvailable() {
        try {
            ensureBucket();
        } catch (Exception exception) {
            throw storageFailure("MinIO 存储不可用", exception);
        }
    }

    @Override
    public String location() {
        return "MINIO:" + bucket;
    }

    private boolean failExists(Exception exception) {
        throw storageFailure("MinIO 文件状态读取失败", exception);
    }

    private MinioClient client() {
        MinioClient value = client;
        if (value != null) return value;
        synchronized (this) {
            if (client == null) {
                if (!StringUtils.hasText(endpoint)
                        || !StringUtils.hasText(accessKey)
                        || !StringUtils.hasText(secretKey)) {
                    throw new IllegalStateException("MinIO 配置不完整");
                }
                client = MinioClient.builder()
                        .endpoint(endpoint)
                        .credentials(accessKey, secretKey)
                        .build();
            }
            return client;
        }
    }

    private void ensureBucket() throws Exception {
        if (bucketReady.get()) return;
        synchronized (bucketReady) {
            if (bucketReady.get()) return;
            BucketExistsArgs existsArgs = BucketExistsArgs.builder().bucket(bucket).build();
            if (!client().bucketExists(existsArgs)) {
                MakeBucketArgs.Builder builder = MakeBucketArgs.builder().bucket(bucket);
                if (StringUtils.hasText(region)) builder.region(region);
                try {
                    client().makeBucket(builder.build());
                } catch (ErrorResponseException concurrentCreate) {
                    String code = concurrentCreate.errorResponse() == null
                            ? null : concurrentCreate.errorResponse().code();
                    if (!"BucketAlreadyOwnedByYou".equals(code)
                            && !"BucketAlreadyExists".equals(code)
                            && !client().bucketExists(existsArgs)) {
                        throw concurrentCreate;
                    }
                }
            }
            bucketReady.set(true);
        }
    }

    private IllegalStateException storageFailure(String message, Exception exception) {
        return new IllegalStateException(message, exception);
    }
}
