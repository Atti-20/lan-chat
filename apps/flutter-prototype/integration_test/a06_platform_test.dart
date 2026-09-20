import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/application/platform_coordinator.dart';
import 'package:meshx_flutter_probe/platform/discovery.dart';
import 'package:meshx_flutter_probe/platform/storage.dart';
import 'package:meshx_flutter_probe/platform/system_capabilities.dart';
import 'package:meshx_flutter_probe/ui/app.dart';

// Real native adapters, OS lifecycle callbacks and a dedicated real Spring node.
// Operator lock/network actions are required; no synthetic lifecycle injection.
void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets('A06 native LAN and OS recovery', (tester) async {
    const origin = String.fromEnvironment('MESHX_NODE');
    const physical = bool.fromEnvironment('PROBE_PHYSICAL');
    const interactive = bool.fromEnvironment('PROBE_INTERACTIVE');
    final metrics = <String, dynamic>{
      'platform': Platform.isIOS ? 'ios' : 'android',
      'physicalDevice': physical,
      'mode': 'debug',
      'nativeEvents': <String>[],
    };
    binding.reportData = {'metrics': metrics};
    final discovery = NativeNodeDiscovery();
    final system = NativeSystemCapabilities();
    final lifecycle = FlutterLifecyclePort();
    final c = ChatController(
      discovery: discovery,
      store: FileChatStore(),
      credentials: NativeCredentialStore(),
      allowLocalHttp: true,
    );
    final coordinator = PlatformCoordinator(
      chat: c,
      lifecycle: lifecycle,
      notifications: system,
      files: system,
      sharePort: system,
      network: system,
      settings: system,
      disposeAdapters: system.dispose,
    );
    var backgroundCount = 0, foregroundCount = 0, networkCount = 0;
    final lifeSub = lifecycle.changes.listen((value) {
      (metrics['nativeEvents'] as List<String>).add(value.name);
      if (value == AppVisibility.background) backgroundCount++;
      if (value == AppVisibility.foreground) foregroundCount++;
      debugPrint('A06_OS_EVENT ${value.name}');
    });
    final netSub = system.changes.listen((result) {
      if (result.ok) networkCount++;
    });
    coordinator.start();
    system.start();
    await tester.pumpWidget(MeshXApp(controller: c, platform: coordinator));
    Future<void> until(bool Function() done, {int seconds = 120}) async {
      final end = DateTime.now().add(Duration(seconds: seconds));
      while (!done() && DateTime.now().isBefore(end)) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      expect(
        done(),
        isTrue,
        reason: 'Timed out; application error: ${c.error}',
      );
    }

    debugPrint(
      'A06_STAGE discovery: allow Local Network on the physical device',
    );
    unawaited(c.scan());
    await until(() => !c.scanning);
    metrics['discoveryStatus'] = c.discoveryStatus.name;
    metrics['candidateCount'] = c.candidates.length;
    metrics['fixtureDiscovered'] = c.candidates.any(
      (v) => Uri.parse(v).port == Uri.parse(origin).port,
    );
    debugPrint(
      'A06_DISCOVERY ${metrics['discoveryStatus']} candidates=${metrics['candidateCount']}',
    );
    await c.stopScan();
    expect(c.candidates, isEmpty);
    metrics['stopClearsCandidates'] = true;
    unawaited(
      c.login(
        origin,
        const String.fromEnvironment('PROBE_USERNAME'),
        const String.fromEnvironment('PROBE_PASSWORD'),
      ),
    );
    await until(() => c.online && !c.busy);
    final room = c.conversations.firstWhere(
      (r) => r.id == 'group:${const String.fromEnvironment('PROBE_GROUP_ID')}',
    );
    await c.select(room);
    metrics['initialMessageCount'] = c.messages.length;
    expect(c.messages, isNotEmpty);
    metrics['notificationPermissionBefore'] =
        (await system.permission()).status.name;
    final invalid = await system.share(
      const SelectedFile(
        handle: 'not-a-handle',
        name: 'invalid.txt',
        mime: 'text/plain',
        size: 1,
      ),
    );
    expect(invalid.ok, isFalse);
    metrics['invalidShareStatus'] = invalid.status.name;
    if (interactive) {
      debugPrint(
        'A06_STAGE lock: lock for 5 seconds then unlock and return to MeshX',
      );
      final bg = backgroundCount, fg = foregroundCount;
      await until(
        () => backgroundCount > bg && foregroundCount > fg && c.online,
        seconds: 240,
      );
      await coordinator.drain();
      metrics['osLockResumeOnline'] = true;
      debugPrint(
        'A06_STAGE network: disable Wi-Fi, return to MeshX, enable Wi-Fi and return',
      );
      final net = networkCount, priorBg = backgroundCount;
      await until(
        () => networkCount > net && backgroundCount > priorBg && c.online,
        seconds: 240,
      );
      await coordinator.drain();
      unawaited(c.scan());
      await until(() => !c.scanning);
      metrics['rediscoveredAfterNetworkChange'] = c.candidates.any(
        (v) => Uri.parse(v).port == Uri.parse(origin).port,
      );
      expect(metrics['rediscoveredAfterNetworkChange'], isTrue);
      metrics['osNetworkRecoveryOnline'] = true;
    }
    metrics['lifecycleTransitions'] = coordinator.lifecycleTransitions;
    metrics['networkEvents'] = networkCount;
    if (physical) expect(metrics['fixtureDiscovered'], isTrue);
    metrics['uniqueMessages'] =
        c.messages.map((v) => v.messageId).toSet().length == c.messages.length;
    expect(metrics['uniqueMessages'], isTrue);
    await lifeSub.cancel();
    await netSub.cancel();
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();
  }, timeout: const Timeout(Duration(minutes: 12)));
}
