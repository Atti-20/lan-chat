import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/application/platform_coordinator.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/ui/capabilities_page.dart';
import 'platform_fakes.dart';
import 'recovery_test.dart' show ControlledApi;

void main() {
  testWidgets('denied settings recovery exposes manual path and recheck', (
    tester,
  ) async {
    final system = FakeSystem()
      ..permissionStatus = CapabilityStatus.permissionPermanentlyDenied
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
    addTearDown(() async {
      platform.dispose();
      chat.dispose();
      await system.dispose();
    });
    await tester.pumpWidget(
      MaterialApp(home: CapabilitiesPage(platform: platform)),
    );
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('settings-fallback')), findsOneWidget);
    expect(find.byKey(const Key('recheck-permissions')), findsOneWidget);
    await tester.tap(find.byKey(const Key('open-system-settings')));
    await tester.pumpAndSettle();
    expect(find.textContaining('系统未打开应用设置'), findsOneWidget);
    expect(system.settingsCount, 1);
  });
}
