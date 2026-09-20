import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/core/models.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';
import 'package:meshx_flutter_probe/platform/transfer_storage.dart';
import 'package:meshx_flutter_probe/application/broadcasts_controller.dart';
import 'package:meshx_flutter_probe/application/platform_coordinator.dart';
import 'package:meshx_flutter_probe/data/broadcast_models.dart';
import 'package:meshx_flutter_probe/platform/storage.dart';
import 'broadcasts_feature_test.dart' show FakeBroadcastApi, detailJson;
import 'platform_fakes.dart';
import 'mobile_reliability_test.dart' show TestCredentials;
import 'recovery_controller_test.dart' show ControllerRecoveryTransport;
import 'recovery_test.dart' show CapturingConnection, room, eventually;
import 'mobile_completion_test.dart' show CompletionApi;

class BurnWire extends CapturingConnection {
  BurnWire(super.api);
  final confirmation = Completer<Json>();
  @override
  Future<Json> command(
    String event,
    Json payload, {
    required String conversationId,
  }) {
    send(event, payload, conversationId: conversationId);
    return confirmation.future;
  }
}

ChatMessage burnMessage() => ChatMessage(
  messageId: 'burn-12345678',
  clientMsgId: 'burn-client-1234',
  conversationId: room.id,
  fromUserId: 2,
  content: 'private ephemeral text',
  contentType: 'text',
  sequence: 3,
  createdAt: DateTime.now(),
  isBurn: true,
  burnDuration: 5,
);

class LocatedSystem extends FakeSystem implements LocationPort {
  @override
  Future<CapabilityResult<LocationProof>> currentLocation() async =>
      CapabilityResult(
        CapabilityStatus.success,
        value: LocationProof(31, 121, 8, DateTime.now()),
      );
}

class LocatedApi extends FakeBroadcastApi {
  Map<String, dynamic>? submittedLocation;
  @override
  Future<BroadcastReceiverState> completeBroadcast(
    int id, {
    List<int> imageFileIds = const [],
    Map<String, dynamic>? location,
  }) async {
    submittedLocation = location;
    return super.completeBroadcast(
      id,
      imageFileIds: imageFileIds,
      location: location,
    );
  }
}

class BurnRecovery extends ControllerRecoveryTransport {
  @override
  Future<Object?> snapshot(String id, String? token, int limit) async {
    final result =
        await super.snapshot(id, token, limit) as Map<String, dynamic>;
    for (final row in result['items'] as List) {
      if (row['details'] is Map) row['details']['isBurn'] = 1;
    }
    return result;
  }
}

void main() {
  test(
    'burn text is revealed only after terminal server confirmation',
    () async {
      final api = CompletionApi(), wire = BurnWire(CompletionApi());
      final chat = ChatController(connectionFactory: (_) => wire)..api = api;
      addTearDown(chat.dispose);
      await chat.reconnect();
      chat.active = room;
      final m = burnMessage();
      wire.events.add({'event': 'CHAT_DELIVER', 'payload': m.toJson()});
      await eventually(
        () => chat.messages.any((v) => v.messageId == m.messageId),
      );
      var revealed = false;
      final read = chat.consumeBurn(m).then((value) {
        revealed = value != null;
        return value;
      });
      await Future<void>.delayed(Duration.zero);
      expect(revealed, false);
      wire.confirmation.complete({
        'event': 'CHAT_BURN',
        'conversationId': room.id,
        'payload': {'messageId': m.messageId},
      });
      expect(await read, m.content);
      expect(chat.messages.last.content, isEmpty);
      expect(chat.messages.last.burned, true);
      expect(await chat.consumeBurn(m), isNull);
    },
  );
  test('late burn confirmation after leaving never reveals text', () async {
    final api = CompletionApi(), wire = BurnWire(CompletionApi());
    final chat = ChatController(connectionFactory: (_) => wire)..api = api;
    addTearDown(chat.dispose);
    await chat.reconnect();
    chat.active = room;
    final m = burnMessage();
    final read = chat.consumeBurn(m);
    chat.leaveConversation();
    wire.confirmation.complete({
      'event': 'CHAT_BURN',
      'conversationId': room.id,
      'payload': {'messageId': m.messageId},
    });
    expect(await read, isNull);
  });
  test(
    'typing is throttled, stopped, and independent of message delivery',
    () async {
      final wire = CapturingConnection(CompletionApi());
      final chat = ChatController(connectionFactory: (_) => wire)
        ..api = CompletionApi();
      addTearDown(chat.dispose);
      await chat.reconnect();
      chat.active = room;
      chat.sendTyping(false);
      expect(wire.frames, isEmpty);
      chat.sendTyping(true);
      chat.sendTyping(true);
      expect(wire.frames.length, 1);
      chat.leaveConversation();
      expect(wire.frames.last['event'], 'TYPING_STOP');
      chat.active = room;
      wire.events.add({
        'event': 'TYPING_START',
        'conversationId': room.id,
        'payload': {'userId': 2},
      });
      await eventually(() => chat.typingLabel.isNotEmpty);
      wire.events.add({
        'event': 'TYPING_STOP',
        'conversationId': room.id,
        'payload': {'userId': 2},
      });
      await eventually(() => chat.typingLabel.isEmpty);
    },
  );
  test(
    'retained upload survives a store restart and remains account partitioned',
    () async {
      final root = await Directory.systemTemp.createTemp('meshx-transfers-');
      addTearDown(() => root.delete(recursive: true));
      final first = FileTransferStore(directory: root);
      final task = await first.stage(
        'https://node|1',
        {
          'clientUploadId': 'upload-1234567890',
          'fileSize': 4,
          'conversationId': room.id,
        },
        Stream.fromIterable([
          [1, 2],
          [3, 4],
        ]),
      );
      final second = FileTransferStore(directory: root);
      expect(await second.list('https://node|2'), isEmpty);
      expect((await second.list('https://node|1')).single, task);
      expect(
        await second.read(
          'https://node|1',
          task['clientUploadId'] as String,
          2,
          2,
        ),
        [3, 4],
      );
      expect(task['fileHash'], sha256.convert([1, 2, 3, 4]).toString());
      await second.remove('https://node|1', task['clientUploadId'] as String);
      expect(await second.list('https://node|1'), isEmpty);
    },
  );
  test(
    'resumable HTTP uploads only missing parts with a stable upload identity',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final requests = <String>[];
      final bytes = [1, 2, 3, 4];
      final hash = sha256.convert(bytes).toString();
      const id = 'upload-1234567890';
      final api = MeshXApi(Uri.parse('http://127.0.0.1:${server.port}'));
      api.node = NodeInfo(api.origin, 'test', '', '/api/v1', '/ws/chat');
      api.session = const Session(1, 'test', 'synthetic');
      addTearDown(() async {
        api.close();
        await server.close(force: true);
      });
      server.listen((request) async {
        requests.add('${request.method} ${request.uri.path}');
        Object? data;
        if (request.uri.path.endsWith('/uploads')) {
          final body = jsonDecode(await utf8.decoder.bind(request).join());
          expect(body['clientUploadId'], id);
          expect(body['fileHash'], hash);
          data = {
            'uploadId': id,
            'status': 'UPLOADING',
            'chunkSize': 2,
            'totalParts': 2,
            'uploadedParts': [1],
          };
        } else if (request.method == 'PUT') {
          expect(
            await request.fold<List<int>>([], (all, part) => all..addAll(part)),
            [3, 4],
          );
          expect(
            request.uri.queryParameters['sha256'],
            sha256.convert([3, 4]).toString(),
          );
          data = {};
        } else {
          const name = '0123456789abcdef0123456789abcdef.bin';
          data = {
            'url': '/api/v1/file/content/$name',
            'fileName': name,
            'originalName': 'test.bin',
            'fileType': 'application/octet-stream',
            'fileHash': hash,
            'fileSize': 4,
          };
        }
        request.response.headers.contentType = ContentType.json;
        request.response.write(jsonEncode({'code': 200, 'data': data}));
        await request.response.close();
      });
      final task = {
        'clientUploadId': id,
        'conversationId': room.id,
        'fileName': 'test.bin',
        'fileSize': 4,
        'fileType': 'application/octet-stream',
        'fileHash': hash,
      };
      final result = await api.resumeAttachment(
        task: task,
        readChunk: (offset, length) async =>
            bytes.sublist(offset, offset + length),
        cancelled: () => false,
      );
      expect(result.attachment.fileHash, hash);
      expect(requests, [
        'POST /api/v1/file/uploads',
        'PUT /api/v1/file/uploads/$id/parts/2',
        'POST /api/v1/file/uploads/$id/complete',
      ]);
      final count = requests.length;
      await expectLater(
        api.resumeAttachment(
          task: task,
          readChunk: (offset, length) async => List.filled(length, 0),
          cancelled: () => false,
        ),
        throwsFormatException,
      );
      expect(requests.length, count);
    },
  );
  test('location proof rejects stale, future and invalid coordinates', () {
    expect(LocationProof(31, 121, 10, DateTime.now()).fresh, true);
    expect(
      LocationProof(
        31,
        121,
        10,
        DateTime.now().subtract(const Duration(minutes: 2)),
      ).fresh,
      false,
    );
    expect(LocationProof(91, 121, 10, DateTime.now()).fresh, false);
    expect(
      LocationProof(
        31,
        121,
        10,
        DateTime.now().add(const Duration(minutes: 1)),
      ).fresh,
      false,
    );
  });
  test(
    'location-required completion submits fresh OS proof to the node',
    () async {
      final api = LocatedApi()
        ..current = BroadcastDetail.fromJson(
          detailJson(location: true, viewed: true),
        );
      final chat = ChatController()..api = api;
      final system = LocatedSystem();
      final platform = PlatformCoordinator(
        chat: chat,
        lifecycle: FakeLifecycle(),
        notifications: system,
        files: system,
        sharePort: system,
        network: system,
        settings: system,
        location: system,
      );
      final broadcasts = BroadcastsController(chat: chat, platform: platform);
      addTearDown(() {
        broadcasts.dispose();
        platform.dispose();
        chat.dispose();
      });
      await broadcasts.open(7);
      expect(await broadcasts.complete(), true);
      expect(api.submittedLocation?['latitude'], 31);
      expect(api.submittedLocation?['longitude'], 121);
      expect(api.submittedLocation?['accuracyMeters'], 8);
      expect(api.completes, 1);
    },
  );
  test(
    'recovery drafts persist separately from verified history across restart',
    () async {
      final directory = await Directory.systemTemp.createTemp(
        'meshx-drafts-test-',
      );
      final storage = FileChatStore(directory: directory);
      final credentials = TestCredentials();
      final api = CompletionApi();
      await credentials.write(api.credentials());
      ChatController make() => ChatController(
        discovery: FakeDiscovery(),
        store: storage,
        draftStore: storage,
        credentials: credentials,
        enableRecovery: true,
        apiFactory: (_) => CompletionApi(),
        connectionFactory: (api) => CapturingConnection(api),
        recoveryTransportFactory: (_) => ControllerRecoveryTransport(),
      );
      final first = make();
      await first.restore();
      await first.pause();
      first.updateDraft(room.id, 'recovery draft');
      await Future<void>.delayed(const Duration(milliseconds: 350));
      first.dispose();
      final second = make();
      addTearDown(() async {
        second.dispose();
        await Future<void>.delayed(const Duration(milliseconds: 50));
        await directory.delete(recursive: true);
      });
      await second.restore();
      expect(second.drafts[room.id], 'recovery draft');
      expect(
        (await storage.loadRecovery('http://127.0.0.1|1'))!.snapshot.toString(),
        isNot(contains('recovery draft')),
      );
    },
  );

  test('burn reveal in recovery waits for durable burned proof', () async {
    final root = await Directory.systemTemp.createTemp('meshx-burn-proof-');
    final api = CompletionApi(), transport = BurnRecovery();
    final wire = BurnWire(api);
    final chat = ChatController(
      discovery: FakeDiscovery(),
      store: FileChatStore(directory: root),
      enableRecovery: true,
      recoveryTransportFactory: (_) => transport,
      connectionFactory: (_) => wire,
      apiFactory: (_) => api,
    );
    addTearDown(() async {
      chat.dispose();
      await Future<void>.delayed(const Duration(milliseconds: 50));
      await root.delete(recursive: true);
    });
    await chat.login('http://127.0.0.1', 'user', 'password');
    expect(chat.online, true, reason: chat.error);
    chat.active = room;
    final m = chat.messages.single;
    final reveal = chat.consumeBurn(m);
    transport.terminal = 'BURNED';
    final event = {
      'event': 'CHAT_BURN',
      'conversationId': room.id,
      'payload': {'messageId': m.messageId},
    };
    wire.confirmation.complete(event);
    wire.events.add(event);
    expect(await reveal, 'verified body');
    expect(chat.recoveryTerminals.single.state, 'BURNED');
  });
}
