import 'platform_fakes.dart';
import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/core/models.dart';
import 'package:meshx_flutter_probe/core/store.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';
import 'package:meshx_flutter_probe/platform/storage.dart';
import 'recovery_test.dart'
    show
        ControlledApi,
        ControlledConnection,
        CapturingConnection,
        room,
        message,
        eventually;

class TestCredentials implements CredentialStore {
  Json? value;
  bool fail = false;
  @override
  Future<Json?> read() async => value;
  @override
  Future<void> write(Json credentials) async {
    if (fail) {
      throw PlatformException(code: 'STORAGE_UNAVAILABLE', message: '安全存储不可用');
    }
    value = Map.from(credentials);
  }

  @override
  Future<void> clear() async {
    value = null;
  }
}

class GatedStore implements ChatStore {
  final gate = Completer<void>();
  bool fail = false;
  @override
  Future<Json?> load(String owner) async => null;
  @override
  Future<void> save(String owner, Json snapshot) async {
    await gate.future;
    if (fail) throw const FileSystemException('disk unavailable');
  }
}

class GatedConnection extends CapturingConnection {
  GatedConnection(super.api);
  final sync = Completer<Json>();
  final requested = <Map<String, int>>[];
  @override
  Future<Json> synchronize(Map<String, int> positions) {
    requested.add(Map.from(positions));
    return sync.future;
  }
}

Json syncPage(
  List<ChatMessage> messages, {
  int latest = 0,
  bool more = false,
}) => {
  'messages': messages.map((m) => m.toJson()).toList(),
  'latestPositions': {room.id: latest},
  'deniedConversationIds': <String>[],
  'hasMore': more,
};

class PagedConnection extends CapturingConnection {
  PagedConnection(super.api);
  final requests = <Map<String, int>>[];
  @override
  Future<Json> synchronize(Map<String, int> positions) async {
    requests.add(Map.from(positions));
    if (requests.length == 1) {
      return syncPage([message(1)], latest: 4, more: true);
    }
    // Sequence 2 was physically deleted; authoritative sync safely crosses it.
    return syncPage([message(3), message(4)], latest: 4);
  }
}

class SlowRefreshApi extends ControlledApi {
  final refreshGate = Completer<Session>();
  @override
  Future<Session> refresh({String? rejectedToken}) {
    refreshCount++;
    return refreshGate.future;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('pure Dart receive cursor executes all shared sequence vectors', () {
    final fixture =
        jsonDecode(
              File('../../contracts/fixtures/core-v1.json').readAsStringSync(),
            )
            as Json;
    for (final vector in fixture['sequences'] as List) {
      expect(
        advanceContiguousSequence(
          vector['current'],
          (vector['candidates'] as List).cast<int>(),
        ),
        vector['expected'],
        reason: vector['id'],
      );
    }
    expect(
      [0, 1, 2, 3, 4, 5, 20].map((n) => reconnectDelay(n).inMilliseconds),
      [1000, 2000, 4000, 8000, 16000, 30000, 30000],
    );
    expect(reconnectDelay(0, randomFraction: .5).inMilliseconds, 1125);
  });

  test(
    'AUTH alone never enables send; paged authoritative sync persists its actual tail',
    () async {
      final api = ControlledApi(),
          connection = GatedConnection(ControlledApi());
      final c = ChatController(
        discovery: FakeDiscovery(),
        connectionFactory: (_) => connection,
      )..api = api;
      addTearDown(c.dispose);
      final connecting = c.reconnect();
      await eventually(() => connection.requested.isNotEmpty);
      expect(c.online, isFalse);
      c.active = room;
      expect(c.send('offline during synchronization'), isTrue);
      expect(connection.frames, isEmpty);
      connection.sync.complete(syncPage([message(1)], latest: 1));
      await connecting;
      expect(c.online, isTrue);
      expect(c.positions[room.id], 1);
      expect(connection.frames, hasLength(1));
    },
  );

  test(
    'hasMore advances only returned messages, not server latestPositions',
    () async {
      final api = ControlledApi();
      final wire = PagedConnection(api);
      final c = ChatController(
        discovery: FakeDiscovery(),
        connectionFactory: (_) => wire,
      )..api = api;
      addTearDown(c.dispose);
      await c.reconnect();
      c.active = room;
      expect(wire.requests, [
        {room.id: 0},
        {room.id: 1},
      ]);
      expect(c.positions[room.id], 4);
      expect(c.messages.map((m) => m.sequence), [1, 3, 4]);
    },
  );

  test(
    'disconnect during sync cannot publish ONLINE from a late response',
    () async {
      final api = ControlledApi(), wire = GatedConnection(ControlledApi());
      final c = ChatController(
        discovery: FakeDiscovery(),
        connectionFactory: (_) => wire,
      )..api = api;
      addTearDown(c.dispose);
      final connecting = c.reconnect();
      await eventually(() => wire.requested.isNotEmpty);
      wire.disconnected.add(null);
      await Future<void>.delayed(Duration.zero);
      wire.sync.complete(syncPage([message(1)], latest: 1));
      await connecting;
      expect(c.online, isFalse);
      expect(c.positions, isEmpty);
    },
  );

  test(
    'old async refresh cannot reconnect or erase a newly logged-in session',
    () async {
      final old = SlowRefreshApi();
      final c = ChatController(
        discovery: FakeDiscovery(),
        connectionFactory: (api) => ControlledConnection(
          api,
          identical(api, old) ? const ApiException('expired', code: 401) : null,
        ),
      )..api = old;
      addTearDown(c.dispose);
      final reconnecting = c.reconnect();
      await eventually(() => old.refreshCount == 1);
      await c.logout(revokeRemote: false);
      final next = ControlledApi();
      c.api = next;
      await c.reconnect();
      expect(c.online, isTrue);
      old.refreshGate.completeError(
        const ApiException('old refresh failed', code: 401),
      );
      await reconnecting;
      expect(c.api, same(next));
      expect(c.online, isTrue);
      expect(c.error, isNull);
    },
  );

  test(
    'durable acceptance precedes wire send and save failure is visible',
    () async {
      final api = ControlledApi(), gate = GatedStore();
      final wire = CapturingConnection(api);
      final c = ChatController(
        discovery: FakeDiscovery(),
        store: gate,
        connectionFactory: (_) => wire,
      )..api = api;
      addTearDown(c.dispose);
      final connecting = c.reconnect();
      // Complete startup persistence, then use a separate store failure test below.
      gate.gate.complete();
      await connecting;
      c.active = room;
      gate.fail = true;
      expect(c.send('must not leave the device'), isTrue);
      await eventually(() => c.error != null);
      expect(wire.frames, isEmpty);
      expect(c.messages.last.delivery, Delivery.failed);
    },
  );

  test(
    'restart restores outbox ID, messages and cursor from a real atomic file',
    () async {
      final dir = await Directory.systemTemp.createTemp('meshx-a05-store-');
      addTearDown(() => dir.delete(recursive: true));
      final storage = FileChatStore(directory: dir),
          credentials = TestCredentials();
      final firstApi = ControlledApi();
      final first = ChatController(
        discovery: FakeDiscovery(),
        store: storage,
        credentials: credentials,
        connectionFactory: (a) => CapturingConnection(a),
      )..api = firstApi;
      await credentials.write(firstApi.credentials());
      await first.reconnect();
      first.active = room;
      await first.pause();
      expect(first.send('queued across process recreation'), isTrue);
      final id = first.messages.last.clientMsgId;
      await storage.load(accountScope(firstApi.origin, 1));
      first.dispose();
      final secondWire = CapturingConnection(ControlledApi());
      final second = ChatController(
        discovery: FakeDiscovery(),
        store: FileChatStore(directory: dir),
        credentials: credentials,
        apiFactory: (_) => ControlledApi(),
        connectionFactory: (_) => secondWire,
      );
      addTearDown(second.dispose);
      await second.restore();
      second.active = room;
      expect(second.positions[room.id], 2);
      expect(
        second.messages.map((m) => m.content),
        contains('queued across process recreation'),
      );
      expect(secondWire.frames.single['clientMsgId'], id);
      secondWire.events.add({
        'event': 'CHAT_ACK',
        'conversationId': room.id,
        'clientMsgId': id,
        'payload': {'messageId': 'confirmed', 'sequence': 3},
      });
      await eventually(() => second.messages.last.delivery == Delivery.sent);
      await storage.load(accountScope(firstApi.origin, 1));
      final restored = await FileChatStore(
        directory: dir,
      ).load(accountScope(firstApi.origin, 1));
      expect(
        (restored!['messages'][room.id] as List).where(
          (m) => m['clientMsgId'] == id,
        ),
        hasLength(1),
      );
    },
  );

  test(
    'server and account snapshots never alias, even with identical message IDs',
    () async {
      final dir = await Directory.systemTemp.createTemp('meshx-a05-owners-');
      addTearDown(() => dir.delete(recursive: true));
      final storage = FileChatStore(directory: dir);
      final owners = [
        accountScope(Uri.parse('https://a.example'), 1),
        accountScope(Uri.parse('https://a.example'), 2),
        accountScope(Uri.parse('https://b.example'), 1),
      ];
      await Future.wait([
        for (var i = 0; i < owners.length; i++)
          storage.save(owners[i], {'message': i}),
      ]);
      for (var i = 0; i < owners.length; i++) {
        expect(await storage.load(owners[i]), {'message': i});
      }
      expect(
        await storage.load(
          accountScope(Uri.parse('https://a.example:8443'), 1),
        ),
        isNull,
      );
    },
  );

  test(
    'secure storage failure rejects login and never writes credentials to chat files',
    () async {
      final dir = await Directory.systemTemp.createTemp(
        'meshx-a05-no-secrets-',
      );
      addTearDown(() => dir.delete(recursive: true));
      final c = ChatController(
        discovery: FakeDiscovery(),
        store: FileChatStore(directory: dir),
        credentials: TestCredentials()..fail = true,
        apiFactory: (_) => ControlledApi(),
      );
      addTearDown(c.dispose);
      await c.login('http://127.0.0.1', 'fixture', 'fixture');
      expect(c.session, isNull);
      expect(c.error, '安全存储不可用');
      expect(await dir.list().toList(), isEmpty);
    },
  );

  test(
    'native credential failure propagates without a memory or plaintext fallback',
    () async {
      const channel = MethodChannel('com.meshx.mobile/storage');
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            channel,
            (_) async => throw PlatformException(code: 'STORAGE_UNAVAILABLE'),
          );
      addTearDown(
        () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(channel, null),
      );
      final storage = NativeCredentialStore();
      await expectLater(storage.read(), throwsA(isA<PlatformException>()));
      await expectLater(
        storage.write({'token': 'unit-fixture'}),
        throwsA(isA<PlatformException>()),
      );
    },
  );
}
