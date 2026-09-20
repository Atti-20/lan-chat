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
  AttachmentController({required this.chat, required this.platform}) {
    _invalidations = chat.invalidations.listen((event) {
      if (event.messageId == null) {
        _downloads.clear();
      } else {
        _downloads.remove(event.messageId);
      }
      cancel();
      if (event.messageId == null &&
          platform.transfers != null &&
          owner != null) {
        final scope = owner!;
        unawaited(
          () async {
            for (final task in await platform.transfers!.list(scope)) {
              if (task['conversationId'] == event.conversationId) {
                await platform.transfers!.remove(
                  scope,
                  task['clientUploadId'] as String,
                );
              }
            }
            await refreshPending();
          }().catchError((Object failure) {
            error = chat.describe(failure);
          }),
        );
      }
      notifyListeners();
    });
  }
  late final StreamSubscription<Object?> _invalidations;
  Future<void> refreshPending() async {
    final scope = owner;
    if (scope == null || platform.transfers == null) return;
    try {
      final tasks = await platform.transfers!.list(scope);
      if (owner != scope || _disposed) return;
      pending = tasks
          .where(
            (t) =>
                t['kind'] != 'direct' && t['conversationId'] == chat.active?.id,
          )
          .toList();
      notifyListeners();
    } catch (failure) {
      if (!_disposed) {
        error = chat.describe(failure);
        notifyListeners();
      }
    }
  }

  bool _disposed = false;
  Future<bool> resumePending(Json task) async {
    _syncOwner();
    final api = chat.api, scope = owner, storage = platform.transfers;
    if (busy ||
        api == null ||
        scope == null ||
        storage == null ||
        !chat.online ||
        !chat.canSendInActiveConversation ||
        task['conversationId'] != chat.active?.id ||
        task['owner'] != scope) {
      return false;
    }
    busy = true;
    error = null;
    final operation = ++_operation;
    totalBytes = task['fileSize'] as int;
    notifyListeners();
    try {
      return await _transmitTask(task, operation, scope, api);
    } catch (failure) {
      if (!_disposed && operation == _operation) error = chat.describe(failure);
      return false;
    } finally {
      if (!_disposed && operation == _operation) {
        busy = false;
        completedBytes = 0;
        totalBytes = 0;
        await refreshPending();
        notifyListeners();
      }
    }
  }

  Future<void> discardPending(Json task) async {
    final scope = owner;
    if (busy || scope == null || task['owner'] != scope) return;
    await platform.transfers?.remove(scope, task['clientUploadId'] as String);
    await refreshPending();
  }

  Future<bool> _transmitTask(
    Json task,
    int operation,
    String scope,
    MeshXApi api,
  ) async {
    final storage = platform.transfers!, id = task['clientUploadId'] as String;
    bool cancelled() =>
        _disposed ||
        operation != _operation ||
        owner != scope ||
        chat.active?.id != task['conversationId'] ||
        !chat.canSendInActiveConversation ||
        !chat.online;
    final uploaded = await api.resumeAttachment(
      task: task,
      readChunk: (offset, length) => storage.read(scope, id, offset, length),
      cancelled: cancelled,
      onProgress: (sent, total) {
        if (!cancelled()) {
          completedBytes = sent;
          totalBytes = total;
          notifyListeners();
        }
      },
    );
    if (cancelled()) return false;
    final accepted = await chat.queueTransferredAttachment(
      uploaded.attachment,
      conversationId: task['conversationId'] as String,
      clientMsgId: task['clientMsgId'] as String,
    );
    if (accepted) await storage.remove(scope, id);
    return accepted;
  }

  final ChatController chat;
  final PlatformCoordinator platform;
  bool busy = false;
  bool preparing = false;
  int completedBytes = 0, totalBytes = 0;
  String? error;
  List<Json> pending = [];
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
    preparing = false;
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

  Future<bool> pickAndSend({bool photos = false, bool direct = false}) async {
    _syncOwner();
    final api = chat.api;
    final conversation = chat.active;
    if (busy || api == null || conversation == null || chat.session == null) {
      return false;
    }
    if (!chat.canUploadAttachment) {
      error = '当前会话未允许上传文件';
      notifyListeners();
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
    preparing = true;
    error = null;
    completedBytes = 0;
    totalBytes = 0;
    final operation = ++_operation;
    var ownsSelection = false;
    notifyListeners();
    try {
      await platform.pickFile(maxBytes: attachmentByteLimit, photos: photos);
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
      preparing = false;
      totalBytes = selected.size;
      notifyListeners();
      final storage = platform.transfers;
      if (storage != null) {
        final scope = owner!;
        final task = await storage.stage(
          scope,
          {
            'clientUploadId': requestId(),
            'clientMsgId': requestId(),
            'conversationId': conversation.id,
            'fileName': selected.name,
            'fileSize': selected.size,
            'fileType': selected.mime,
          },
          () async* {
            for (
              var offset = 0;
              offset < selected.size;
              offset += 1024 * 1024
            ) {
              if (operation != _operation || owner != scope) {
                throw const AttachmentCancelledException();
              }
              final length = (selected.size - offset).clamp(0, 1024 * 1024);
              final chunk = await platform.readSelectedFileChunk(
                offset: offset,
                length: length,
              );
              if (!chunk.ok ||
                  chunk.value == null ||
                  chunk.value!.length != length) {
                throw const ApiException('无法保存附件副本');
              }
              yield chunk.value!;
            }
          }(),
        );
        if (operation != _operation || owner != scope) return false;
        if (direct &&
            conversation.kind == 'private' &&
            platform.direct != null) {
          try {
            final attachment = await platform.direct!.send(
              task,
              cancelled: () =>
                  operation != _operation ||
                  owner != scope ||
                  chat.active?.id != conversation.id,
              onProgress: (sent, total) {
                if (operation == _operation && !_disposed) {
                  completedBytes = sent;
                  totalBytes = total;
                  notifyListeners();
                }
              },
            );
            if (operation != _operation || owner != scope) return false;
            return await chat.queueTransferredAttachment(
              attachment,
              conversationId: conversation.id,
              clientMsgId: task['clientMsgId'] as String,
            );
          } catch (_) {
            if (operation != _operation || owner != scope || !chat.online) {
              return false;
            }
            await storage.update(scope, {...task, 'kind': 'upload'});
            error = '直传未完成，正在通过节点续传';
            notifyListeners();
          }
        }
        return await _transmitTask(task, operation, scope, api);
      }
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
        cancelled: () =>
            operation != _operation ||
            !identical(chat.api, api) ||
            chat.active?.id != conversation.id ||
            !chat.canSendInActiveConversation,
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
        preparing = false;
        completedBytes = 0;
        totalBytes = 0;
        if (ownsSelection) await platform.clearFile();
        await refreshPending();
        notifyListeners();
      }
    }
  }

  AttachmentData? parse(ChatMessage message) {
    if (!chat.canDownloadAttachment(message) ||
        !{'file', 'image'}.contains(message.contentType)) {
      return null;
    }
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
    if (!chat.canDownloadAttachment(message)) {
      _downloads.remove(message.key);
      return null;
    }
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
    if (downloaded == null || !chat.canDownloadAttachment(message)) {
      return false;
    }
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
      final downloaded = attachment.transferPath == 'PEER_TO_PEER'
          ? await (platform.direct?.read(message, attachment) ??
                Future<DownloadedAttachment>.error(
                  const ApiException('此设备没有直传副本'),
                ))
          : await api.downloadAttachment(
              attachment,
              cancelled: () =>
                  operation != _operation ||
                  !identical(chat.api, api) ||
                  !chat.canDownloadAttachment(message),
              onProgress: (received, total) {
                if (operation != _operation) return;
                completedBytes = received;
                totalBytes = total > 0 ? total : attachment.size;
                notifyListeners();
              },
            );
      if (operation != _operation ||
          !identical(chat.api, api) ||
          !chat.canDownloadAttachment(message)) {
        return null;
      }
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
    _disposed = true;
    unawaited(_invalidations.cancel());
    ++_operation;
    _downloads.clear();
    super.dispose();
  }
}
