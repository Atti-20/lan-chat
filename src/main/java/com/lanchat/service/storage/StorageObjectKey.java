package com.lanchat.service.storage;

import java.util.Locale;

final class StorageObjectKey {

    private StorageObjectKey() {
    }

    static String normalize(String value) {
        if (value == null) throw new IllegalArgumentException("存储对象名无效");
        String normalized = value.trim().replace('\\', '/');
        if (normalized.isBlank()
                || normalized.startsWith("/")
                || normalized.endsWith("/")
                || normalized.contains("//")
                || normalized.contains("../")
                || normalized.contains("/..")
                || normalized.equals("..")
                || normalized.length() > 500
                || !normalized.toLowerCase(Locale.ROOT)
                        .matches("^[a-z0-9][a-z0-9._/-]*$")) {
            throw new IllegalArgumentException("存储对象名无效");
        }
        return normalized;
    }
}
