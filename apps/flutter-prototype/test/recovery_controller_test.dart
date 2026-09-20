import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/ui/app.dart';
import 'package:meshx_flutter_probe/ui/theme.dart';
import 'package:meshx_flutter_probe/ui/glass_chrome.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/application/recovery_coordinator.dart';
import 'package:meshx_flutter_probe/core/recovery.dart';
import 'package:meshx_flutter_probe/platform/storage.dart';
import 'platform_fakes.dart';
import 'recovery_test.dart' show ControlledApi, CapturingConnection, room;

class ControllerRecoveryTransport implements RecoveryTransport {
  static const epoch = '11111111-1111-4111-8111-111111111111';
  Completer<void>? gate;
  final entered = Completer<void>();
  bool revoked = false, supported = true;
  String terminal = 'NORMAL';
  @override
  Future<Object?> capabilities() async => {
    'capability': 'meshx.mutation-recovery',
    'versions': supported ? [1] : [],
    'recordVersion': 1,
    'maxPageSize': 100,
  };
  @override
  Future<Object?> open(Map<String, dynamic> input, String key) async => {
    'recoveryId': 'session',
    'mode': 'rebuild',
    'streamEpoch': epoch,
    'startCursor': '0',
    'snapshotBoundary': '0',
    'floor': '0',
    'latest': '0',
    'snapshotId': 'snapshot',
  };
  @override
  Future<Object?> snapshot(String id, String? token, int limit) async {
    if (!entered.isCompleted) entered.complete();
    await gate?.future;
    return {
      'snapshotId': 'snapshot',
      'boundary': '0',
      'items': revoked
          ? []
          : [
              {
                'kind': 'CONVERSATION',
                'conversationId': room.id,
                'accessVersion': '1',
                'readAllowed': true,
                'sendAllowed': true,
                'messageSequenceAtH': '1',
              },
              {
                'kind': 'MESSAGE',
                'conversationId': room.id,
                'messageId': 'verified',
                'objectVersion': '1',
                'state': terminal,
                'messageSequence': '1',
                if (terminal == 'NORMAL') 'content': 'verified body',
                if (terminal == 'NORMAL')
                  'details': {
                    'fromUserId': 2,
                    'contentType': 'text',
                    'createTime': '2026-09-19T10:00:00',
                    'isBurn': 0,
                  },
              },
            ],
      'nextPageToken': null,
      'snapshotComplete': true,
    };
  }

  @override
  Future<Object?> cut(String id) async => {
    'through': '0',
    'floor': '0',
    'streamEpoch': epoch,
  };
  @override
  Future<Object?> mutations(
    String id,
    String after,
    String through,
    int limit,
  ) async => throw StateError('no mutations expected');
  @override
  Future<Object?> ready(String id, String cursor, bool complete) async => {
    'ready': true,
    'acceptedCursor': '0',
    'latest': '0',
    'streamEpoch': epoch,
  };
  @override
  Future<void> release(String id) async {}
}

void main() {
  late Directory root;
  late FileChatStore store;
  setUp(() async {
    root = await Directory.systemTemp.createTemp('meshx-controller-recovery-');
    store = FileChatStore(directory: root);
  });
  tearDown(() async {
    await root.delete(recursive: true);
  });
  ChatController create(
    ControllerRecoveryTransport transport,
    ControlledApi api,
    CapturingConnection wire, {
    bool enabled = true,
  }) => ChatController(
    discovery: FakeDiscovery(),
    store: store,
    enableRecovery: enabled,
    recoveryTransportFactory: (_) => transport,
    apiFactory: (_) => api,
    connectionFactory: (_) => wire,
  );

  test(
    'controller quarantines legacy cache and gates send after durable rebuild',
    () async {
      final api = ControlledApi(),
          transport = ControllerRecoveryTransport()..gate = Completer<void>();
      final wire = CapturingConnection(api), c = create(transport, api, wire);
      addTearDown(c.dispose);
      await store.save('http://127.0.0.1|1', {
        'conversations': [room.toJson()],
        'messages': {
          room.id: [
            {
              'messageId': 'old',
              'clientMsgId': 'old',
              'conversationId': room.id,
              'fromUserId': 2,
              'content': 'legacy-secret',
              'sequence': 1,
              'createTime': '2026-01-01T00:00:00',
              'delivery': 'sent',
            },
          ],
        },
        'positions': {room.id: 1},
      });
      final login = c.login('http://127.0.0.1', 'user', 'password');
      await transport.entered.future;
      c.active = room;
      expect(c.messages, isEmpty);
      expect(c.online, isFalse);
      expect(c.send('too early'), isFalse);
      transport.gate!.complete();
      await login;
      expect(c.recoveryPhase, RecoveryPhase.onlineSafe);
      expect(c.online, isTrue);
      expect(c.messages.single.content, 'verified body');
      expect(api.historyCount, 0);
      expect(
        jsonEncode((await store.loadRecovery('http://127.0.0.1|1'))!.snapshot),
        isNot(contains('legacy-secret')),
      );
      expect(c.send('new draft'), isTrue);
      for (var i = 0; i < 50 && wire.frames.isEmpty; i++) {
        await Future<void>.delayed(const Duration(milliseconds: 2));
      }
      expect(wire.frames.single['event'], 'CHAT_SEND');
      final stored = (await store.loadRecovery('http://127.0.0.1|1'))!.snapshot;
      expect(stored['outbox'][room.id][0]['content'], 'new draft');
      var notifications = 0;
      c.onLiveMessage = (_, _, _) {
        notifications++;
      };
      transport.revoked = true;
      transport.gate = Completer<void>();
      wire.events.add({
        'event': 'CHAT_DELIVER',
        'payload': {'content': 'unverified-live-body'},
      });
      for (var i = 0; i < 50 && c.online; i++) {
        await Future<void>.delayed(const Duration(milliseconds: 2));
      }
      expect(c.online, isFalse);
      expect(c.messages, isEmpty);
      expect(notifications, 0);
      transport.gate!.complete();
      for (var i = 0; i < 100 && !c.online; i++) {
        await Future<void>.delayed(const Duration(milliseconds: 2));
      }
      expect(c.online, isTrue);
      expect(c.active, isNull);
      expect(c.conversations, isEmpty);
      final revoked = (await store.loadRecovery(
        'http://127.0.0.1|1',
      ))!.snapshot;
      expect(jsonEncode(revoked), isNot(contains('new draft')));
      expect(
        revoked['outbox'][room.id][0]['recoveryDisposition'],
        'dropBodyRevoked',
      );
      await c.pause();
      expect(c.messages, isEmpty);
      expect(c.send('offline'), isFalse);
    },
  );

  test('logout during snapshot cannot publish old account data', () async {
    final api = ControlledApi(),
        transport = ControllerRecoveryTransport()..gate = Completer<void>();
    final c = create(transport, api, CapturingConnection(api));
    addTearDown(c.dispose);
    final login = c.login('http://127.0.0.1', 'user', 'password');
    await transport.entered.future;
    await c.logout(revokeRemote: false);
    transport.gate!.complete();
    await login;
    expect(c.session, isNull);
    expect(c.online, isFalse);
    expect(c.messages, isEmpty);
    expect(await store.loadRecovery('http://127.0.0.1|1'), isNull);
  });
  test(
    'format2 requires recovery even with build flag off; unsupported server cannot fall back',
    () async {
      await store.commitRecovery('http://127.0.0.1|1', 0, {
        'outbox': <String, dynamic>{},
        'messages': {'secret': 'must-not-display'},
      });
      final api = ControlledApi(),
          transport = ControllerRecoveryTransport()..supported = false;
      final wire = CapturingConnection(api),
          c = create(transport, api, wire, enabled: false);
      addTearDown(c.dispose);
      await c.login('http://127.0.0.1', 'user', 'password');
      expect(c.usesMutationRecovery, isTrue);
      expect(c.online, isFalse);
      expect(c.messages, isEmpty);
      expect(c.lastErrorCode, 'BLOCKED_UPGRADE');
      expect(api.historyCount, 0);
      expect(wire.frames, isEmpty);
      expect((await store.loadRecovery('http://127.0.0.1|1'))!.revision, 1);
    },
  );
  test(
    'actual file failure remains storage blocked and never exposes the staged snapshot',
    () async {
      final encoded = base64Url.encode(utf8.encode('http://127.0.0.1|1'));
      await Directory('${root.path}/$encoded.json.tmp').create();
      final api = ControlledApi(), transport = ControllerRecoveryTransport();
      final wire = CapturingConnection(api), c = create(transport, api, wire);
      addTearDown(c.dispose);
      await c.login('http://127.0.0.1', 'user', 'password');
      expect(c.recoveryPhase, RecoveryPhase.storageBlocked);
      expect(c.online, isFalse);
      c.active = room;
      expect(c.messages, isEmpty);
      expect(c.send('must not send'), isFalse);
      expect(wire.frames, isEmpty);
      expect(await store.loadRecovery('http://127.0.0.1|1'), isNull);
    },
  );

  testWidgets('two pending drafts retain distinct widget identities', (
    tester,
  ) async {
    late ChatController c;
    await tester.runAsync(() async {
      final api = ControlledApi();
      c = create(ControllerRecoveryTransport(), api, CapturingConnection(api));
      await c.login('http://127.0.0.1', 'user', 'password');
      c.active = room;
      c.send('first pending draft');
      c.send('second pending draft');
      await Future<void>.delayed(const Duration(milliseconds: 30));
    });
    addTearDown(c.dispose);
    await tester.pumpWidget(
      MaterialApp(
        theme: meshXTheme(Brightness.light),
        home: Scaffold(body: MessagePane(controller: c)),
      ),
    );
    expect(find.text('first pending draft'), findsOneWidget);
    expect(find.text('second pending draft'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.runAsync(() => c.pause());
  });

  test(
    'read frames require continuous visible eligibility and stop on quarantine',
    () async {
      final api = ControlledApi(), transport = ControllerRecoveryTransport();
      final wire = CapturingConnection(api), c = create(transport, api, wire);
      addTearDown(c.dispose);
      await c.login('http://127.0.0.1', 'user', 'password');
      c.active = room;
      final scope = c.recoveryReadScope;
      for (final now in [0, 100, 200, 300]) {
        c.observeRecoveryRead(scope, {2}, now, foreground: true);
      }
      expect(wire.frames, isEmpty);
      for (final now in [400, 500, 600, 699]) {
        c.observeRecoveryRead(scope, {1}, now, foreground: true);
      }
      expect(wire.frames, isEmpty);
      c.observeRecoveryRead(scope, {1}, 700, foreground: true);
      expect(wire.frames.single['event'], 'CHAT_READ');
      expect(wire.frames.single['payload']['lastReadSequence'], 1);
      await c.pause();
      c.observeRecoveryRead(scope, {1}, 1000, foreground: true);
      expect(wire.frames.length, 1);
    },
  );

  for (final state in ['RECALLED', 'BURNED', 'UNAVAILABLE']) {
    testWidgets('authoritative $state row renders without invented identity', (
      tester,
    ) async {
      late ChatController c;
      await tester.runAsync(() async {
        final api = ControlledApi(),
            transport = ControllerRecoveryTransport()..terminal = state;
        c = create(transport, api, CapturingConnection(api));
        await c.login('http://127.0.0.1', 'user', 'password');
        c.active = room;
      });
      addTearDown(c.dispose);
      await tester.pumpWidget(
        MaterialApp(
          theme: meshXTheme(Brightness.light),
          home: Scaffold(body: MessagePane(controller: c)),
        ),
      );
      expect(c.messages, isEmpty);
      expect(find.byKey(const ValueKey('terminal-verified')), findsOneWidget);
      expect(find.text(c.recoveryTerminals.single.label), findsOneWidget);
      expect(find.text('发送第一条消息，开始交流'), findsNothing);
      expect(find.text('verified body'), findsNothing);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.runAsync(() => c.pause());
      expect(c.recoveryTerminals, isEmpty);
    });
  }

  for (final brightness in Brightness.values) {
    for (final width in [390.0, 1024.0]) {
      testWidgets('recovery feedback and disabled send at $brightness/$width', (
        tester,
      ) async {
        late ChatController c;
        await tester.runAsync(() async {
          final api = ControlledApi(),
              transport = ControllerRecoveryTransport();
          c = create(transport, api, CapturingConnection(api));
          await c.login('http://127.0.0.1', 'user', 'password');
          c.active = room;
          await c.pause();
        });
        addTearDown(c.dispose);
        tester.view.physicalSize = Size(width, 844);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        await tester.pumpWidget(
          MaterialApp(
            theme: meshXTheme(brightness),
            home: Scaffold(body: MessagePane(controller: c)),
          ),
        );
        await tester.enterText(find.byType(TextField), 'retained draft');
        await tester.pump();
        expect(find.text('正在校验消息，完成后可继续聊天'), findsOneWidget);
        expect(find.text('verified body'), findsNothing);
        expect(
          tester
              .widget<MeshXGlassButton>(find.byKey(const Key('send-message')))
              .onPressed,
          isNull,
        );
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
      });
    }
  }
}
