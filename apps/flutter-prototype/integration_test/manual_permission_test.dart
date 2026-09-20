import 'package:meshx_flutter_probe/platform/discovery.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/ui/app.dart';

// Start on Android 17 with local-network permission revoked. The host operator
// chooses Deny once and Allow once in the actual system dialog; no adb grant.
void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets(
    'manual node connection handles denied then granted LAN permission',
    (tester) async {
      const origin = String.fromEnvironment('MESHX_NODE');
      const username = String.fromEnvironment('PROBE_USERNAME');
      const password = String.fromEnvironment('PROBE_PASSWORD');
      const groupId = String.fromEnvironment('PROBE_GROUP_ID');
      final c = ChatController(
        discovery: NativeNodeDiscovery(),
        allowLocalHttp: true,
      );
      final metrics = <String, dynamic>{'platform': 'android', 'mode': 'debug'};
      binding.reportData = {'metrics': metrics};
      await tester.pumpWidget(
        MeshXApp(controller: c, initialThemeMode: ThemeMode.light),
      );
      await binding.convertFlutterSurfaceToImage();
      await tester.pumpAndSettle();

      Future<void> submit() async {
        for (final pair in [
          ('node-origin', origin),
          ('username', username),
          ('password', password),
        ]) {
          final field = find.byKey(Key(pair.$1));
          await tester.ensureVisible(field);
          await tester.enterText(field, pair.$2);
        }
        await tester.ensureVisible(find.byKey(const Key('login')));
        await tester.tap(find.byKey(const Key('login')));
        await tester.pump();
      }

      Future<void> waitFor(bool Function() done) async {
        final deadline = DateTime.now().add(const Duration(seconds: 90));
        while (!done() && DateTime.now().isBefore(deadline)) {
          await tester.pump(const Duration(milliseconds: 100));
        }
        expect(done(), isTrue, reason: c.error);
      }

      await submit();
      debugPrint('PERMISSION_CHECK: choose Deny in the system dialog.');
      await waitFor(() => !c.busy);
      expect(c.session, isNull);
      expect(c.error, contains('权限'));
      metrics['denialBlocksLogin'] = true;
      await tester.pumpAndSettle();
      await binding.takeScreenshot('android-manual-permission-denied');

      await submit();
      debugPrint('PERMISSION_CHECK: choose Allow in the system dialog.');
      await waitFor(() => c.online && !c.busy);
      metrics['manualConnectionOnlineAfterGrant'] = true;
      final room = c.conversations.firstWhere(
        (room) => room.id == 'group:$groupId',
      );
      await c.select(room);
      await tester.pumpAndSettle();
      expect(c.messages, isNotEmpty);
      metrics['realHistoryAfterGrant'] = c.messages.length;
      await binding.takeScreenshot('android-manual-permission-granted');
      await c.pause();
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pumpAndSettle();
    },
  );
}
