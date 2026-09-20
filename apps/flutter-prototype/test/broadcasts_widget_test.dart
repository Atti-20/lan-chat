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

class BroadcastListApi extends MeshXApi {
  BroadcastListApi({required this.all, required this.pending})
    : super(Uri.parse('https://broadcast-list.invalid')) {
    node = NodeInfo(origin, 'test', '', '/api/v1', '/ws/chat');
    session = const Session(1, 'Me', 'token');
  }

  final List<BroadcastSummary> all;
  final List<BroadcastSummary> pending;

  @override
  Future<List<BroadcastSummary>> broadcasts({bool pending = false}) async =>
      pending ? this.pending : all;
}

BroadcastSummary broadcastSummary({
  required int id,
  required String title,
  String confirmation = 'PENDING',
}) => BroadcastSummary.fromJson({
  'id': id,
  'senderId': 2,
  'title': title,
  'content': '来自节点的真实任务说明。',
  'status': 'ACTIVE',
  'priority': 'IMPORTANT',
  'confirmationRequired': true,
  'requireImageProof': false,
  'requireLocationProof': false,
  'currentUserConfirmStatus': confirmation,
  'deadlineAt': '2026-12-31T18:00:00',
});

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
  testWidgets(
    'broadcast filters render only node-authoritative pending and completed work',
    (tester) async {
      tester.view.physicalSize = const Size(320, 720);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final pending = broadcastSummary(id: 8, title: '等待办理的广播');
      final completed = broadcastSummary(
        id: 9,
        title: '节点已执行的广播',
        confirmation: 'EXECUTED',
      );
      final received = broadcastSummary(
        id: 10,
        title: '已收到的历史广播',
        confirmation: 'RECEIVED',
      );
      final api = BroadcastListApi(
        pending: [pending],
        all: [pending, completed, received],
      );
      final chat = ChatController()..api = api;
      addTearDown(chat.dispose);
      await tester.pumpWidget(
        MaterialApp(
          theme: meshXTheme(Brightness.dark),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(
              context,
            ).copyWith(textScaler: const TextScaler.linear(1.4)),
            child: child!,
          ),
          home: BroadcastsPage(chat: chat),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('broadcast-list')), findsOneWidget);
      expect(find.byKey(const Key('broadcast-8-pending')), findsOneWidget);
      expect(find.text('节点已执行的广播'), findsNothing);
      final list = tester.widget<ListView>(
        find.byKey(const Key('broadcast-list')),
      );
      final padding = list.padding! as EdgeInsets;
      expect(padding.bottom, 88);

      await tester.tap(find.byKey(const Key('broadcast-filter-completed')));
      await tester.pumpAndSettle();
      expect(find.text('节点已执行的广播'), findsOneWidget);
      expect(find.text('已收到的历史广播'), findsNothing);

      await tester.tap(find.byKey(const Key('broadcast-filter-all')));
      await tester.pumpAndSettle();
      expect(find.text('等待办理的广播'), findsOneWidget);
      expect(find.text('节点已执行的广播'), findsOneWidget);
      expect(find.text('已收到的历史广播'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

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
      final complete = find.byKey(
        const Key('complete-broadcast'),
        skipOffstage: false,
      );
      final detailList = find.byKey(const Key('broadcast-detail'));
      for (
        var attempt = 0;
        attempt < 4 && complete.evaluate().isEmpty;
        attempt++
      ) {
        await tester.drag(detailList, const Offset(0, -300));
        await tester.pumpAndSettle();
      }
      await tester.ensureVisible(complete);
      await tester.pumpAndSettle();
      await tester.tap(complete);
      await tester.pumpAndSettle();
      final error = find.text('广播凭证图片不能超过 5MB，请重新选择', skipOffstage: false);
      expect(
        find.descendant(of: find.byType(SnackBar), matching: error),
        findsOneWidget,
      );
      expect(find.byType(SnackBar).hitTestable(), findsOneWidget);
      ScaffoldMessenger.of(
        tester.element(find.byType(SnackBar)),
      ).removeCurrentSnackBar();
      await tester.pumpAndSettle();
      await tester.drag(detailList, const Offset(0, 600));
      await tester.pumpAndSettle();
      await tester.drag(detailList, const Offset(0, 600));
      await tester.pumpAndSettle();
      expect(error, findsOneWidget);
      expect(tester.takeException(), isNull);
      final visibleDismiss = find.byKey(const Key('broadcast-notice-dismiss'));
      expect(visibleDismiss.hitTestable(), findsOneWidget);
      await tester.tap(visibleDismiss);
      await tester.pumpAndSettle();
      expect(error, findsNothing);
      system.pickGate = null;
      for (
        var attempt = 0;
        attempt < 4 && complete.evaluate().isEmpty;
        attempt++
      ) {
        await tester.drag(detailList, const Offset(0, -300));
        await tester.pumpAndSettle();
      }
      await tester.ensureVisible(complete);
      await tester.pumpAndSettle();
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
    expect(find.text('当前回执'), findsOneWidget);
    expect(find.text('等待确认'), findsOneWidget);
    expect(find.byKey(const Key('confirm-RECEIVED')), findsOneWidget);
    expect(find.byKey(const Key('complete-broadcast')), findsOneWidget);
    await tester.drag(
      find.byKey(const Key('broadcast-detail')),
      const Offset(0, -300),
    );
    await tester.pump();
    expect(tester.takeException(), isNull);
  });

  testWidgets('location-required broadcast explains capture at submission', (
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
    expect(find.textContaining('请求一次当前位置'), findsOneWidget);
    expect(find.byKey(const Key('complete-broadcast')), findsOneWidget);
    expect(find.byKey(const Key('confirm-EXECUTED')), findsNothing);
  });
}
