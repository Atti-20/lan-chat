import 'platform_fakes.dart';
import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';
import 'package:meshx_flutter_probe/data/models.dart';

const room = Conversation(
  id: 'private:1:2',
  targetId: 2,
  kind: 'private',
  title: '测试',
);

class ControlledApi extends MeshXApi {
  ControlledApi() : super(Uri.parse('http://127.0.0.1')) {
    node = NodeInfo(origin, '测试', '', '/api/v1', '/ws/chat');
    session = const Session(1, '测试', 'old');
  }
  @override
  Future<Map<String, int>> recoveryReadPositions() async => {room.id: 0};
  bool failSnapshot = false, closed = false;
  int refreshCount = 0, historyCount = 0;
  final firstHistory = Completer<List<ChatMessage>>();
  @override
  Future<void> validateCurrentUser() async {}
  @override
  Future<NodeInfo> handshake() async => node!;
  @override
  Future<Session> login(String username, String password) async => session!;
  @override
  Future<List<Conversation>> conversations() async {
    if (failSnapshot) throw const ApiException('初始化失败');
    return [room];
  }

  @override
  Future<Session> refresh({String? rejectedToken}) async {
    refreshCount++;
    return session = const Session(1, '测试', 'new');
  }

  @override
  Future<List<ChatMessage>> history(String id, {int? before, int limit = 50}) {
    historyCount++;
    return historyCount == 1
        ? firstHistory.future
        : Future.value([message(1), message(2)]);
  }

  @override
  void close() {
    closed = true;
    super.close();
  }
}

ChatMessage message(int sequence) => ChatMessage(
  messageId: '$sequence',
  clientMsgId: 'c$sequence',
  conversationId: room.id,
  fromUserId: 2,
  content: '$sequence',
  sequence: sequence,
  createdAt: DateTime(2026),
);

class ControlledConnection extends RealtimeConnection {
  ControlledConnection(super.api, [this.failure]);
  final Exception? failure;
  @override
  Future<Json> synchronize(Map<String, int> positions) async => {
    'messages': [
      message(1).toJson(),
      message(2).toJson(),
    ].where((j) => (j['sequence'] as int) > positions.values.first).toList(),
    'latestPositions': {positions.keys.first: 2},
    'deniedConversationIds': <String>[],
    'hasMore': false,
  };
  @override
  Future<void> connect() async {
    if (failure != null) throw failure!;
  }
}

class CapturingConnection extends ControlledConnection {
  CapturingConnection(super.api);
  final frames = <Json>[];
  @override
  void send(
    String event,
    Json payload, {
    String? clientMsgId,
    String? conversationId,
  }) {
    frames.add({
      'event': event,
      'payload': Map<String, dynamic>.from(payload),
      'clientMsgId': clientMsgId,
      'conversationId': conversationId,
    });
  }
}

Future<void> eventually(bool Function() condition) async {
  for (var i = 0; i < 100 && !condition(); i++) {
    await Future<void>.delayed(const Duration(milliseconds: 5));
  }
  expect(condition(), isTrue);
}

class DeniedLocalNetwork extends FakeDiscovery {
  DeniedLocalNetwork() {
    accessStatus = CapabilityStatus.permissionDenied;
  }
}

void main() {
  for (final kind in ['private', 'group']) {
    test(
      '$kind send uses the generated wire DTO and retries the original id/content',
      () async {
        final api = ControlledApi();
        final wire = CapturingConnection(api);
        final c = ChatController(connectionFactory: (_) => wire)..api = api;
        await c.reconnect();
        c.active = Conversation(
          id: '$kind:1:2',
          targetId: 2,
          kind: kind,
          title: '契约测试',
        );
        try {
          expect(c.send('协议与设计保持一致。'), isTrue);
          final pending = c.messages.singleWhere(
            (m) => m.fromUserId == api.session!.userId,
          );
          expect(wire.frames.single['event'], 'CHAT_SEND');
          expect(wire.frames.single['payload'], {
            if (kind == 'private') 'toUserId': 2,
            if (kind == 'group') 'groupId': 2,
            'contentType': 'text',
            'content': '协议与设计保持一致。',
            'isBurn': false,
          });
          expect(
            wire.frames.single['clientMsgId'],
            matches(RegExp(r'^[A-Za-z0-9_-]{8,64}$')),
          );
          expect(wire.frames.single['conversationId'], c.active!.id);
          expect(c.send('不能改变重试内容', retry: pending), isTrue);
          expect(wire.frames[1], wire.frames[0]);
          expect(
            c.messages.where((m) => m.fromUserId == api.session!.userId),
            hasLength(1),
          );
        } finally {
          c.dispose();
        }
      },
    );
  }

  test(
    'manual connection requests permission before creating an authenticated API',
    () async {
      var created = false;
      final c = ChatController(
        discovery: DeniedLocalNetwork(),
        apiFactory: (_) {
          created = true;
          return ControlledApi();
        },
      );
      await c.login('http://127.0.0.1', 'test', 'test');
      expect(created, isFalse);
      expect(c.session, isNull);
      expect(c.error, capabilityMessage(CapabilityStatus.permissionDenied));
      c.dispose();
    },
  );
  test('initial snapshot failure does not commit a partial login', () async {
    final api = ControlledApi()..failSnapshot = true;
    final c = ChatController(
      discovery: FakeDiscovery(),
      apiFactory: (_) => api,
    );
    await c.login('http://127.0.0.1', 'test', 'test');
    expect(c.session, isNull);
    expect(c.conversations, isEmpty);
    expect(c.error, '初始化失败');
    expect(api.closed, isTrue);
    c.dispose();
  });

  test('expired websocket auth refreshes once then recovers', () async {
    final api = ControlledApi();
    var attempts = 0;
    final c = ChatController(
      connectionFactory: (a) => ControlledConnection(
        a,
        attempts++ == 0 ? const ApiException('expired', code: 401) : null,
      ),
    )..api = api;
    await c.reconnect();
    expect(api.refreshCount, 1);
    expect(c.online, isFalse);
    await c.reconnect();
    expect(c.online, isTrue);
    expect(api.refreshCount, 1);
    c.dispose();
  });

  test('live TOKEN_EXPIRED reconnects and refreshes only once', () async {
    final api = ControlledApi();
    final initial = ControlledConnection(api);
    var connections = 0;
    final c = ChatController(
      connectionFactory: (a) {
        connections++;
        if (connections == 1) return initial;
        return ControlledConnection(
          a,
          connections == 2 ? const ApiException('expired', code: 401) : null,
        );
      },
    )..api = api;
    await c.reconnect();
    initial.events.add({
      'event': 'TOKEN_EXPIRED',
      'payload': <String, dynamic>{},
    });
    final deadline = DateTime.now().add(const Duration(seconds: 5));
    while ((connections < 3 || !c.online) &&
        DateTime.now().isBefore(deadline)) {
      await Future<void>.delayed(const Duration(milliseconds: 20));
    }
    expect(c.online, isTrue);
    expect(connections, 3);
    expect(api.refreshCount, 1);
    c.dispose();
  });

  test(
    'revoked auth clears credentials without refreshing or reconnecting',
    () async {
      final api = ControlledApi();
      var attempts = 0;
      final c = ChatController(
        connectionFactory: (a) {
          attempts++;
          return ControlledConnection(
            a,
            const SessionRevokedException('设备已撤销'),
          );
        },
      )..api = api;
      await c.reconnect();
      await c.reconnect();
      expect(c.session, isNull);
      expect(c.error, '设备已撤销');
      expect(api.refreshCount, 0);
      expect(attempts, 1);
      c.dispose();
    },
  );

  test('live FORCE_LOGOUT clears conversation and session', () async {
    final api = ControlledApi();
    final connection = ControlledConnection(api);
    final c = ChatController(connectionFactory: (_) => connection)..api = api;
    await c.reconnect();
    connection.events.add({
      'event': 'FORCE_LOGOUT',
      'payload': {'message': '设备已撤销'},
    });
    await eventually(() => c.session == null);
    expect(c.online, isFalse);
    expect(c.conversations, isEmpty);
    expect(c.error, '设备已撤销');
    c.dispose();
  });

  test(
    'authoritative sync and an old history overlap merge without losing rows',
    () async {
      final api = ControlledApi();
      final c = ChatController(
        connectionFactory: (a) => ControlledConnection(a),
      )..api = api;
      final selecting = c.select(room);
      await eventually(() => api.historyCount == 1);
      final recovering = c.reconnect();
      await Future<void>.delayed(const Duration(milliseconds: 15));
      api.firstHistory.complete([message(1)]);
      await Future.wait([selecting, recovering]);
      expect(api.historyCount, 1);
      expect(c.positions[room.id], 2);
      expect(c.messages.map((m) => m.sequence), [1, 2]);
      c.dispose();
    },
  );

  test(
    'staggered 401 responses reuse the already rotated access token',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final api = MeshXApi(Uri.parse('http://127.0.0.1:${server.port}'));
      final stale = <HttpRequest>[];
      final retryTokens = <String>[];
      var rotations = 0;
      Future<void> respond(
        HttpRequest request,
        dynamic data, [
        int code = 200,
      ]) async {
        request.response.headers.contentType = ContentType.json;
        request.response.statusCode = code;
        request.response.write(jsonEncode({'code': code, 'data': data}));
        await request.response.close();
      }

      server.listen((request) async {
        final path = request.uri.path;
        if (path.endsWith('/node/info')) {
          await respond(request, {'protocolVersion': 1});
        } else if (path.endsWith('/login')) {
          request.response.cookies.add(
            Cookie('lanchat_refresh', 'test-refresh'),
          );
          await respond(request, {
            'userId': 1,
            'nickname': 'test',
            'token': 'old',
          });
        } else if (path.endsWith('/refresh')) {
          rotations++;
          await respond(request, {
            'userId': 1,
            'nickname': 'test',
            'token': 'new$rotations',
          });
        } else if (request.headers.value('authorization') == 'Bearer old') {
          stale.add(request);
          if (stale.length == 3) await respond(stale[0], null, 401);
        } else {
          retryTokens.add(request.headers.value('authorization')!);
          await respond(request, []);
          if (retryTokens.length < 3) {
            await respond(stale[retryTokens.length], null, 401);
          }
        }
      });
      try {
        await api.handshake();
        await api.login('test', 'test');
        await api.conversations().timeout(const Duration(seconds: 5));
        expect(rotations, 1);
        expect(retryTokens, ['Bearer new1', 'Bearer new1', 'Bearer new1']);
      } finally {
        api.close();
        await server.close(force: true);
      }
    },
  );

  for (final event in ['TOKEN_EXPIRED', 'FORCE_LOGOUT']) {
    test(
      'real websocket maps $event before close to its auth outcome',
      () async {
        final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
        final sockets = <WebSocket>[];
        server.listen((request) async {
          final socket = await WebSocketTransformer.upgrade(request);
          sockets.add(socket);
          socket.listen((_) async {
            socket.add(
              jsonEncode({
                'version': 1,
                'event': event,
                'timestamp': DateTime.now().millisecondsSinceEpoch,
                'payload': {'message': 'test'},
              }),
            );
            await socket.close(WebSocketStatus.policyViolation);
          });
        });
        final api = MeshXApi(Uri.parse('http://127.0.0.1:${server.port}'));
        api.node = NodeInfo(api.origin, 'test', '', '/api/v1', '/ws/chat');
        api.session = const Session(1, 'test', 'old');
        final connection = RealtimeConnection(api);
        try {
          await expectLater(
            connection.connect(),
            throwsA(
              event == 'FORCE_LOGOUT'
                  ? isA<SessionRevokedException>()
                  : isA<ApiException>().having((e) => e.code, 'code', 401),
            ),
          );
        } finally {
          await connection.close();
          api.close();
          for (final socket in sockets) {
            await socket.close();
          }
          await server.close(force: true);
        }
      },
    );
  }
}
