import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/platform/capability_codec.dart';
import 'package:meshx_flutter_probe/platform/discovery.dart';
import 'package:meshx_flutter_probe/platform/system_capabilities.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/application/platform_coordinator.dart';
import 'package:meshx_flutter_probe/data/broadcast_models.dart';
import 'package:meshx_flutter_probe/data/models.dart';
import 'package:meshx_flutter_probe/core/models.dart';
import 'platform_fakes.dart';
import 'recovery_test.dart'
    show ControlledApi, CapturingConnection, room, message, eventually;

class CountedConnection extends CapturingConnection {
  CountedConnection(super.api, this.opened, this.closed);
  final void Function() opened, closed;
  bool connected = false;
  @override
  Future<void> connect() async {
    connected = true;
    opened();
  }

  @override
  Future<void> close() async {
    if (connected) {
      connected = false;
      closed();
    }
    await super.close();
  }
}

class RoutingApi extends ControlledApi {
  bool activeTarget = true;
  int detailReads = 0;
  @override
  Future<BroadcastDetail> broadcastDetail(int broadcastId) async {
    detailReads++;
    return BroadcastDetail.fromJson({
      'broadcast': {
        'id': broadcastId,
        'senderId': 2,
        'title': '广播',
        'content': '内容',
        'status': activeTarget ? 'ACTIVE' : 'CANCELLED',
        'priority': 'NORMAL',
        'confirmationRequired': true,
        'requireImageProof': false,
        'requireLocationProof': false,
      },
      'receiver': {
        'id': 3,
        'broadcastId': broadcastId,
        'userId': 1,
        'confirmStatus': 'PENDING',
        'targetStatus': activeTarget ? 'ACTIVE' : 'REMOVED',
      },
      'sender': {'nickname': '发布者'},
      'contentEvidence': {'imageUrls': []},
      'confirmationOptions': ['RECEIVED'],
    });
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const codec = StandardMethodCodec();
  Future<void> event(Map<String, Object?> value) async {
    final done = Completer<void>();
    messenger.handlePlatformMessage(
      'com.meshx.prototype/discovery/events',
      codec.encodeSuccessEnvelope(value),
      (_) => done.complete(),
    );
    await done.future;
  }

  setUp(() {
    messenger.setMockMethodCallHandler(
      const MethodChannel('com.meshx.prototype/discovery/events'),
      (_) async => null,
    );
  });
  tearDown(() {
    messenger.setMockMethodCallHandler(NativeNodeDiscovery.channel, null);
    messenger.setMockMethodCallHandler(capabilityChannel, null);
    messenger.setMockMethodCallHandler(
      const MethodChannel('com.meshx.prototype/discovery/events'),
      null,
    );
  });

  test(
    'every capability state remains distinct and malformed replies fail closed',
    () async {
      const names = [
        'AVAILABLE',
        'SUCCESS',
        'PERMISSION_REQUIRED',
        'PERMISSION_DENIED',
        'PERMISSION_PERMANENTLY_DENIED',
        'UNSUPPORTED',
        'TEMPORARILY_UNAVAILABLE',
        'CANCELLED',
        'TIMEOUT',
        'FAILED',
      ];
      expect(
        names.map(decodeStatus).toSet(),
        hasLength(CapabilityStatus.values.length),
      );
      for (final name in names) {
        messenger.setMockMethodCallHandler(
          capabilityChannel,
          (_) async => {'status': name},
        );
        expect(
          (await invokeCapability(capabilityChannel, 'operation')).status,
          decodeStatus(name),
        );
      }
      messenger.setMockMethodCallHandler(capabilityChannel, (_) async => null);
      expect(
        (await invokeCapability(capabilityChannel, 'operation')).status,
        CapabilityStatus.failed,
      );
      messenger.setMockMethodCallHandler(
        capabilityChannel,
        (_) async => throw PlatformException(
          code: 'SECRET',
          message: '/private/user-file/token',
        ),
      );
      final sanitized = await invokeCapability(capabilityChannel, 'operation');
      expect(sanitized.reason, 'platformOperationFailed');
      messenger.setMockMethodCallHandler(capabilityChannel, null);
      expect(
        (await invokeCapability(capabilityChannel, 'operation')).status,
        CapabilityStatus.unsupported,
      );
    },
  );

  test(
    'malformed native discovery snapshot fails closed and stops native scan',
    () async {
      var session = 0, stops = 0;
      messenger.setMockMethodCallHandler(NativeNodeDiscovery.channel, (
        call,
      ) async {
        if (call.method == 'start') {
          session = (call.arguments as Map)['session'] as int;
        }
        if (call.method == 'stop') stops++;
        return {'status': 'AVAILABLE'};
      });
      final discovery = NativeNodeDiscovery();
      final updates = <DiscoveryUpdate>[];
      final subscription = discovery.updates.listen(updates.add);
      await discovery.start();
      final before = stops;
      await event({
        'session': session,
        'status': 'SUCCESS',
        'nodes': 'invalid',
      });
      await Future<void>.delayed(Duration.zero);
      expect(updates.last.result.status, CapabilityStatus.failed);
      expect(updates.last.result.reason, 'malformedSnapshot');
      expect(updates.last.complete, isTrue);
      expect(stops, before + 1);
      await subscription.cancel();
      await discovery.dispose();
    },
  );

  test(
    'discovery snapshot dedupes, removes lost nodes, and rejects stopped or old sessions',
    () async {
      var session = 0;
      messenger.setMockMethodCallHandler(NativeNodeDiscovery.channel, (
        call,
      ) async {
        if (call.method == 'start') {
          session = (call.arguments as Map)['session'] as int;
        }
        return {'status': 'AVAILABLE'};
      });
      final discovery = NativeNodeDiscovery();
      final updates = <DiscoveryUpdate>[];
      final subscription = discovery.updates.listen(updates.add);
      await discovery.start(requestPermission: true);
      await event({
        'session': session,
        'status': 'SUCCESS',
        'nodes': [
          {
            'id': 'node-one',
            'name': 'MeshX',
            'origin': 'http://192.168.0.2:8080',
          },
          {
            'id': 'legacy-alias',
            'name': 'MeshX',
            'origin': 'http://192.168.0.2:8080',
          },
          {'id': 'node-one', 'name': 'MeshX', 'origin': 'https://meshx.local'},
          {'id': 'malicious', 'origin': 'https://name:token@bad.example'},
        ],
      });
      expect(updates.last.result.value, hasLength(1));
      expect(updates.last.result.value!.single.origins, hasLength(2));
      await event({'session': session, 'status': 'SUCCESS', 'nodes': []});
      expect(updates.last.result.value, isEmpty);
      final old = session;
      await discovery.stop(cancelled: true);
      final count = updates.length;
      await event({
        'session': old,
        'status': 'SUCCESS',
        'nodes': [
          {'id': 'late', 'origin': 'http://192.168.0.9'},
        ],
      });
      expect(updates, hasLength(count));
      await discovery.start();
      await event({
        'session': old,
        'status': 'PERMISSION_DENIED',
        'complete': true,
      });
      expect(updates, hasLength(count));
      await discovery.dispose();
      expect((await discovery.start()).status, CapabilityStatus.cancelled);
      await subscription.cancel();
    },
  );

  test(
    'empty successful scan and denied/unsupported scan are different outcomes',
    () async {
      final discovery = FakeDiscovery();
      final chat = ChatController(discovery: discovery);
      await chat.scan();
      expect(chat.discoveryStatus, CapabilityStatus.success);
      expect(chat.error, contains('未发现'));
      discovery.scanStatus = CapabilityStatus.permissionDenied;
      await chat.scan();
      expect(chat.discoveryStatus, CapabilityStatus.permissionDenied);
      expect(chat.error, contains('权限'));
      discovery.scanStatus = CapabilityStatus.unsupported;
      await chat.scan();
      expect(chat.discoveryStatus, CapabilityStatus.unsupported);
      chat.dispose();
    },
  );

  test(
    'a missing native completion is timed out and explicitly stopped',
    () async {
      var stops = 0;
      messenger.setMockMethodCallHandler(NativeNodeDiscovery.channel, (
        call,
      ) async {
        if (call.method == 'stop') stops++;
        return {'status': 'AVAILABLE'};
      });
      final discovery = NativeNodeDiscovery();
      final updates = <DiscoveryUpdate>[];
      final subscription = discovery.updates.listen(updates.add);
      await discovery.start(window: const Duration(seconds: 1));
      await Future<void>.delayed(const Duration(milliseconds: 4200));
      expect(updates.last.result.status, CapabilityStatus.timeout);
      expect(stops, 1);
      await discovery.dispose();
      await subscription.cancel();
    },
  );

  test(
    'late start response cannot take ownership after another scan starts',
    () async {
      final first = Completer<Map<String, Object?>>();
      var starts = 0;
      messenger.setMockMethodCallHandler(NativeNodeDiscovery.channel, (
        call,
      ) async {
        if (call.method == 'start' && ++starts == 1) return first.future;
        return {'status': 'AVAILABLE'};
      });
      final discovery = NativeNodeDiscovery();
      final pending = discovery.start();
      await eventually(() => starts == 1);
      await discovery.start();
      first.complete({'status': 'AVAILABLE'});
      expect((await pending).status, CapabilityStatus.cancelled);
      await discovery.dispose();
    },
  );

  test(
    'stop clears listeners at application boundary even for a faulty late adapter event',
    () async {
      final discovery = FakeDiscovery()..autoComplete = false;
      final chat = ChatController(discovery: discovery);
      final pending = chat.scan();
      await eventually(() => discovery.starts == 1);
      await chat.stopScan();
      discovery.events.add(
        const DiscoveryUpdate(
          1,
          CapabilityResult(
            CapabilityStatus.success,
            value: [
              DiscoveredNode('late', 'late', ['http://192.168.0.9']),
            ],
          ),
        ),
      );
      await pending;
      expect(chat.candidates, isEmpty);
      expect(chat.scanning, isFalse);
      chat.dispose();
    },
  );

  test(
    'history/current conversation/own/duplicate events do not create notifications',
    () {
      final policy = NotificationPolicy();
      bool show(
        String id, {
        bool live = true,
        bool own = false,
        bool stored = false,
        bool viewing = false,
        String owner = 'one',
      }) => policy.shouldShow(
        owner: owner,
        id: id,
        live: live,
        ownMessage: own,
        alreadyStored: stored,
        viewingConversation: viewing,
      );
      expect(show('history', live: false), isFalse);
      expect(show('own', own: true), isFalse);
      expect(show('stored', stored: true), isFalse);
      expect(show('viewed', viewing: true), isFalse);
      expect(show('new'), isTrue);
      expect(show('new'), isFalse);
      expect(show('new', owner: 'two'), isTrue);
    },
  );

  test(
    'repeated OS resume, background and network recovery keep one connection and flush once',
    () async {
      final lifecycle = FakeLifecycle(),
          system = FakeSystem(),
          discovery = FakeDiscovery();
      final api = ControlledApi();
      var open = 0, maximum = 0, created = 0;
      final connections = <CountedConnection>[];
      final chat = ChatController(
        discovery: discovery,
        connectionFactory: (api) {
          created++;
          final wire = CountedConnection(api, () {
            open++;
            if (open > maximum) maximum = open;
          }, () => open--);
          connections.add(wire);
          return wire;
        },
      )..api = api;
      final platform = PlatformCoordinator(
        chat: chat,
        lifecycle: lifecycle,
        notifications: system,
        files: system,
        sharePort: system,
        network: system,
        settings: system,
      )..start();
      await chat.reconnect();
      chat.active = room;
      lifecycle.emit(AppVisibility.background);
      lifecycle.emit(AppVisibility.foreground);
      lifecycle.emit(AppVisibility.foreground);
      await platform.drain();
      expect(chat.online, isTrue);
      expect(maximum, 1);
      expect(created, 2);
      lifecycle.emit(AppVisibility.background);
      await platform.drain();
      expect(chat.send('offline with OS background'), isTrue);
      await Future<void>.delayed(Duration.zero);
      lifecycle.emit(AppVisibility.foreground);
      lifecycle.emit(AppVisibility.foreground);
      await platform.drain();
      expect(
        connections
            .expand((c) => c.frames)
            .where(
              (frame) =>
                  (frame['payload'] as Map)['content'] ==
                  'offline with OS background',
            ),
        hasLength(1),
      );
      system.networkEvents.add(
        const CapabilityResult(CapabilityStatus.success),
      );
      await Future<void>.delayed(const Duration(milliseconds: 280));
      await platform.drain();
      expect(chat.online, isTrue);
      expect(maximum, 1);
      platform.dispose();
      chat.dispose();
      await system.dispose();
    },
  );

  test(
    'logout invalidates an in-flight notification before its completion',
    () async {
      final system = FakeSystem()..showGate = Completer<void>();
      final chat = ChatController(discovery: FakeDiscovery())
        ..api = ControlledApi();
      final platform = PlatformCoordinator(
        chat: chat,
        lifecycle: FakeLifecycle(),
        notifications: system,
        files: system,
        sharePort: system,
        network: system,
        settings: system,
      )..start();
      await platform.drain();
      chat.onLiveMessage!(message(9), true, false);
      await eventually(() => platform.liveNotificationAttempts == 1);
      await chat.logout(revokeRemote: false);
      expect(system.owner, isNull);
      system.showGate!.complete();
      await platform.drain();
      expect(system.visible, isEmpty);
      platform.dispose();
      chat.dispose();
      await system.dispose();
    },
  );

  test(
    'notification tap waits for owner and online state then validates broadcast target',
    () async {
      final system = FakeSystem(), api = RoutingApi();
      final chat = ChatController(discovery: FakeDiscovery())..api = api;
      chat.online = true;
      final routes = <int?>[];
      final platform = PlatformCoordinator(
        chat: chat,
        lifecycle: FakeLifecycle(),
        notifications: system,
        files: system,
        sharePort: system,
        network: system,
        settings: system,
      )..start();
      platform.setBroadcastNavigationHandler(routes.add);
      var conversationNavigations = 0;
      platform.setConversationNavigationHandler(
        () => conversationNavigations++,
      );
      chat.conversations = [room];
      await platform.drain();
      system.tapEvents.add(NotificationRoute('wrong-owner', room.id));
      await Future<void>.delayed(Duration.zero);
      expect(conversationNavigations, 0);
      system.tapEvents.add(NotificationRoute(platform.owner!, room.id));
      await eventually(() => conversationNavigations == 1);
      expect(chat.active?.id, room.id);
      system.tapEvents.add(
        NotificationRoute('wrong-owner', room.id, broadcastId: 7),
      );
      await Future<void>.delayed(Duration.zero);
      expect(routes, isEmpty);
      system.tapEvents.add(
        NotificationRoute(platform.owner!, room.id, broadcastId: 7),
      );
      await eventually(() => routes.length == 1);
      expect(routes, [7]);
      api.activeTarget = false;
      system.tapEvents.add(
        NotificationRoute(platform.owner!, room.id, broadcastId: 8),
      );
      await eventually(() => routes.length == 2);
      expect(routes, [7, null]);
      expect(api.detailReads, 2);
      platform.dispose();
      chat.dispose();
      await system.dispose();
    },
  );

  test(
    'live broadcast notification carries only its bounded route id',
    () async {
      final system = FakeSystem()
        ..permissionStatus = CapabilityStatus.available;
      final chat = ChatController(discovery: FakeDiscovery())
        ..api = ControlledApi();
      final platform = PlatformCoordinator(
        chat: chat,
        lifecycle: FakeLifecycle(),
        notifications: system,
        files: system,
        sharePort: system,
        network: system,
        settings: system,
      )..start();
      await platform.drain();
      chat.onLiveMessage!(
        ChatMessage(
          messageId: 'broadcast-message',
          clientMsgId: 'broadcast-client',
          conversationId: room.id,
          fromUserId: 2,
          content:
              '{"kind":"BROADCAST_OVERVIEW","broadcastId":17,"title":"secret"}',
          contentType: 'broadcast',
          sequence: 17,
          createdAt: DateTime(2026),
        ),
        true,
        false,
      );
      await platform.drain();
      expect(system.shownRoutes.single.broadcastId, 17);
      expect(system.shownRoutes.single.conversationId, room.id);
      platform.dispose();
      chat.dispose();
      await system.dispose();
    },
  );

  test(
    'offline tap from a logged-out owner cannot navigate after account switch',
    () async {
      final system = FakeSystem(), oldApi = RoutingApi();
      final chat = ChatController(discovery: FakeDiscovery())..api = oldApi;
      final routes = <int?>[];
      final platform = PlatformCoordinator(
        chat: chat,
        lifecycle: FakeLifecycle(),
        notifications: system,
        files: system,
        sharePort: system,
        network: system,
        settings: system,
      )..start();
      platform.setBroadcastNavigationHandler(routes.add);
      await platform.drain();
      final oldOwner = platform.owner!;
      chat.online = false;
      system.tapEvents.add(
        NotificationRoute(oldOwner, room.id, broadcastId: 9),
      );
      final newApi = RoutingApi()
        ..session = const Session(2, '新账号', 'new-token');
      chat.api = newApi;
      chat.online = true;
      chat.notifyListeners();
      await platform.drain();
      await Future<void>.delayed(Duration.zero);
      expect(platform.owner, isNot(oldOwner));
      expect(routes, isEmpty);
      expect(newApi.detailReads, 0);
      platform.dispose();
      chat.dispose();
      await system.dispose();
    },
  );

  test('settings open is single-flight and preserves status reason', () async {
    final gate = Completer<void>();
    final system = FakeSystem()
      ..settingsGate = gate
      ..settingsResult = const CapabilityResult(
        CapabilityStatus.failed,
        reason: 'settingsOpenRejected',
      );
    final chat = ChatController(discovery: FakeDiscovery())
      ..api = ControlledApi();
    final platform = PlatformCoordinator(
      chat: chat,
      lifecycle: FakeLifecycle(),
      notifications: system,
      files: system,
      sharePort: system,
      network: system,
      settings: system,
    )..start();
    final first = platform.openSettings();
    await Future<void>.delayed(Duration.zero);
    await platform.openSettings();
    expect(system.settingsCount, 1);
    gate.complete();
    await first;
    expect(platform.settingsStatus?.status, CapabilityStatus.failed);
    expect(platform.settingsStatus?.reason, 'settingsOpenRejected');
    platform.dispose();
    chat.dispose();
    await system.dispose();
  });

  test(
    'late picker result is released after account ownership changes',
    () async {
      final system = FakeSystem()
        ..pickGate = Completer<CapabilityResult<SelectedFile>>();
      final chat = ChatController(discovery: FakeDiscovery())
        ..api = ControlledApi();
      final platform = PlatformCoordinator(
        chat: chat,
        lifecycle: FakeLifecycle(),
        notifications: system,
        files: system,
        sharePort: system,
        network: system,
        settings: system,
      )..start();
      final selecting = platform.pickFile();
      await eventually(() => system.pickCount == 1);
      await chat.logout(revokeRemote: false);
      final before = system.releaseCount;
      system.pickGate!.complete(
        const CapabilityResult(
          CapabilityStatus.success,
          value: SelectedFile(
            handle: 'opaque',
            name: 'selected.txt',
            mime: 'text/plain',
            size: 4,
          ),
        ),
      );
      await selecting;
      expect(platform.selectedFile, isNull);
      expect(system.releaseCount, greaterThan(before));
      platform.dispose();
      chat.dispose();
      await system.dispose();
    },
  );

  test(
    'photo selection reaches native picker with bounded photo intent',
    () async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(capabilityChannel, (call) async {
        calls.add(call);
        return {'status': 'CANCELLED'};
      });
      final result = await NativeSystemCapabilities().pickPhoto(maxBytes: 1024);
      expect(result.status, CapabilityStatus.cancelled);
      expect(calls.single.method, 'pickFile');
      expect(calls.single.arguments, {'maxBytes': 1024, 'photos': true});
    },
  );

  test(
    'file cancellation, too-large, missing and permission denial retain reason and never share paths',
    () async {
      final adapter = NativeSystemCapabilities();
      for (final result in [
        {'status': 'CANCELLED'},
        {'status': 'FAILED', 'reason': 'fileTooLarge'},
        {'status': 'FAILED', 'reason': 'fileMissing'},
        {'status': 'PERMISSION_DENIED'},
      ]) {
        messenger.setMockMethodCallHandler(
          capabilityChannel,
          (_) async => result,
        );
        final picked = await adapter.pick();
        expect(picked.status, decodeStatus(result['status']));
        expect(picked.value, isNull);
        expect(picked.reason, result['reason'] ?? '');
      }
      messenger.setMockMethodCallHandler(
        capabilityChannel,
        (_) async => {
          'status': 'SUCCESS',
          'handle': '/etc/passwd',
          'name': 'x',
          'size': 1,
          'mime': 'text/plain',
        },
      );
      expect((await adapter.pick()).status, CapabilityStatus.failed);
      Object? arguments;
      messenger.setMockMethodCallHandler(capabilityChannel, (call) async {
        arguments = call.arguments;
        return {'status': 'SUCCESS'};
      });
      await adapter.share(
        const SelectedFile(
          handle: 'opaque',
          name: 'display.txt',
          mime: 'text/plain',
          size: 4,
        ),
      );
      expect(arguments, {'handle': 'opaque'});
      messenger.setMockMethodCallHandler(capabilityChannel, (call) async {
        expect(call.method, 'readFile');
        expect(call.arguments, {
          'handle': '00000000-0000-0000-0000-000000000001',
          'maxBytes': 4,
        });
        return {
          'status': 'SUCCESS',
          'bytes': Uint8List.fromList([1, 2, 3, 4]),
        };
      });
      final read = await adapter.read(
        const SelectedFile(
          handle: '00000000-0000-0000-0000-000000000001',
          name: 'image.png',
          mime: 'image/png',
          size: 4,
        ),
        maxBytes: 4,
      );
      expect(read.value, [1, 2, 3, 4]);
      messenger.setMockMethodCallHandler(capabilityChannel, (call) async {
        expect(call.method, 'readFileChunk');
        expect(call.arguments, {
          'handle': '00000000-0000-0000-0000-000000000001',
          'offset': 1,
          'length': 2,
        });
        return {
          'status': 'SUCCESS',
          'bytes': Uint8List.fromList([2, 3]),
        };
      });
      final chunk = await adapter.readChunk(
        const SelectedFile(
          handle: '00000000-0000-0000-0000-000000000001',
          name: 'image.png',
          mime: 'image/png',
          size: 4,
        ),
        offset: 1,
        length: 2,
      );
      expect(chunk.value, [2, 3]);
      messenger.setMockMethodCallHandler(capabilityChannel, (call) async {
        expect(call.method, 'cacheFile');
        expect((call.arguments as Map)['name'], 'received.txt');
        expect((call.arguments as Map)['mime'], 'text/plain');
        expect((call.arguments as Map)['bytes'], Uint8List.fromList([1, 2]));
        return {
          'status': 'SUCCESS',
          'handle': '00000000-0000-0000-0000-000000000002',
          'name': 'received.txt',
          'mime': 'text/plain',
          'size': 2,
        };
      });
      final cached = await adapter.cache(
        name: 'received.txt',
        mime: 'text/plain',
        bytes: [1, 2],
      );
      expect(cached.value?.handle, '00000000-0000-0000-0000-000000000002');
      messenger.setMockMethodCallHandler(
        capabilityChannel,
        (_) async => {
          'status': 'SUCCESS',
          'bytes': Uint8List.fromList([1, 2, 3]),
        },
      );
      expect(
        (await adapter.read(
          const SelectedFile(
            handle: '00000000-0000-0000-0000-000000000001',
            name: 'image.png',
            mime: 'image/png',
            size: 4,
          ),
          maxBytes: 4,
        )).reason,
        'invalidFileContent',
      );
      await adapter.dispose();
    },
  );
}
