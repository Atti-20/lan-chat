package com.lanchat.service.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class FileObjectStorageRegistry {

    private final Map<String, FileObjectStorage> storages;
    private final String activeType;

    public FileObjectStorageRegistry(List<FileObjectStorage> storages,
                                     @Value("${file.storage-type:LOCAL}") String activeType) {
        this.storages = storages.stream().collect(Collectors.toUnmodifiableMap(
                storage -> storage.type().toUpperCase(Locale.ROOT), Function.identity()));
        this.activeType = normalizeType(activeType);
        if (!this.storages.containsKey(this.activeType)) {
            throw new IllegalStateException("不支持的文件存储类型: " + activeType);
        }
    }

    public FileObjectStorage active() {
        return forType(activeType);
    }

    public FileObjectStorage forType(String type) {
        String normalized = normalizeType(type);
        FileObjectStorage storage = storages.get(normalized);
        if (storage == null) throw new IllegalStateException("不支持的文件存储类型: " + type);
        return storage;
    }

    public String activeType() {
        return activeType;
    }

    private String normalizeType(String type) {
        return type == null || type.isBlank() ? "LOCAL" : type.trim().toUpperCase(Locale.ROOT);
    }
}
