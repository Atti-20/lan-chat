package com.lanchat.common;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FileReferenceUtilTest {

    @Test
    void extractsOriginalNameFromCurrentAttachmentJson() {
        String content = "{\"url\":\"/api/v1/file/content/0123456789abcdef0123456789abcdef.png\","
                + "\"thumbnailUrl\":\"/api/v1/file/content/thumb_0123456789abcdef0123456789abcdef.png\"}";

        assertEquals("0123456789abcdef0123456789abcdef.png",
                FileReferenceUtil.extractFirstStoredName(content));
        assertEquals(Set.of("0123456789abcdef0123456789abcdef.png"),
                FileReferenceUtil.extractStoredNames(content));
    }

    @Test
    void extractsLegacyFileUrlAndRejectsUnrelatedText() {
        assertEquals("0123456789abcdef0123456789abcdef.xlsx",
                FileReferenceUtil.extractFirstStoredName("/file/0123456789abcdef0123456789abcdef.xlsx"));
        assertNull(FileReferenceUtil.extractFirstStoredName("not an attachment"));
    }
}
