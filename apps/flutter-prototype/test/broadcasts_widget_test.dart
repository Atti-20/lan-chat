import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/application/broadcasts_controller.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/data/broadcast_models.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';
import 'package:meshx_flutter_probe/data/models.dart';
import 'package:meshx_flutter_probe/ui/broadcasts_page.dart';
import 'package:meshx_flutter_probe/ui/theme.dart';
import 'package:meshx_flutter_probe/application/platform_coordinator.dart';
import 'platform_fakes.dart';

class WidgetBroadcastApi extends MeshXApi {
  WidgetBroadcastApi(this.value) : super(Uri.parse('https://widget.invalid')) {
    node = NodeInfo(origin, 'test', '', '/api/v1', '/ws/chat');
    session = const Session(1, 'Me', 'token');
  }
  BroadcastDetail value;
  @override
  Future<BroadcastDetail> broadcastDetail(int broadcastId) async => value;
  @override
  Future<List<BroadcastSummary>> broadcasts({bool pending = false}) async => [
    value.broadcast,
  ];
  @override
  Future<BroadcastReceiverState> viewBroadcast(int broadcastId) async =>
      value.receiver!;
}

BroadcastDetail widgetDetail({
  bool location = false,
  bool image = false,
  List<String> images = const [],
  String content = '请到指定区域集合并回执。',
  bool completed = false,
  bool required = true,
}) => BroadcastDetail.fromJson({
  'broadcast': {
    'id': 8,
    'senderId': 2,
    'title': '应急集合',
    'content': content,
    'status': 'ACTIVE',
    'priority': 'EMERGENCY',
    'confirmationRequired': required,
    'requireImageProof': image,
    'requireLocationProof': location,
  },
  'receiver': {
    'id': 18,
    'broadcastId': 8,
    'userId': 1,
    'targetStatus': 'ACTIVE',
    'confirmStatus': 'PENDING',
    'viewedAt': '2026-09-13T10:00:00',
    'confirmedAt': completed ? '2026-09-13T10:05:00' : null,
  },
  'sender': {'nickname': '值班员'},
  'contentEvidence': {'imageUrls': images},
  'confirmationOptions': ['RECEIVED', 'EXECUTED'],
});

void main() {
  for (final brightness in Brightness.values) {
    testWidgets('image error is actionable at 320px in $brightness', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(320, 720);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final api = WidgetBroadcastApi(
        widgetDetail(
          image: true,
          content: List.filled(16, '请阅读集合说明后上传凭证。').join('\n'),
        ),
      );
      final chat = ChatController()..api = api;
      final system = FakeSystem()
        ..pickGate = (Completer<CapabilityResult<SelectedFile>>()
          ..complete(
            const CapabilityResult(
              CapabilityStatus.failed,
              reason: 'fileTooLarge',
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
      await platform.drain();
      final controller = BroadcastsController(
        chat: chat,
        api: api,
        platform: platform,
        changes: const Stream.empty(),
      )..detail = api.value;
      await tester.pumpWidget(
        MaterialApp(
          theme: meshXTheme(brightness),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(
              context,
            ).copyWith(textScaler: const TextScaler.linear(1.4)),
            child: child!,
          ),
          home: BroadcastDetailPage(controller: controller),
        ),
      );
      final complete = find.byKey(const Key('complete-broadcast'));
      final scrollable = find
          .descendant(
            of: find.byKey(const Key('broadcast-detail')),
            matching: find.byType(Scrollable),
          )
          .first;
      await tester.scrollUntilVisible(complete, 250, scrollable: scrollable);
      await tester.tap(complete);
      await tester.pumpAndSettle();
      final error = find.text('广播凭证图片不能超过 5MB，请重新选择');
      expect(
        find.descendant(of: find.byType(SnackBar), matching: error),
        findsOneWidget,
      );
      expect(find.byType(SnackBar).hitTestable(), findsOneWidget);
      ScaffoldMessenger.of(
        tester.element(find.byType(SnackBar)),
      ).removeCurrentSnackBar();
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(error, -250, scrollable: scrollable);
      await tester.ensureVisible(error);
      expect(error, findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.tap(find.byTooltip('关闭提示'));
      await tester.pumpAndSettle();
      expect(error, findsNothing);
      system.pickGate = null;
      await tester.scrollUntilVisible(complete, 250, scrollable: scrollable);
      expect(tester.widget<FilledButton>(complete).onPressed, isNotNull);
      await tester.tap(complete);
      await tester.pumpAndSettle();
      expect(system.pickCount, 2);
      expect(controller.error, isNull);
      expect(controller.detail!.canSubmit, isTrue);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
      controller.dispose();
      platform.dispose();
      chat.dispose();
      await system.dispose();
    });
  }
  for (final completed in [true, false]) {
    testWidgets(
      completed
          ? 'completed receipt has no repeat action'
          : 'optional notice needs no completion action',
      (tester) async {
        final api = WidgetBroadcastApi(
          widgetDetail(completed: completed, required: completed),
        );
        final chat = ChatController()..api = api;
        final controller = BroadcastsController(
          chat: chat,
          api: api,
          changes: const Stream.empty(),
        )..detail = api.value;
        await tester.pumpWidget(
          MaterialApp(home: BroadcastDetailPage(controller: controller)),
        );
        expect(find.byKey(const Key('complete-broadcast')), findsNothing);
        expect(find.byKey(const Key('confirm-EXECUTED')), findsNothing);
        expect(
          find.textContaining(completed ? '已提交回执' : '不要求确认'),
          findsOneWidget,
        );
        await tester.pumpWidget(const SizedBox.shrink());
        controller.dispose();
        chat.dispose();
      },
    );
  }
  testWidgets('broadcast detail remains reachable at 320px', (tester) async {
    tester.view.physicalSize = const Size(320, 720);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final api = WidgetBroadcastApi(widgetDetail());
    final chat = ChatController()..api = api;
    final controller = BroadcastsController(
      chat: chat,
      api: api,
      changes: const Stream.empty(),
    )..detail = api.value;
    addTearDown(() {
      controller.dispose();
      chat.dispose();
    });
    await tester.pumpWidget(
      MaterialApp(home: BroadcastDetailPage(controller: controller)),
    );
    expect(find.text('应急集合'), findsOneWidget);
    expect(find.text('值班员 · 紧急广播'), findsOneWidget);
    expect(find.text('已收到'), findsOneWidget);
    expect(find.byKey(const Key('confirm-EXECUTED')), findsNothing);
    expect(find.textContaining('当前回执：等待确认'), findsOneWidget);
    expect(find.byKey(const Key('confirm-RECEIVED')), findsOneWidget);
    expect(find.byKey(const Key('complete-broadcast')), findsOneWidget);
    await tester.drag(
      find.byKey(const Key('broadcast-detail')),
      const Offset(0, -300),
    );
    await tester.pump();
    expect(tester.takeException(), isNull);
  });

  testWidgets('location-required broadcast is visibly read-only', (
    tester,
  ) async {
    final api = WidgetBroadcastApi(widgetDetail(location: true));
    final chat = ChatController()..api = api;
    final controller = BroadcastsController(
      chat: chat,
      api: api,
      changes: const Stream.empty(),
    )..detail = api.value;
    addTearDown(() {
      controller.dispose();
      chat.dispose();
    });
    await tester.pumpWidget(
      MaterialApp(home: BroadcastDetailPage(controller: controller)),
    );
    expect(find.textContaining('使用 Web 端办理'), findsOneWidget);
    expect(find.byKey(const Key('complete-broadcast')), findsNothing);
    expect(find.byKey(const Key('confirm-EXECUTED')), findsNothing);
  });
}
