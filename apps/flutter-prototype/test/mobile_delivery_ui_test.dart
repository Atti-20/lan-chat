import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/ui/broadcast_management.dart';
import 'package:meshx_flutter_probe/application/platform_coordinator.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/core/models.dart';
import 'package:meshx_flutter_probe/data/broadcast_models.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';
import 'platform_fakes.dart';
import 'mobile_completion_test.dart' show CompletionApi;
import 'recovery_test.dart' show CapturingConnection, room, message;

class DeliveryApi extends CompletionApi {
  Json? published, binding;
  int unbinds = 0;
  @override
  Future<BroadcastSummary> publishBroadcast(Json draft) async {
    published = draft;
    throw const ApiException('节点拒绝发布');
  }

  @override
  Future<Json> pushConfiguration() async => {
    'enabled': true,
    'fcm': true,
    'firebase': {'projectId': 'test'},
  };
  @override
  Future<void> registerPush(Json value) async {
    binding = value;
  }

  @override
  Future<void> unregisterPush() async {
    unbinds++;
  }
}

class PushSystem extends FakeSystem implements PushPort {
  String? pushOwner;
  @override
  Future<CapabilityResult<Json>> registerPush(
    String owner,
    Json firebase,
  ) async {
    pushOwner = owner;
    return const CapabilityResult(
      CapabilityStatus.success,
      value: {
        'platform': 'FCM',
        'endpoint': 'test-device-token',
        'scope': 'test-device-scope',
      },
    );
  }

  @override
  Future<CapabilityResult<Json>> pushState() async =>
      CapabilityResult(CapabilityStatus.success, value: {'owner': pushOwner});
  @override
  Future<CapabilityResult<void>> clearPush() async {
    pushOwner = null;
    return const CapabilityResult(CapabilityStatus.success);
  }
}

void main() {
  testWidgets('rejected broadcast keeps title and body for correction', (
    tester,
  ) async {
    final api = DeliveryApi(), chat = ChatController();
    chat.api = api;
    addTearDown(chat.dispose);
    await tester.pumpWidget(
      MaterialApp(
        home: BroadcastComposePage(chat: chat, friends: const []),
      ),
    );
    await tester.enterText(find.byType(TextField).at(0), '保留标题');
    await tester.enterText(find.byType(TextField).at(1), '保留正文');
    FocusManager.instance.primaryFocus?.unfocus();
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('全节点范围（需要相应权限）'));
    await tester.tap(find.text('全节点范围（需要相应权限）'));
    await tester.pump();
    final publish = find.widgetWithText(FilledButton, '发布广播');
    await tester.ensureVisible(publish);
    await tester.pumpAndSettle();
    await tester.tap(publish);
    await tester.pumpAndSettle();
    expect(api.published?['title'], '保留标题');
    await tester.drag(find.byType(ListView), const Offset(0, 2000));
    await tester.pumpAndSettle();
    expect(find.text('保留标题'), findsOneWidget);
    expect(find.text('保留正文'), findsOneWidget);
    expect(find.textContaining('节点拒绝发布'), findsOneWidget);
  });
  test('push success requires node binding and disable removes it', () async {
    final api = DeliveryApi(), chat = ChatController();
    chat.api = api;
    final system = PushSystem()..permissionStatus = CapabilityStatus.success;
    final platform = PlatformCoordinator(
      chat: chat,
      lifecycle: FakeLifecycle(),
      notifications: system,
      files: system,
      sharePort: system,
      network: system,
      settings: system,
      push: system,
    )..start();
    addTearDown(() {
      platform.dispose();
      chat.dispose();
    });
    await platform.configurePush();
    expect(platform.pushStatus.ok, true);
    expect(api.binding?['platform'], 'FCM');
    expect(
      api.binding?.keys,
      unorderedEquals(['platform', 'endpoint', 'scope']),
    );
    await platform.disablePush();
    expect(api.unbinds, 1);
    expect(system.pushOwner, isNull);
  });
  test(
    'search focus waits for current authoritative history and clears on leave',
    () async {
      final api = CompletionApi(), wire = CapturingConnection(CompletionApi());
      final chat = ChatController(connectionFactory: (_) => wire)..api = api;
      addTearDown(chat.dispose);
      await chat.reconnect();
      chat.active = room;
      api.historyGate = Completer<List<ChatMessage>>();
      final hit = message(9);
      final result = chat.focusMessage(hit);
      expect(chat.focusedMessageId, isNull);
      api.historyGate!.complete([hit]);
      expect(await result, true);
      expect(chat.focusedSequence, 9);
      chat.leaveConversation();
      expect(chat.focusedMessageId, isNull);
    },
  );
}
