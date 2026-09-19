import 'dart:io';
import 'dart:async';
import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/application/platform_coordinator.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/platform/storage.dart';
import 'platform_fakes.dart';
import 'recovery_controller_test.dart' show ControllerRecoveryTransport;
import 'recovery_test.dart'
    show ControlledApi, CapturingConnection, room, eventually;

class NotificationTransport extends ControllerRecoveryTransport {
  bool added = false, removed = false;
  @override
  Future<Object?> snapshot(String id, String? token, int limit) async {
    final page = Map<String, dynamic>.from(
      await super.snapshot(id, token, limit) as Map,
    );
    final items = List<dynamic>.from(page['items'] as List);
    page['items'] = items;
    if (added) {
      (items.first as Map)['messageSequenceAtH'] = '2';
      final row = Map<String, dynamic>.from(items.last as Map)
        ..['messageId'] = 'live-message'
        ..['messageSequence'] = '2';
      if (removed) {
        row['state'] = 'RECALLED';
        row['objectVersion'] = '2';
        row.remove('content');
        row.remove('details');
      }
      items.add(row);
    }
    return page;
  }
}

class FailingCancelSystem extends FakeSystem {
  bool failCancel = false;
  int cancelled = 0;
  @override
  Future<CapabilityResult<void>> cancel(String id) async {
    cancelled++;
    if (failCancel) return const CapabilityResult(CapabilityStatus.failed);
    return super.cancel(id);
  }
}

class DelayedShowSystem extends FakeSystem {
  final entered = Completer<void>();
  @override
  Future<CapabilityResult<void>> show(String id, NotificationRoute route) {
    if (!entered.isCompleted) entered.complete();
    return super.show(id, route);
  }
}

void main() {
  test(
    'late native SHOW is cancelled when the real controller has already applied recall',
    () async {
      final root = await Directory.systemTemp.createTemp(
        'meshx-delayed-notification-',
      );
      final store = FileChatStore(directory: root), api = ControlledApi();
      final wire = CapturingConnection(api),
          transport = NotificationTransport();
      final system = DelayedShowSystem()..showGate = Completer<void>();
      final chat = ChatController(
        discovery: FakeDiscovery(),
        store: store,
        enableRecovery: true,
        apiFactory: (_) => api,
        connectionFactory: (_) => wire,
        recoveryTransportFactory: (_) => transport,
      );
      final platform = PlatformCoordinator(
        chat: chat,
        lifecycle: FakeLifecycle(),
        notifications: system,
        files: system,
        sharePort: system,
        network: system,
        settings: system,
      )..start();
      addTearDown(() async {
        if (!system.showGate!.isCompleted) system.showGate!.complete();
        await platform.drain();
        platform.dispose();
        chat.dispose();
        await root.delete(recursive: true);
      });
      await chat.login('http://127.0.0.1', 'user', 'password');
      await platform.drain();
      transport.added = true;
      wire.events.add({
        'event': 'CHAT_DELIVER',
        'payload': {'messageId': 'live-message'},
      });
      await system.entered.future.timeout(const Duration(seconds: 5));
      transport.removed = true;
      await chat.refreshConversations();
      expect(chat.recoveryNotificationAllows('live-message', room.id), false);
      system.showGate!.complete();
      await platform.drain();
      expect(system.visible, isEmpty);
      final stored = (await store.loadRecovery('http://127.0.0.1|1'))!.snapshot;
      expect(stored['notificationRecovery']['routes'], isEmpty);
      // A later drain may acknowledge cancellation, but the stale SHOW can never return.
      expect(
        (stored['notificationRecovery']['effects'] as List).where(
          (e) => e['kind'] == 'SHOW',
        ),
        isEmpty,
      );
    },
  );
  test(
    'real store persists live notification, invalidates its tap, and retries failed OS cancellation',
    () async {
      final root = await Directory.systemTemp.createTemp(
        'meshx-notification-recovery-',
      );
      final store = FileChatStore(directory: root),
          api = ControlledApi(),
          wire = CapturingConnection(api),
          transport = NotificationTransport(),
          system = FailingCancelSystem();
      final chat = ChatController(
        discovery: FakeDiscovery(),
        store: store,
        enableRecovery: true,
        apiFactory: (_) => api,
        connectionFactory: (_) => wire,
        recoveryTransportFactory: (_) => transport,
      );
      final platform = PlatformCoordinator(
        chat: chat,
        lifecycle: FakeLifecycle(),
        notifications: system,
        files: system,
        sharePort: system,
        network: system,
        settings: system,
      )..start();
      addTearDown(() async {
        platform.dispose();
        chat.dispose();
        await root.delete(recursive: true);
      });
      await chat.login('http://127.0.0.1', 'user', 'password');
      await platform.drain();
      expect(system.visible, isEmpty);
      expect(chat.conversations.single.preview, 'verified body');
      expect(chat.conversations.single.unread, 1);
      transport.added = true;
      wire.events.add({
        'event': 'CHAT_DELIVER',
        'payload': {'messageId': 'live-message'},
      });
      for (var i = 0; i < 200 && system.visible.isEmpty; i++) {
        await Future<void>.delayed(const Duration(milliseconds: 5));
      }
      expect(
        system.visible,
        isNotEmpty,
        reason:
            'phase=${chat.recoveryPhase} error=${chat.error} code=${chat.lastErrorCode} effects=${chat.recoveryNotificationEffects} platform=${platform.notificationStatus.reason} disk=${jsonEncode((await store.loadRecovery("http://127.0.0.1|1"))?.snapshot["notificationRecovery"])}',
      );
      await platform.drain();
      expect(system.visible.single, startsWith('meshx-'));
      expect(chat.conversations.single.unread, 2);
      final route = system.shownRoutes.single;
      expect(route.messageId, 'live-message');
      expect(route.conversationId, room.id);
      var stored = (await store.loadRecovery('http://127.0.0.1|1'))!.snapshot;
      expect(
        (stored['notificationRecovery']['routes'] as List).single['messageId'],
        'live-message',
      );
      expect(stored['notificationRecovery']['effects'], isEmpty);
      system.failCancel = true;
      transport.removed = true;
      wire.events.add({
        'event': 'CHAT_RECALL',
        'payload': {'messageId': 'live-message'},
      });
      await eventually(() => system.cancelled > 0);
      await platform.drain();
      expect(chat.conversations.single.preview, '这条消息已撤回');
      expect(chat.conversations.single.unread, 1);
      expect(
        chat.recoveryNotificationAllows(route.messageId, route.conversationId),
        false,
      );
      var navigated = 0;
      platform.setConversationNavigationHandler(() => navigated++);
      system.tapEvents.add(route);
      expect(navigated, 0);
      stored = (await store.loadRecovery('http://127.0.0.1|1'))!.snapshot;
      expect(stored['notificationRecovery']['routes'], isEmpty);
      expect(
        (stored['notificationRecovery']['effects'] as List).single['kind'],
        'CANCEL',
      );
      expect(
        jsonEncode(stored['notificationRecovery']),
        isNot(contains('verified body')),
      );
      system.failCancel = false;
      await chat.refreshConversations();
      await platform.drain();
      expect(system.visible, isEmpty);
      stored = (await store.loadRecovery('http://127.0.0.1|1'))!.snapshot;
      expect(stored['notificationRecovery']['effects'], isEmpty);
    },
  );
}
