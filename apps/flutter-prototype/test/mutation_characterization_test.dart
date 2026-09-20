// Former gap vectors now assert ordinary-path redaction and ownership safety.
import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/core/models.dart';
import 'package:meshx_flutter_probe/core/store.dart';
import 'package:meshx_flutter_probe/platform/storage.dart';
import 'mobile_reliability_test.dart' show TestCredentials;
import 'platform_fakes.dart';
import 'recovery_test.dart'
    show ControlledApi, CapturingConnection, room, eventually;

final vectors =
    jsonDecode(
          File(
            '../../contracts/test-vectors/message-mutation-v1.json',
          ).readAsStringSync(),
        )
        as Json;
Json copy(dynamic value) => jsonDecode(jsonEncode(value)) as Json;
ChatMessage original() => ChatMessage.fromJson(copy(vectors['original']));

class MutationApi extends ControlledApi {
  bool removed = false;
  @override
  Future<List<Conversation>> conversations() async => removed ? [] : [room];
  @override
  Future<List<ChatMessage>> history(
    String id, {
    int? before,
    int limit = 50,
  }) async => [
    for (var n = 13; n <= 62; n++)
      ChatMessage.fromJson({
        ...copy(vectors['original']),
        'messageId': 'later-$n',
        'clientMsgId': 'later-client-$n',
        'sequence': n,
        'content': 'synthetic later $n',
      }),
  ];
}

class MutationConnection extends CapturingConnection {
  MutationConnection(super.api);
  // Deliberately retain a late-callback transport to exercise controller ownership.
  @override
  Future<void> close() async {}
  bool seed = true, denied = false;
  final requests = <Map<String, int>>[];
  @override
  Future<Json> synchronize(Map<String, int> positions) async {
    requests.add(Map.from(positions));
    if (denied) {
      return {
        'messages': [],
        'latestPositions': {},
        'deniedConversationIds': [room.id],
        'hasMore': false,
      };
    }
    return {
      ...copy(vectors['reconnect'])['payload'] as Json,
      'messages': seed && positions[room.id] == 0
          ? [
              copy(vectors['original']),
              {
                ...copy(vectors['original']),
                'messageId': 'tail-62',
                'clientMsgId': 'tail-client-62',
                'sequence': 62,
              },
            ]
          : [],
    };
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  for (final vector in vectors['cases'] as List) {
    test('v1 terminal regression: ${vector['id']}', () async {
      final api = MutationApi(), wire = MutationConnection(MutationApi());
      if (vector['id'] == 'mutation-before-original') wire.seed = false;
      final c = ChatController(
        discovery: FakeDiscovery(),
        connectionFactory: (_) => wire,
      )..api = api;
      addTearDown(c.dispose);
      await c.reconnect();
      c.active = room;
      if (vector['id'] == 'stale-connection-mutation') {
        await c.pause();
        wire.events.add(copy(vectors['recall']));
        // A supported event proves ownership fencing, not just an ignored event.
        wire.events.add({
          'event': 'CHAT_DELIVER',
          'payload': {
            ...copy(vectors['original']),
            'content': 'stale overwrite',
          },
        });
      } else if (vector['id'] == 'reconnect-mutation') {
        wire.seed = false;
        await c.reconnect();
        await c.select(
          room,
        ); // Current latest-50 history fallback also misses old A.
        expect(wire.requests.last[room.id], 0);
      } else {
        for (final op in vector['order'] as List) {
          if (op == 'original') {
            wire.events.add({
              'event': 'CHAT_DELIVER',
              'payload': copy(vectors['original']),
            });
          } else {
            wire.events.add(copy(vectors[op]));
          }
        }
      }
      await Future<void>.delayed(const Duration(milliseconds: 25));
      final found = c.messages
          .where((m) => m.messageId == original().messageId)
          .firstOrNull;
      switch (vector['id']) {
        case 'reconnect-mutation':
        case 'stale-connection-mutation':
        case 'removed-conversation':
          expect(found, isNull);
        case 'unknown-mutation':
          expect(
            found?.content,
            original().content,
          ); // Unknown WS events have no invented v1 semantics.
        default:
          expect(found?.content, isEmpty);
          expect(found!.recalled || found.burned, isTrue);
      }
      if (vector['id'] == 'removed-conversation') expect(c.active, isNull);
    });
  }

  test(
    'cold restart revalidates old bodies and removes deleted messages from disk',
    () async {
      final dir = await Directory.systemTemp.createTemp('meshx-a07-mutation-');
      addTearDown(() => dir.delete(recursive: true));
      final api = MutationApi(),
          credentials = TestCredentials(),
          store = FileChatStore(directory: dir);
      await credentials.write(api.credentials());
      final first = ChatController(
        discovery: FakeDiscovery(),
        store: store,
        connectionFactory: (a) => MutationConnection(a),
      )..api = api;
      await first.reconnect();
      first.active = room;
      await first.pause();
      first.dispose();
      final wire = MutationConnection(MutationApi())..seed = false;
      final second = ChatController(
        discovery: FakeDiscovery(),
        store: FileChatStore(directory: dir),
        credentials: credentials,
        apiFactory: (_) => MutationApi(),
        connectionFactory: (_) => wire,
      );
      addTearDown(second.dispose);
      await second.restore();
      second.active = room;
      expect(second.online, isTrue);
      expect(wire.requests.single[room.id], 0);
      expect(second.messages, isEmpty);
      expect(
        (await store.load(accountScope(api.origin, 1)))!['messages'][room.id],
        isEmpty,
      );
    },
  );

  test(
    'removed summary purges disk cache, pending messages and selection',
    () async {
      final dir = await Directory.systemTemp.createTemp('meshx-a07-access-');
      addTearDown(() => dir.delete(recursive: true));
      final api = MutationApi(),
          wire = MutationConnection(MutationApi()),
          store = FileChatStore(directory: dir);
      final c = ChatController(
        discovery: FakeDiscovery(),
        store: store,
        connectionFactory: (_) => wire,
      )..api = api;
      addTearDown(c.dispose);
      await c.reconnect();
      c.active = room;
      await c.pause();
      expect(c.send('synthetic queued sensitive text'), isTrue);
      await eventually(
        () => c.messages.any((m) => m.delivery == Delivery.queued),
      );
      api.removed = true;
      final requests = wire.requests.length;
      await c.resume();
      expect(c.conversations, isEmpty);
      expect(wire.requests.length, requests);
      expect(
        wire.frames,
        isEmpty,
      ); // No current send, but no permanent discard rule.
      expect(c.active, isNull);
      expect(c.positions[room.id], isNull);
      final disk = await store.load(accountScope(api.origin, 1));
      expect((disk!['messages'] as Map).containsKey(room.id), isFalse);
    },
  );

  test(
    'explicit deniedConversationIds path purges cache/cursor/queue and selection',
    () async {
      final api = MutationApi(), wire = MutationConnection(MutationApi());
      final c = ChatController(
        discovery: FakeDiscovery(),
        connectionFactory: (_) => wire,
      )..api = api;
      addTearDown(c.dispose);
      await c.reconnect();
      c.active = room;
      await c.pause();
      c.send('synthetic queue');
      wire.denied = true;
      await c.resume();
      expect(c.active, isNull);
      expect(c.positions, isEmpty);
      expect(c.messages, isEmpty);
      expect(wire.frames, isEmpty);
    },
  );

  test(
    'history, background and reconnect without visibility samples never emit CHAT_READ',
    () async {
      final api = MutationApi(), wire = MutationConnection(MutationApi());
      final c = ChatController(
        discovery: FakeDiscovery(),
        connectionFactory: (_) => wire,
      )..api = api;
      addTearDown(c.dispose);
      await c.reconnect();
      wire.events.add({
        'event': 'CHAT_DELIVER',
        'payload': copy(vectors['original']),
      });
      await c.select(room);
      await c.pause();
      wire.events.add({
        'event': 'CHAT_DELIVER',
        'payload': copy(vectors['original']),
      });
      await c.resume();
      await Future<void>.delayed(const Duration(milliseconds: 20));
      expect(wire.frames.where((f) => f['event'] == 'CHAT_READ'), isEmpty);
      // Positive visible-read semantics are covered by ordinary_read_test.dart.
    },
  );

  test(
    'unknown snapshot status redacts body and persists only unsupported content',
    () {
      final m = ChatMessage.fromJson({
        ...copy(vectors['original']),
        'status': 99,
      });
      expect(m.content, isEmpty);
      expect(m.contentType, 'unsupported');
      expect(m.toJson().containsKey('status'), isFalse);
    },
  );
}
