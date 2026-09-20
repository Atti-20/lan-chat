package com.lanchat.service.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Component
public class LocalFileObjectStorage implements FileObjectStorage {

    private final Path root;

    @Autowired
    public LocalFileObjectStorage(@Value("${file.path}") String path) {
        this(Paths.get(path));
    }

    public LocalFileObjectStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String type() {
        return "LOCAL";
    }

    @Override
    public void put(String objectKey, Path source, String contentType) {
        Path destination = resolve(objectKey);
        try {
            Files.createDirectories(destination.getParent());
            Path temporary = Files.createTempFile(destination.getParent(), ".object-", ".tmp");
            try {
                Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(temporary, destination,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("文件存储写入失败", exception);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            return Files.newInputStream(resolve(objectKey));
        } catch (IOException exception) {
            throw new IllegalArgumentException("文件不存在", exception);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        return Files.isRegularFile(resolve(objectKey));
    }

    @Override
    public long size(String objectKey) {
        try {
            return Files.size(resolve(objectKey));
        } catch (IOException exception) {
            throw new IllegalArgumentException("文件不存在", exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolve(objectKey));
        } catch (IOException exception) {
            throw new IllegalStateException("文件存储清理失败", exception);
        }
    }

    @Override
    public void checkAvailable() {
        try {
            Files.createDirectories(root);
            if (!Files.isDirectory(root) || !Files.isReadable(root) || !Files.isWritable(root)) {
                throw new IllegalStateException("本地文件存储目录不可读写");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("本地文件存储目录不可用", exception);
        }
    }

    @Override
    public String location() {
        return root.toString();
    }

    private Path resolve(String objectKey) {
        String normalizedKey = StorageObjectKey.normalize(objectKey);
        Path resolved = root.resolve(normalizedKey).normalize();
        if (!resolved.startsWith(root)) throw new IllegalArgumentException("存储对象名无效");
        return resolved;
    }
}
