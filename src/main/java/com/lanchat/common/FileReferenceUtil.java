package com.lanchat.common;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts UUID-based stored file names from attachment payloads. */
public final class FileReferenceUtil {

    private static final Pattern STORED_FILE_PATTERN = Pattern.compile(
            "(?:thumb_)?([0-9a-fA-F]{32}\\.[a-zA-Z0-9]{1,10})");

    private FileReferenceUtil() {
    }

    public static String extractFirstStoredName(String content) {
        if (content == null || content.isBlank()) return null;
        Matcher matcher = STORED_FILE_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static Set<String> extractStoredNames(String content) {
        Set<String> names = new LinkedHashSet<>();
        if (content == null || content.isBlank()) return names;

        Matcher matcher = STORED_FILE_PATTERN.matcher(content);
        while (matcher.find()) names.add(matcher.group(1));
        return names;
    }
}
