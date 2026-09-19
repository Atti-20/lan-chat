import 'dart:convert';
import '../core/models.dart';

const attachmentByteLimit = 25 * 1024 * 1024;

final _storedFilePattern = RegExp(
  r'^(?:thumb_)?([0-9a-fA-F]{32}\.[a-zA-Z0-9]{1,10})$',
);

String _requiredString(Json value, String key) {
  final raw = value[key];
  if (raw is! String || raw.trim().isEmpty) {
    throw FormatException('附件字段 $key 无效');
  }
  return raw.trim();
}

String? _optionalString(Json value, String key) {
  final raw = value[key];
  if (raw == null) return null;
  if (raw is! String || raw.trim().isEmpty) {
    throw FormatException('附件字段 $key 无效');
  }
  return raw.trim();
}

String storedFileName(String raw) {
  final uri = Uri.tryParse(raw);
  if (uri == null ||
      uri.hasScheme ||
      uri.hasAuthority ||
      uri.hasQuery ||
      uri.hasFragment) {
    throw const FormatException('附件地址必须来自当前节点');
  }
  final segments = uri.pathSegments;
  if (segments.length != 5 ||
      segments[0] != 'api' ||
      segments[1] != 'v1' ||
      segments[2] != 'file' ||
      segments[3] != 'content' ||
      !_storedFilePattern.hasMatch(segments[4])) {
    throw const FormatException('附件地址无效');
  }
  return _storedFilePattern.firstMatch(segments[4])!.group(1)!;
}

class AttachmentData {
  const AttachmentData({
    required this.url,
    required this.name,
    required this.size,
    required this.mime,
    required this.fileHash,
    this.thumbnailUrl,
    this.originalUrl,
    this.transferPath = 'NODE_RELAY',
  });

  final String url, name, mime, fileHash, transferPath;
  final String? thumbnailUrl, originalUrl;
  final int size;

  bool get image => mime.toLowerCase().startsWith('image/');
  String get storedName => storedFileName(originalUrl ?? url);

  factory AttachmentData.fromJson(Json value) {
    final transferPath = _optionalString(value, 'transferPath') ?? 'NODE_RELAY';
    if (transferPath != 'NODE_RELAY') {
      throw const FormatException('当前设备没有直传文件副本');
    }
    final url = _requiredString(value, 'url');
    final originalUrl = _optionalString(value, 'originalUrl');
    storedFileName(originalUrl ?? url);
    final thumbnail = _optionalString(value, 'thumbnailUrl');
    if (thumbnail != null) storedFileName(thumbnail);
    final size = integer(value['size']);
    if (size <= 0 || size > attachmentByteLimit) {
      throw const FormatException('附件大小无效');
    }
    final hash = _requiredString(value, 'fileHash').toLowerCase();
    if (!RegExp(r'^[0-9a-f]{64}$').hasMatch(hash)) {
      throw const FormatException('附件哈希无效');
    }
    final mime = _requiredString(value, 'mime').toLowerCase();
    if (!RegExp(r'^[a-z0-9.+-]+/[a-z0-9.+-]+$').hasMatch(mime)) {
      throw const FormatException('附件类型无效');
    }
    return AttachmentData(
      url: url,
      originalUrl: originalUrl,
      thumbnailUrl: thumbnail,
      name: _requiredString(value, 'name'),
      size: size,
      mime: mime,
      fileHash: hash,
      transferPath: transferPath,
    );
  }

  factory AttachmentData.decode(String value) {
    final decoded = jsonDecode(value);
    if (decoded is! Json) throw const FormatException('附件消息格式无效');
    return AttachmentData.fromJson(decoded);
  }

  Json toJson() => {
    'url': url,
    if (originalUrl != null) 'originalUrl': originalUrl,
    if (thumbnailUrl != null) 'thumbnailUrl': thumbnailUrl,
    'name': name,
    'size': size,
    'mime': mime,
    'fileHash': fileHash,
    'transferPath': transferPath,
  };

  String encode() => jsonEncode(toJson());
}

class FileUploadResult {
  const FileUploadResult({required this.attachment});
  final AttachmentData attachment;

  factory FileUploadResult.fromJson(Json value) {
    final url = _requiredString(value, 'url');
    final type = _requiredString(value, 'fileType');
    final size = integer(value['fileSize']);
    final hash = _requiredString(value, 'fileHash');
    final attachment = AttachmentData.fromJson({
      'url': url,
      if (type.toLowerCase().startsWith('image/')) 'originalUrl': url,
      if (value['thumbnailUrl'] != null) 'thumbnailUrl': value['thumbnailUrl'],
      'name': _requiredString(value, 'originalName'),
      'size': size,
      'mime': type,
      'fileHash': hash,
      'transferPath': 'NODE_RELAY',
    });
    final serverName = _requiredString(value, 'fileName');
    if (attachment.storedName != serverName) {
      throw const FormatException('附件存储标识不一致');
    }
    return FileUploadResult(attachment: attachment);
  }
}

class DownloadedAttachment {
  const DownloadedAttachment({
    required this.bytes,
    required this.mime,
    required this.name,
  });
  final List<int> bytes;
  final String mime, name;
}

class AttachmentCancelledException implements Exception {
  const AttachmentCancelledException();
  @override
  String toString() => '操作已取消';
}
