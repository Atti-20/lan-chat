package com.lanchat.common;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Inspects uploaded bytes instead of trusting the browser supplied MIME type.
 *
 * <p>The supported signatures intentionally mirror LanChat's configurable
 * attachment allow-list. Unknown or active-content formats are rejected before
 * they are moved into the private storage directory.</p>
 */
public final class FileContentInspector {

    private static final int HEADER_SIZE = 560;
    private static final int TEXT_SAMPLE_SIZE = 64 * 1024;
    private static final int MAX_ZIP_ENTRIES_TO_INSPECT = 2_048;

    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "csv", "json", "md");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp");
    private static final Set<String> ALWAYS_BLOCKED_EXTENSIONS = Set.of(
            "html", "htm", "xhtml", "svg", "xml", "js", "mjs", "cjs", "css",
            "exe", "dll", "com", "bat", "cmd", "ps1", "sh", "bash", "zsh",
            "jar", "war", "class", "msi", "apk", "app", "dmg", "iso", "lnk", "url"
    );
    private static final Map<String, Set<String>> DECLARED_TYPES_BY_EXTENSION = Map.ofEntries(
            Map.entry("jpg", Set.of("image/jpeg", "image/jpg", "image/pjpeg")),
            Map.entry("jpeg", Set.of("image/jpeg", "image/jpg", "image/pjpeg")),
            Map.entry("png", Set.of("image/png", "image/x-png")),
            Map.entry("gif", Set.of("image/gif")),
            Map.entry("bmp", Set.of("image/bmp", "image/x-ms-bmp")),
            Map.entry("webp", Set.of("image/webp")),
            Map.entry("pdf", Set.of("application/pdf", "application/x-pdf")),
            Map.entry("doc", Set.of("application/msword")),
            Map.entry("xls", Set.of("application/vnd.ms-excel")),
            Map.entry("ppt", Set.of("application/vnd.ms-powerpoint")),
            Map.entry("docx", Set.of(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/zip", "application/x-zip-compressed")),
            Map.entry("xlsx", Set.of(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/zip", "application/x-zip-compressed")),
            Map.entry("pptx", Set.of(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/zip", "application/x-zip-compressed")),
            Map.entry("zip", Set.of("application/zip", "application/x-zip", "application/x-zip-compressed")),
            Map.entry("rar", Set.of("application/vnd.rar", "application/x-rar", "application/x-rar-compressed")),
            Map.entry("7z", Set.of("application/x-7z-compressed")),
            Map.entry("gz", Set.of("application/gzip", "application/x-gzip")),
            Map.entry("tar", Set.of("application/x-tar")),
            Map.entry("mp3", Set.of("audio/mpeg", "audio/mp3")),
            Map.entry("wav", Set.of("audio/wav", "audio/x-wav")),
            Map.entry("avi", Set.of("video/x-msvideo", "video/avi")),
            Map.entry("mp4", Set.of("video/mp4")),
            Map.entry("mov", Set.of("video/quicktime")),
            Map.entry("txt", Set.of("text/plain")),
            Map.entry("csv", Set.of("text/csv", "text/plain", "application/csv", "application/vnd.ms-excel")),
            Map.entry("json", Set.of("application/json", "text/json", "text/plain")),
            Map.entry("md", Set.of("text/markdown", "text/plain"))
    );

    private FileContentInspector() {
    }

    public record Inspection(String extension, String mediaType, boolean image) {
    }

    public static Inspection inspect(Path path,
                                     String originalFilename,
                                     String declaredContentType,
                                     long maxImagePixels) {
        String extension = extensionOf(originalFilename);
        if (extension == null || ALWAYS_BLOCKED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("不支持的文件类型");
        }

        byte[] header = readHeader(path);
        String mediaType = detectMediaType(path, extension, header);
        verifyDeclaredType(declaredContentType, extension);

        boolean image = IMAGE_EXTENSIONS.contains(extension);
        if (image) validateImage(path, maxImagePixels);
        return new Inspection(extension, mediaType, image);
    }

    public static String extensionOf(String fileName) {
        if (fileName == null) return null;
        int separator = fileName.lastIndexOf('.');
        if (separator <= 0 || separator == fileName.length() - 1) return null;
        String extension = fileName.substring(separator + 1).toLowerCase(Locale.ROOT);
        return extension.matches("^[a-z0-9]{1,10}$") ? extension : null;
    }

    private static byte[] readHeader(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            return input.readNBytes(HEADER_SIZE);
        } catch (IOException exception) {
            throw new IllegalArgumentException("文件内容无法读取");
        }
    }

    private static String detectMediaType(Path path, String extension, byte[] header) {
        return switch (extension) {
            case "jpg", "jpeg" -> require(header, bytes(0xff, 0xd8, 0xff), "image/jpeg");
            case "png" -> require(header, bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), "image/png");
            case "gif" -> requireAny(header, "image/gif", ascii("GIF87a"), ascii("GIF89a"));
            case "bmp" -> require(header, ascii("BM"), "image/bmp");
            case "webp" -> requireRiff(header, ascii("WEBP"), "image/webp");
            case "pdf" -> require(header, ascii("%PDF-"), "application/pdf");
            case "doc", "xls", "ppt" -> require(header,
                    bytes(0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1), legacyOfficeType(extension));
            case "docx", "xlsx", "pptx" -> inspectOfficeZip(path, extension);
            case "zip" -> requireZip(header, "application/zip");
            case "rar" -> requireAny(header, "application/vnd.rar",
                    bytes(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00),
                    bytes(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00));
            case "7z" -> require(header, bytes(0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c), "application/x-7z-compressed");
            case "gz" -> require(header, bytes(0x1f, 0x8b), "application/gzip");
            case "tar" -> requireAt(header, 257, ascii("ustar"), "application/x-tar");
            case "mp3" -> inspectMp3(header);
            case "wav" -> requireRiff(header, ascii("WAVE"), "audio/wav");
            case "avi" -> requireRiff(header, ascii("AVI "), "video/x-msvideo");
            case "mp4" -> inspectIsoMedia(header, "video/mp4");
            case "mov" -> inspectIsoMedia(header, "video/quicktime");
            default -> {
                if (TEXT_EXTENSIONS.contains(extension)) {
                    validateUtf8Text(path);
                    yield textMediaType(extension);
                }
                throw new IllegalArgumentException("文件内容类型不在安全白名单中");
            }
        };
    }

    private static String inspectOfficeZip(Path path, String extension) {
        String requiredPrefix = switch (extension) {
            case "docx" -> "word/";
            case "xlsx" -> "xl/";
            case "pptx" -> "ppt/";
            default -> throw new IllegalArgumentException("Office 文件类型无效");
        };
        boolean contentTypes = false;
        boolean expectedRoot = false;
        int inspected = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null && inspected++ < MAX_ZIP_ENTRIES_TO_INSPECT) {
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../")) {
                    throw new IllegalArgumentException("压缩文件包含不安全路径");
                }
                if ("[Content_Types].xml".equals(name)) contentTypes = true;
                if (name.startsWith(requiredPrefix)) expectedRoot = true;
                if (contentTypes && expectedRoot) break;
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Office 文件结构损坏");
        }
        if (!contentTypes || !expectedRoot) {
            throw new IllegalArgumentException("文件扩展名与 Office 内容不匹配");
        }
        return switch (extension) {
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default -> throw new IllegalArgumentException("Office 文件类型无效");
        };
    }

    private static String inspectMp3(byte[] header) {
        boolean id3 = startsWith(header, ascii("ID3"));
        boolean frame = header.length >= 2
                && (header[0] & 0xff) == 0xff
                && ((header[1] & 0xe0) == 0xe0);
        if (!id3 && !frame) throw mismatch();
        return "audio/mpeg";
    }

    private static String inspectIsoMedia(byte[] header, String mediaType) {
        if (header.length < 12 || !matchesAt(header, 4, ascii("ftyp"))) throw mismatch();
        return mediaType;
    }

    private static String requireZip(byte[] header, String mediaType) {
        return requireAny(header, mediaType,
                bytes(0x50, 0x4b, 0x03, 0x04),
                bytes(0x50, 0x4b, 0x05, 0x06),
                bytes(0x50, 0x4b, 0x07, 0x08));
    }

    private static String requireRiff(byte[] header, byte[] formType, String mediaType) {
        if (header.length < 12 || !startsWith(header, ascii("RIFF")) || !matchesAt(header, 8, formType)) {
            throw mismatch();
        }
        return mediaType;
    }

    private static String require(byte[] header, byte[] signature, String mediaType) {
        if (!startsWith(header, signature)) throw mismatch();
        return mediaType;
    }

    private static String requireAt(byte[] header, int offset, byte[] signature, String mediaType) {
        if (!matchesAt(header, offset, signature)) throw mismatch();
        return mediaType;
    }

    private static String requireAny(byte[] header, String mediaType, byte[]... signatures) {
        if (Arrays.stream(signatures).noneMatch(signature -> startsWith(header, signature))) throw mismatch();
        return mediaType;
    }

    private static void validateUtf8Text(Path path) {
        byte[] sample;
        try (InputStream input = Files.newInputStream(path)) {
            sample = input.readNBytes(TEXT_SAMPLE_SIZE);
        } catch (IOException exception) {
            throw new IllegalArgumentException("文本文件无法读取");
        }
        for (byte value : sample) {
            if (value == 0) throw new IllegalArgumentException("文本文件包含二进制内容");
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(sample));
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("文本文件必须使用 UTF-8 编码");
        }
    }

    private static void validateImage(Path path, long maxImagePixels) {
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) throw new IllegalArgumentException("图片内容无法解析");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("图片内容无法解析");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > maxImagePixels) {
                    throw new IllegalArgumentException("图片像素尺寸超过安全限制");
                }
            } finally {
                reader.dispose();
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IllegalArgumentException("图片内容无法解析");
        }
    }

    private static void verifyDeclaredType(String declared, String extension) {
        if (declared == null || declared.isBlank()) return;
        String normalized = declared.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || "application/octet-stream".equals(normalized)) return;
        Set<String> accepted = DECLARED_TYPES_BY_EXTENSION.getOrDefault(extension, Set.of());
        if (!accepted.contains(normalized)) {
            throw new IllegalArgumentException("文件扩展名、MIME 与内容不一致");
        }
    }

    private static String legacyOfficeType(String extension) {
        return switch (extension) {
            case "doc" -> "application/msword";
            case "xls" -> "application/vnd.ms-excel";
            case "ppt" -> "application/vnd.ms-powerpoint";
            default -> "application/octet-stream";
        };
    }

    private static String textMediaType(String extension) {
        return switch (extension) {
            case "csv" -> "text/csv";
            case "json" -> "application/json";
            case "md" -> "text/markdown";
            default -> "text/plain";
        };
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        return matchesAt(value, 0, prefix);
    }

    private static boolean matchesAt(byte[] value, int offset, byte[] expected) {
        if (offset < 0 || value.length < offset + expected.length) return false;
        for (int index = 0; index < expected.length; index++) {
            if (value[offset + index] != expected[index]) return false;
        }
        return true;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) result[index] = (byte) values[index];
        return result;
    }

    private static IllegalArgumentException mismatch() {
        return new IllegalArgumentException("文件扩展名与实际内容不匹配");
    }
}
