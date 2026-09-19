import 'dart:async';
import 'dart:collection';
import 'package:flutter/foundation.dart';
import '../chat_controller.dart';
import '../core/models.dart';
import '../core/platform_ports.dart';
import '../data/attachments_models.dart';
import '../data/meshx_api.dart';
import 'platform_coordinator.dart';

class AttachmentController extends ChangeNotifier {
  AttachmentController({required this.chat, required this.platform});

  final ChatController chat;
  final PlatformCoordinator platform;
  bool busy = false;
  int completedBytes = 0, totalBytes = 0;
  String? error;
  int _operation = 0;
  String? _owner;
  final LinkedHashMap<String, DownloadedAttachment> _downloads =
      LinkedHashMap();

  double? get progress => totalBytes > 0
      ? (completedBytes / totalBytes).clamp(0, 1).toDouble()
      : null;

  String? get owner => chat.api == null || chat.session == null
      ? null
      : '${chat.api!.origin}|${chat.session!.userId}';

  void _syncOwner() {
    final current = owner;
    if (_owner != current) {
      _owner = current;
      _downloads.clear();
      ++_operation;
    }
  }

  void cancel() {
    if (!busy) return;
    ++_operation;
    busy = false;
    completedBytes = 0;
    totalBytes = 0;
    error = null;
    unawaited(platform.clearFile());
    notifyListeners();
  }

  void clearError() {
    if (error == null) return;
    error = null;
    notifyListeners();
  }

  Future<bool> pickAndSend() async {
    _syncOwner();
    final api = chat.api;
    final conversation = chat.active;
    if (busy || api == null || conversation == null || chat.session == null) {
      return false;
    }
    if (!chat.online) {
      error = '文件需要连接节点后上传；文字消息仍可离线发送';
      notifyListeners();
      return false;
    }
    if (platform.busy) {
      error = '请先完成当前文件或系统操作';
      notifyListeners();
      return false;
    }
    busy = true;
    error = null;
    completedBytes = 0;
    totalBytes = attachmentByteLimit;
    final operation = ++_operation;
    var ownsSelection = false;
    notifyListeners();
    try {
      await platform.pickFile(maxBytes: attachmentByteLimit);
      if (operation != _operation) return false;
      final selected = platform.selectedFile;
      // Cancellation/failure can retain a previous flow's selection.
      // Only this picker success authorizes uploading and releasing it.
      if (!platform.fileStatus.ok || selected == null) {
        if (platform.fileStatus.status != CapabilityStatus.cancelled) {
          error = capabilityMessage(platform.fileStatus.status);
        }
        return false;
      }
      ownsSelection = true;
      totalBytes = selected.size;
      final uploaded = await api.uploadAttachmentStream(
        conversationId: conversation.id,
        name: selected.name,
        mime: selected.mime,
        size: selected.size,
        readChunk: (offset, length) async {
          final read = await platform.readSelectedFileChunk(
            offset: offset,
            length: length,
          );
          if (!read.ok || read.value == null) {
            if (read.status == CapabilityStatus.cancelled) {
              throw const AttachmentCancelledException();
            }
            throw ApiException(capabilityMessage(read.status));
          }
          return read.value!;
        },
        cancelled: () => operation != _operation || !identical(chat.api, api),
        onProgress: (sent, total) {
          if (operation != _operation) return;
          completedBytes = sent;
          totalBytes = total;
          notifyListeners();
        },
      );
      if (operation != _operation || !identical(chat.api, api)) return false;
      return chat.sendAttachment(
        uploaded.attachment,
        conversationId: conversation.id,
      );
    } on AttachmentCancelledException {
      return false;
    } catch (failure) {
      if (operation == _operation) error = chat.describe(failure);
      return false;
    } finally {
      if (operation == _operation) {
        busy = false;
        completedBytes = 0;
        totalBytes = 0;
        if (ownsSelection) await platform.clearFile();
        notifyListeners();
      }
    }
  }

  AttachmentData? parse(ChatMessage message) {
    if (!{'file', 'image'}.contains(message.contentType)) return null;
    try {
      final attachment = AttachmentData.decode(message.content);
      if ((message.contentType == 'image') != attachment.image) return null;
      return attachment;
    } catch (_) {
      return null;
    }
  }

  Uint8List? previewBytes(ChatMessage message) {
    _syncOwner();
    final value = _downloads[message.key]?.bytes;
    return value == null ? null : Uint8List.fromList(value);
  }

  Future<bool> loadPreview(ChatMessage message) async {
    _syncOwner();
    final attachment = parse(message), api = chat.api;
    if (busy || attachment == null || !attachment.image || api == null) {
      return false;
    }
    final cached = _downloads[message.key];
    if (cached != null) {
      notifyListeners();
      return true;
    }
    return await _download(message, attachment, api) != null;
  }

  Future<bool> downloadAndShare(ChatMessage message) async {
    _syncOwner();
    final attachment = parse(message), api = chat.api;
    if (busy || attachment == null || api == null) return false;
    final downloaded =
        _downloads[message.key] ?? await _download(message, attachment, api);
    if (downloaded == null) return false;
    final result = await platform.shareDownloadedFile(
      name: downloaded.name,
      mime: downloaded.mime,
      bytes: downloaded.bytes,
    );
    if (!result.ok && result.status != CapabilityStatus.cancelled) {
      error = capabilityMessage(result.status);
      notifyListeners();
      return false;
    }
    return result.ok;
  }

  Future<DownloadedAttachment?> _download(
    ChatMessage message,
    AttachmentData attachment,
    MeshXApi api,
  ) async {
    busy = true;
    error = null;
    completedBytes = 0;
    totalBytes = attachment.size;
    final operation = ++_operation;
    notifyListeners();
    try {
      final downloaded = await api.downloadAttachment(
        attachment,
        cancelled: () => operation != _operation || !identical(chat.api, api),
        onProgress: (received, total) {
          if (operation != _operation) return;
          completedBytes = received;
          totalBytes = total > 0 ? total : attachment.size;
          notifyListeners();
        },
      );
      if (operation != _operation || !identical(chat.api, api)) return null;
      _downloads.remove(message.key);
      _downloads[message.key] = downloaded;
      var cachedBytes = _downloads.values.fold<int>(
        0,
        (total, item) => total + item.bytes.length,
      );
      while (_downloads.length > 4 || cachedBytes > 50 * 1024 * 1024) {
        final oldest = _downloads.keys.first;
        cachedBytes -= _downloads.remove(oldest)!.bytes.length;
      }
      return downloaded;
    } on AttachmentCancelledException {
      return null;
    } catch (failure) {
      if (operation == _operation) error = chat.describe(failure);
      return null;
    } finally {
      if (operation == _operation) {
        busy = false;
        completedBytes = 0;
        totalBytes = 0;
        notifyListeners();
      }
    }
  }

  @override
  void dispose() {
    ++_operation;
    _downloads.clear();
    super.dispose();
  }
}
