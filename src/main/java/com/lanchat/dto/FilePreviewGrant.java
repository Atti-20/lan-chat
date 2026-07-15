package com.lanchat.dto;

/** A short-lived preview bearer token resolved to its original authorization. */
public record FilePreviewGrant(String fileName, Long userId) {
}
