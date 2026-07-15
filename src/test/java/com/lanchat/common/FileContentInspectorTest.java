package com.lanchat.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileContentInspectorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsExecutableBytesRenamedAsPdf() throws Exception {
        Path upload = temporaryDirectory.resolve("renamed.pdf");
        Files.write(upload, new byte[]{0x4d, 0x5a, 0x10, 0x00});

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> FileContentInspector.inspect(upload, "manual.pdf", "application/pdf", 1_000_000));

        assertEquals("文件扩展名与实际内容不匹配", exception.getMessage());
    }

    @Test
    void acceptsUtf8TextAndUsesSafeServerMime() throws Exception {
        Path upload = temporaryDirectory.resolve("notes.txt");
        Files.writeString(upload, "局域网内的安全文本");

        FileContentInspector.Inspection inspection = FileContentInspector.inspect(
                upload, "notes.txt", "text/plain", 1_000_000);

        assertEquals("text/plain", inspection.mediaType());
    }

    @Test
    void blocksActiveContentEvenBeforeSignatureInspection() throws Exception {
        Path upload = temporaryDirectory.resolve("page.html");
        Files.writeString(upload, "<script>alert(1)</script>");

        assertThrows(IllegalArgumentException.class,
                () -> FileContentInspector.inspect(upload, "page.html", "text/html", 1_000_000));
    }

    @Test
    void rejectsDeclaredMimeThatDoesNotMatchValidFileBytes() throws Exception {
        Path upload = temporaryDirectory.resolve("manual.pdf");
        Files.writeString(upload, "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> FileContentInspector.inspect(upload, "manual.pdf", "text/plain", 1_000_000));

        assertEquals("文件扩展名、MIME 与内容不一致", exception.getMessage());
    }

    @Test
    void acceptsValidWebpAndReadsItsDimensions() throws Exception {
        Path upload = temporaryDirectory.resolve("pixel.webp");
        Files.write(upload, Base64.getDecoder().decode(
                "UklGRjoAAABXRUJQVlA4IC4AAACyAgCdASoCAAIALmk0mk0iIiIiIgBoSygABc6WWgAA/veff/0PP8bA//LwYAAA"));

        FileContentInspector.Inspection inspection = FileContentInspector.inspect(
                upload, "pixel.webp", "image/webp", 100);

        assertEquals("image/webp", inspection.mediaType());
    }
}
