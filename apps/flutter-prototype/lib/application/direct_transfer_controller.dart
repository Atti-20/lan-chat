import 'dart:async';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';
import '../chat_controller.dart';
import '../core/models.dart';
import '../core/peer_port.dart';
import '../core/transfer_store.dart';
import '../data/attachments_models.dart';
import '../data/meshx_api.dart';

class _Transfer {
  _Transfer(
    this.id,
    this.scope,
    this.owner,
    this.task,
    this.link, {
    this.outgoing = false,
    this.onProgress,
  });
  String id;
  final String scope, owner;
  final Json task;
  final PeerLink link;
  final bool outgoing;
  final void Function(int, int)? onProgress;
  final done = Completer<AttachmentData>();
  StreamController<List<int>>? bytes;
  Future<Json>? staged;
  Timer? timer;
  int received = 0;
  bool streaming = false, completed = false;
  String get conversation => task['conversationId'] as String;
}

class DirectTransferController {
  DirectTransferController({
    required this.chat,
    required this.peers,
    required this.store,
  }) {
    subscription = chat.transferEvents.listen((event) {
      _background(handle(event));
    });
    invalidations = chat.invalidations.listen((event) {
      _background(purge(event.conversationId, event.messageId));
    });
    chat.addListener(changed);
  }
  void _background(Future<void> operation) {
    unawaited(
      operation.catchError((Object failure) {
        if (!disposed) chat.reportFailure(failure);
      }),
    );
  }

  final ChatController chat;
  final PeerPort peers;
  final TransferStore store;
  late final StreamSubscription<Json> subscription;
  late final StreamSubscription<Object?> invalidations;
  final transfers = <String, _Transfer>{};
  bool disposed = false;
  String? get owner => chat.api == null || chat.session == null
      ? null
      : '${chat.api!.origin}|${chat.session!.userId}';
  bool _current(_Transfer t) =>
      !disposed &&
      chat.online &&
      t.owner == owner &&
      t.scope == chat.connectionScope &&
      chat.conversationAccessible(t.conversation);
  void _signal(_Transfer t, String event, Json payload) =>
      chat.sendTransferEvent(event, {
        'transferId': t.id,
        ...payload,
      }, t.conversation);
  void changed() {
    for (final t in transfers.values.toSet()) {
      if (!_current(t)) _background(_fail(t));
    }
  }

  Future<void> purge(String conversation, String? message) async {
    final scope = owner;
    for (final t in transfers.values.toSet()) {
      if (t.conversation == conversation) await _fail(t);
    }
    if (scope == null) return;
    for (final task in await store.list(scope)) {
      if (task['kind'] == 'direct' &&
          task['conversationId'] == conversation &&
          (message == null ||
              task['messageId'] == null ||
              task['messageId'] == message)) {
        await store.remove(scope, task['clientUploadId'] as String);
      }
    }
  }

  Future<AttachmentData> send(
    Json task, {
    required bool Function() cancelled,
    void Function(int, int)? onProgress,
  }) async {
    final target = chat.active, scope = owner;
    if (disposed ||
        scope == null ||
        target?.kind != 'private' ||
        !chat.online ||
        transfers.isNotEmpty) {
      throw const ApiException('当前无法设备直传');
    }
    final connection = chat.connectionScope;
    final link = await peers.create();
    if (disposed ||
        scope != owner ||
        connection != chat.connectionScope ||
        cancelled()) {
      await link.close();
      throw const AttachmentCancelledException();
    }
    final t = _Transfer(
      task['clientUploadId'] as String,
      chat.connectionScope,
      scope,
      Map.from(task),
      link,
      outgoing: true,
      onProgress: onProgress,
    );
    transfers[t.id] = t;
    // Observe errors before asynchronous SDP work; the awaited future below still reports failure.
    unawaited(
      t.done.future.then<void>((_) {}, onError: (Object _, StackTrace _) {}),
    );
    t.timer = Timer(const Duration(seconds: 20), () {
      _background(_fail(t));
    });
    try {
      final sdp = await link.offer();
      if (!_current(t) || cancelled()) {
        throw const AttachmentCancelledException();
      }
      _signal(t, 'FILE_TRANSFER_OFFER', {
        'toUserId': target!.targetId,
        'name': task['fileName'],
        'size': task['fileSize'],
        'mime': task['fileType'],
        'fileHash': task['fileHash'],
        'sdp': sdp,
      });
      final poll = Timer.periodic(const Duration(milliseconds: 100), (_) {
        if (cancelled() || !_current(t)) _background(_fail(t));
      });
      try {
        return await t.done.future;
      } finally {
        poll.cancel();
      }
    } catch (_) {
      await _fail(t);
      rethrow;
    }
  }

  Future<void> handle(Json event) async {
    if (disposed || !chat.online) return;
    final data = event['payload'] as Json? ?? {}, name = event['event'];
    final id = data['transferId'] as String? ?? '';
    if (!RegExp(r'^[a-f0-9]{32}$').hasMatch(id)) return;
    _Transfer? t = transfers[id];
    try {
      if (name == 'FILE_TRANSFER_READY') {
        t = transfers.remove(data['clientTransferId']);
        if (t == null || !_current(t)) return;
        t.id = id;
        transfers[id] = t;
      } else if (name == 'FILE_TRANSFER_OFFER') {
        if (transfers.isNotEmpty || owner == null) return;
        final cid = event['conversationId'] as String? ?? '',
            size = integer(data['size']);
        final hash = data['fileHash'] as String? ?? '',
            sdp = data['sdp'] as String? ?? '';
        if (!cid.startsWith('private:') ||
            !chat.conversationAccessible(cid) ||
            size <= 0 ||
            size > attachmentByteLimit ||
            !RegExp(r'^[a-f0-9]{64}$').hasMatch(hash) ||
            sdp.isEmpty ||
            sdp.length > 262144) {
          return;
        }
        final incomingOwner = owner!, connection = chat.connectionScope;
        final link = await peers.create();
        if (disposed ||
            !chat.online ||
            owner != incomingOwner ||
            connection != chat.connectionScope ||
            transfers.isNotEmpty) {
          await link.close();
          return;
        }
        t = _Transfer(id, chat.connectionScope, owner!, {
          'kind': 'direct',
          'clientUploadId': id,
          'transferId': id,
          'conversationId': cid,
          'fileName': data['name'],
          'fileType': data['mime'],
          'fileSize': size,
          'expectedHash': hash,
        }, link);
        transfers[id] = t;
        t.timer = Timer(const Duration(minutes: 10), () {
          _background(_fail(t!));
        });
        t.bytes = StreamController<List<int>>();
        t.staged = store.stage(t.owner, t.task, t.bytes!.stream);
        unawaited(
          t.staged!.then<void>((_) {}, onError: (Object _, StackTrace _) {}),
        );
        final incoming = t;
        link.messages.listen(
          (value) {
            _background(_receive(incoming, value));
          },
          onError: (_) {
            _background(_fail(incoming));
          },
        );
        final answer = await link.answer(sdp);
        if (_current(t)) {
          _signal(t, 'FILE_TRANSFER_ANSWER', {'sdp': answer});
        } else {
          await _fail(t);
        }
      } else if (t != null && _current(t)) {
        if (name == 'FILE_TRANSFER_ANSWER' && t.outgoing && !t.streaming) {
          t.streaming = true;
          t.timer?.cancel();
          final sending = t;
          t.timer = Timer(const Duration(minutes: 10), () {
            _background(_fail(sending));
          });
          await t.link.acceptAnswer(data['sdp'] as String);
          _signal(t, 'FILE_TRANSFER_STARTED', {});
          final size = t.task['fileSize'] as int;
          for (var offset = 0; offset < size; offset += 65536) {
            if (!_current(t) || !transfers.containsKey(t.id)) {
              throw const AttachmentCancelledException();
            }
            final bytes = await store.read(
              t.owner,
              t.task['clientUploadId'] as String,
              offset,
              (size - offset).clamp(0, 65536),
            );
            if (bytes.isEmpty) throw const FormatException('直传副本不完整');
            await t.link.send(bytes);
            t.onProgress?.call(offset + bytes.length, size);
          }
          await t.link.send('{"type":"complete"}');
        } else if (name == 'FILE_TRANSFER_COMPLETE' && t.outgoing) {
          if (data['fileHash'] != t.task['fileHash'] ||
              integer(data['fileSize']) != t.task['fileSize']) {
            throw const FormatException('直传确认不匹配');
          }
          final saved = {...t.task, 'kind': 'direct', 'transferId': t.id};
          await store.update(t.owner, saved);
          if (!_current(t)) throw const AttachmentCancelledException();
          if (!t.done.isCompleted) {
            t.done.complete(
              AttachmentData(
                url: '',
                name: t.task['fileName'] as String,
                size: t.task['fileSize'] as int,
                mime: t.task['fileType'] as String,
                fileHash: t.task['fileHash'] as String,
                transferPath: 'PEER_TO_PEER',
                transferId: t.id,
              ),
            );
          }
          await _cleanup(t);
        } else if ({
          'FILE_TRANSFER_FAILED',
          'FILE_TRANSFER_CANCELED',
          'FILE_TRANSFER_REJECTED',
        }.contains(name)) {
          await _fail(t);
        }
      }
    } catch (_) {
      if (t != null) await _fail(t);
    }
  }

  Future<void> _receive(_Transfer t, Object value) async {
    if (!_current(t) || t.completed || !transfers.containsKey(t.id)) return;
    if (value is List<int>) {
      t.received += value.length;
      if (t.received > t.task['fileSize']) {
        await _fail(t);
        return;
      }
      t.bytes!.add(value);
    } else if (value == '{"type":"complete"}') {
      t.completed = true;
      try {
        await t.bytes!.close();
        final saved = await t.staged!;
        if (!_current(t) || saved['fileHash'] != t.task['expectedHash']) {
          throw const FormatException('直传校验失败');
        }
        _signal(t, 'FILE_TRANSFER_COMPLETE', {
          'fileHash': saved['fileHash'],
          'fileSize': saved['fileSize'],
        });
        await _cleanup(t);
      } catch (_) {
        await _fail(t);
      }
    }
  }

  Future<void> _cleanup(_Transfer t) async {
    transfers.remove(t.id);
    t.timer?.cancel();
    await t.link.close();
  }

  Future<void> _fail(_Transfer t) async {
    if (transfers.remove(t.id) == null) return;
    try {
      if (_current(t)) {
        _signal(
          t,
          t.outgoing ? 'FILE_TRANSFER_FALLBACK' : 'FILE_TRANSFER_FAILED',
          {'reason': 'MOBILE_TRANSFER_INTERRUPTED'},
        );
      }
    } catch (_) {}
    if (t.outgoing && !t.done.isCompleted) {
      t.done.completeError(const ApiException('直传未完成，改用节点续传'));
    }
    if (t.bytes != null && !t.bytes!.isClosed) {
      t.bytes!.addError(const AttachmentCancelledException());
      unawaited(t.bytes!.close());
    }
    if (!t.outgoing) {
      try {
        await t.staged;
      } catch (_) {}
      try {
        await store.remove(t.owner, t.task['clientUploadId'] as String);
      } finally {
        await _cleanup(t);
      }
    } else {
      await _cleanup(t);
    }
  }

  Future<DownloadedAttachment> read(
    ChatMessage message,
    AttachmentData attachment,
  ) async {
    final scope = owner;
    if (scope == null || !chat.allowsMessage(message)) {
      throw const ApiException('消息不可访问');
    }
    final tasks = await store.list(scope);
    final task = tasks
        .where(
          (t) =>
              t['kind'] == 'direct' &&
              t['transferId'] == attachment.transferId &&
              t['conversationId'] == message.conversationId,
        )
        .firstOrNull;
    if (task == null) throw const ApiException('此设备没有直传副本，请让发送者通过节点重新发送');
    final output = BytesBuilder(copy: false);
    for (var offset = 0; offset < attachment.size; offset += 1024 * 1024) {
      if (scope != owner || !chat.allowsMessage(message)) {
        throw const AttachmentCancelledException();
      }
      output.add(
        await store.read(
          scope,
          task['clientUploadId'] as String,
          offset,
          (attachment.size - offset).clamp(0, 1024 * 1024),
        ),
      );
    }
    final bytes = output.takeBytes();
    if (bytes.length != attachment.size ||
        sha256.convert(bytes).toString() != attachment.fileHash) {
      throw const FormatException('直传副本校验失败');
    }
    await store.update(scope, {...task, 'messageId': message.messageId});
    return DownloadedAttachment(
      bytes: bytes,
      mime: attachment.mime,
      name: attachment.name,
    );
  }

  void dispose() {
    disposed = true;
    chat.removeListener(changed);
    unawaited(subscription.cancel());
    unawaited(invalidations.cancel());
    for (final t in transfers.values.toSet()) {
      _background(_fail(t));
    }
  }
}
