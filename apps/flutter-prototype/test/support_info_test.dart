import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/application/platform_coordinator.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';
import 'package:meshx_flutter_probe/platform/capability_codec.dart';
import 'package:meshx_flutter_probe/platform/system_capabilities.dart';
import 'package:meshx_flutter_probe/ui/support_info_page.dart';
import 'platform_fakes.dart';
import 'recovery_test.dart' show ControlledApi;

PlatformCoordinator coordinator(ChatController chat, FakeSystem system) =>
    PlatformCoordinator(
      chat: chat,
      lifecycle: FakeLifecycle(),
      notifications: system,
      files: system,
      sharePort: system,
      network: system,
      settings: system,
      runtimeInfo: system,
    )..start();

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    messenger.setMockMethodCallHandler(capabilityChannel, null);
    messenger.setMockMethodCallHandler(SystemChannels.platform, null);
  });

  test(
    'native runtime info accepts only bounded public version fields',
    () async {
      final native = NativeSystemCapabilities();
      messenger.setMockMethodCallHandler(
        capabilityChannel,
        (_) async => {
          'status': 'SUCCESS',
          'appVersion': '0.3.1',
          'buildNumber': '42',
          'osName': 'Android',
          'osVersion': '16 (API 36)',
        },
      );
      final accepted = await native.readRuntimeInfo();
      expect(accepted.value?.osVersion, '16 (API 36)');

      messenger.setMockMethodCallHandler(
        capabilityChannel,
        (_) async => {
          'status': 'SUCCESS',
          'appVersion': '/private/token',
          'buildNumber': '42',
          'osName': 'Android',
          'osVersion': '16',
        },
      );
      final rejected = await native.readRuntimeInfo();
      expect(rejected.status, CapabilityStatus.failed);
      expect(rejected.reason, 'invalidRuntimeInfo');
      await native.dispose();
    },
  );

  testWidgets(
    'support preview is usable on small large-text layout and copy matches preview',
    (tester) async {
      tester.view.physicalSize = const Size(320, 568);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      String? copied;
      messenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
        if (call.method == 'Clipboard.setData') {
          copied = (call.arguments as Map)['text'] as String?;
        }
        return null;
      });
      final system = FakeSystem();
      final chat = ChatController(discovery: FakeDiscovery())
        ..api = ControlledApi()
        ..online = true;
      chat.describe(const ApiException('secret detail', code: 503));
      final platform = coordinator(chat, system);
      addTearDown(() async {
        platform.dispose();
        chat.dispose();
        await system.dispose();
      });

      await tester.pumpWidget(
        MediaQuery(
          data: const MediaQueryData(textScaler: TextScaler.linear(1.5)),
          child: MaterialApp(
            home: SupportInfoPage(chat: chat, platform: platform),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('support-preview')), findsOneWidget);
      expect(find.textContaining('连接：ONLINE'), findsOneWidget);
      expect(find.textContaining('错误码：HTTP_503'), findsOneWidget);
      expect(find.textContaining('synthetic-token'), findsNothing);
      expect(find.textContaining('secret detail'), findsNothing);
      expect(find.textContaining('/private/'), findsNothing);
      await chat.pause();
      await tester.pump();
      expect(find.textContaining('连接：BACKGROUND'), findsOneWidget);
      await tester.tap(find.byKey(const Key('copy-support-info')));
      await tester.pump();
      expect(copied, contains('错误码：HTTP_503'));
      expect(copied, isNot(contains('synthetic-token')));
      expect(copied, isNot(contains('secret detail')));
      expect(tester.takeException(), isNull);
    },
  );
}
