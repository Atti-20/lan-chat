import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/application/platform_coordinator.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/ui/app.dart';
import 'package:meshx_flutter_probe/ui/theme.dart';
import 'attachments_feature_test.dart';
import 'platform_fakes.dart';

void main() {
  testWidgets('composer sends selected image then renders attachment actions', (
    tester,
  ) async {
    final api = AttachmentApi(), wire = AttachmentWire(api);
    final chat = ChatController(connectionFactory: (_) => wire)..api = api;
    await chat.reconnect();
    chat.active = attachmentConversation;
    final system = FakeSystem()
      ..selectedBytes = const [1, 2, 3]
      ..pickGate = (Completer<CapabilityResult<SelectedFile>>()
        ..complete(
          const CapabilityResult(
            CapabilityStatus.success,
            value: SelectedFile(
              handle: '00000000-0000-0000-0000-000000000001',
              name: 'meshx.png',
              mime: 'image/png',
              size: 3,
            ),
          ),
        ));
    final platform = PlatformCoordinator(
      chat: chat,
      lifecycle: FakeLifecycle(),
      notifications: system,
      files: system,
      sharePort: system,
      network: system,
      settings: system,
    )..start();
    await tester.pumpWidget(
      MaterialApp(
        theme: meshXTheme(Brightness.light),
        home: Scaffold(
          body: AnimatedBuilder(
            animation: chat,
            builder: (_, _) => MessagePane(
              key: ValueKey(chat.active?.id),
              controller: chat,
              platform: platform,
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.byKey(const Key('attach-file')));
    await tester.pumpAndSettle();
    expect(api.uploads, 1);
    expect(wire.frames.single['payload']['contentType'], 'image');
    expect(find.text('meshx.png'), findsOneWidget);
    expect(find.text('预览'), findsOneWidget);
    expect(find.text('保存/分享'), findsOneWidget);
    await tester.tap(find.text('预览'));
    await tester.pumpAndSettle();
    expect(api.downloads, 1);
    expect(find.byType(Image), findsOneWidget);
    await tester.tap(find.text('保存/分享'));
    await tester.pumpAndSettle();
    expect(system.shareCount, 1);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
    chat.dispose();
    platform.dispose();
    await system.dispose();
  });

  testWidgets('attachment action remains reachable at 320px with large text', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 568);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final api = AttachmentApi(), wire = AttachmentWire(api);
    final chat = ChatController(connectionFactory: (_) => wire)..api = api;
    await chat.reconnect();
    chat.active = attachmentConversation;
    final system = FakeSystem();
    final platform = attachmentPlatform(chat, system)..start();
    addTearDown(() async {
      platform.dispose();
      chat.dispose();
      await system.dispose();
    });
    await tester.pumpWidget(
      MaterialApp(
        theme: meshXTheme(Brightness.dark),
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: const TextScaler.linear(1.4)),
          child: child!,
        ),
        home: Scaffold(
          body: MessagePane(controller: chat, platform: platform),
        ),
      ),
    );
    expect(find.byKey(const Key('attach-file')), findsOneWidget);
    expect(find.byKey(const Key('composer')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
