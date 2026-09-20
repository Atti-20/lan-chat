import 'dart:async';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/application/direct_transfer_controller.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/core/models.dart';
import 'package:meshx_flutter_probe/core/peer_port.dart';
import 'package:meshx_flutter_probe/platform/transfer_storage.dart';
import 'mobile_completion_test.dart' show CompletionApi;
import 'recovery_test.dart' show CapturingConnection, room, eventually;

class Peer implements PeerPort, PeerLink {
  final events = StreamController<Object>.broadcast();
  final sent = <Object>[];
  bool closed = false;
  Completer<PeerLink>? createGate;
  @override
  Future<PeerLink> create() async =>
      createGate == null ? this : createGate!.future;
  @override
  Stream<Object> get messages => events.stream;
  @override
  Future<String> offer() async => 'test-offer';
  @override
  Future<String> answer(String offer) async => 'test-answer';
  @override
  Future<void> acceptAnswer(String answer) async {}
  @override
  Future<void> send(Object value) async {
    sent.add(value);
  }

  @override
  Future<void> close() async {
    closed = true;
  }
}

void main() {
  const serverId = '0123456789abcdef0123456789abcdef';
  late ChatController chat;
  late CapturingConnection wire;
  late Peer peer;
  late FileTransferStore store;
  late DirectTransferController direct;
  late Directory directory;
  setUp(() async {
    directory = await Directory.systemTemp.createTemp('meshx-peer-test-');
    store = FileTransferStore(directory: directory);
    final api = CompletionApi();
    wire = CapturingConnection(api);
    peer = Peer();
    chat = ChatController(connectionFactory: (_) => wire)..api = api;
    await chat.reconnect();
    chat.active = room;
    direct = DirectTransferController(chat: chat, peers: peer, store: store);
  });
  tearDown(() async {
    direct.dispose();
    chat.dispose();
    await peer.events.close();
    await Future<void>.delayed(const Duration(milliseconds: 20));
    await directory.delete(recursive: true);
  });
  Future<Json> stage() => store.stage(direct.owner!, {
    'clientUploadId': 'local-upload-1234567890',
    'clientMsgId': 'msg-1234567890123456',
    'conversationId': room.id,
    'fileName': 'test.bin',
    'fileSize': 4,
    'fileType': 'application/octet-stream',
  }, Stream.value([1, 2, 3, 4]));
  void event(String name, Json payload) => wire.events.add({
    'event': name,
    'conversationId': room.id,
    'payload': {'transferId': serverId, ...payload},
  });
  test(
    'sender retains verified bytes and waits for authoritative receiver completion',
    () async {
      final task = await stage();
      var done = false;
      final result = direct.send(task, cancelled: () => false).then((v) {
        done = true;
        return v;
      });
      await eventually(
        () => wire.frames.any((f) => f['event'] == 'FILE_TRANSFER_OFFER'),
      );
      event('FILE_TRANSFER_READY', {
        'clientTransferId': task['clientUploadId'],
      });
      event('FILE_TRANSFER_ANSWER', {'sdp': 'test-answer'});
      await eventually(() => peer.sent.length == 2);
      expect(done, false);
      expect(peer.sent.first, [1, 2, 3, 4]);
      event('FILE_TRANSFER_COMPLETE', {
        'fileHash': task['fileHash'],
        'fileSize': 4,
      });
      final attachment = await result;
      expect(attachment.transferPath, 'PEER_TO_PEER');
      expect(attachment.transferId, serverId);
      final saved = (await store.list(direct.owner!)).single;
      expect(saved['kind'], 'direct');
      expect(
        await store.read(direct.owner!, task['clientUploadId'] as String, 0, 4),
        [1, 2, 3, 4],
      );
    },
  );
  test(
    'mismatching completion fails and preserves node-fallback copy',
    () async {
      final task = await stage();
      final result = direct.send(task, cancelled: () => false);
      final rejected = expectLater(result, throwsException);
      await eventually(() => wire.frames.isNotEmpty);
      event('FILE_TRANSFER_READY', {
        'clientTransferId': task['clientUploadId'],
      });
      event('FILE_TRANSFER_COMPLETE', {'fileHash': '0' * 64, 'fileSize': 4});
      await rejected;
      expect((await store.list(direct.owner!)).single['kind'], isNull);
      expect(wire.frames.last['event'], 'FILE_TRANSFER_FALLBACK');
    },
  );
  test(
    'incoming hash mismatch cannot acknowledge completion or retain data',
    () async {
      event('FILE_TRANSFER_OFFER', {
        'name': 'test.bin',
        'mime': 'application/octet-stream',
        'size': 4,
        'fileHash': sha256.convert([0, 0, 0, 0]).toString(),
        'sdp': 'test-offer',
      });
      await eventually(
        () => wire.frames.any((f) => f['event'] == 'FILE_TRANSFER_ANSWER'),
      );
      peer.events.add([1, 2, 3, 4]);
      peer.events.add('{"type":"complete"}');
      await eventually(
        () => wire.frames.any((f) => f['event'] == 'FILE_TRANSFER_FAILED'),
      );
      await Future<void>.delayed(const Duration(milliseconds: 30));
      expect(await store.list(direct.owner!), isEmpty);
      expect(
        wire.frames.where((f) => f['event'] == 'FILE_TRANSFER_COMPLETE'),
        isEmpty,
      );
    },
  );
  test(
    'account or connection change while peer opens rejects old offer',
    () async {
      peer.createGate = Completer<PeerLink>();
      event('FILE_TRANSFER_OFFER', {
        'name': 'test.bin',
        'mime': 'application/octet-stream',
        'size': 4,
        'fileHash': '0' * 64,
        'sdp': 'test-offer',
      });
      await Future<void>.delayed(Duration.zero);
      await chat.pause();
      peer.createGate!.complete(peer);
      await eventually(() => peer.closed);
      expect(
        wire.frames.where((f) => f['event'] == 'FILE_TRANSFER_ANSWER'),
        isEmpty,
      );
      expect(await store.list(direct.owner!), isEmpty);
    },
  );
}
