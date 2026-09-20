import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/data/broadcast_models.dart';
import 'package:meshx_flutter_probe/data/friends_models.dart';
import 'package:meshx_flutter_probe/data/groups_models.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';
import 'package:meshx_flutter_probe/data/models.dart';
import 'package:meshx_flutter_probe/ui/app.dart';

class NavigationVisualApi extends MeshXApi {
  NavigationVisualApi() : super(Uri.parse('https://navigation.invalid')) {
    session = const Session(1, '移动用户', 'synthetic-token');
    node = NodeInfo(origin, '本地空间', '', '/api/v1', '/ws/chat');
  }

  @override
  Future<List<FriendContact>> friends() async => const [];

  @override
  Future<List<FriendRequestItem>> friendRequests() async => const [];

  @override
  Future<List<MeshXGroup>> groups() async => const [];

  @override
  Future<List<BroadcastSummary>> broadcasts({bool pending = false}) async =>
      const [];

  @override
  Future<List<ChatMessage>> history(
    String id, {
    int? before,
    int limit = 50,
  }) async => [];
}

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('Android renders every P0 navigation destination', (
    tester,
  ) async {
    final chat = ChatController()..api = NavigationVisualApi();
    binding.reportData = {
      'metrics': {
        'platform': Platform.isAndroid ? 'android' : 'other',
        'schema': 'meshx.b02-navigation-visual/1',
        'fixture': 'synthetic-empty-states',
        'releaseEvidence': false,
      },
    };

    await tester.pumpWidget(
      MeshXApp(controller: chat, initialThemeMode: ThemeMode.light),
    );
    await tester.pumpAndSettle();
    if (Platform.isAndroid) {
      await binding.convertFlutterSurfaceToImage();
      await tester.pump();
    }

    await binding.takeScreenshot('android-b02-messages');
    for (final destination in const ['联系人', '群聊', '广播']) {
      await tester.tap(find.text(destination));
      await tester.pumpAndSettle();
      await binding.takeScreenshot(
        'android-b02-${switch (destination) {
          '联系人' => 'contacts',
          '群聊' => 'groups',
          _ => 'broadcasts',
        }}',
      );
    }
    expect(find.byKey(const Key('primary-bottom-navigation')), findsOneWidget);
    await tester.tap(find.text('联系人'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('搜索'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('friend-search-input')), '同事');
    FocusManager.instance.primaryFocus?.unfocus();
    await tester.pumpAndSettle();
    await tester.tap(
      find.descendant(
        of: find.byType(NavigationBar),
        matching: find.text('消息'),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('联系人'));
    await tester.pumpAndSettle();
    expect(find.text('同事'), findsOneWidget);
    await binding.takeScreenshot('android-b02-search-retained');
    await tester.tap(
      find.descendant(
        of: find.byType(NavigationBar),
        matching: find.text('消息'),
      ),
    );
    await tester.pumpAndSettle();
    const room = Conversation(
      id: 'group:42',
      targetId: 42,
      kind: 'group',
      title: '导航验证群',
    );
    await chat.select(room);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('composer')), findsOneWidget);
    expect(find.byKey(const Key('primary-bottom-navigation')), findsNothing);
    await binding.takeScreenshot('android-b02-conversation');
    await tester.tap(find.byKey(const Key('back-conversations')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('primary-bottom-navigation')), findsOneWidget);
    await tester.tap(find.byKey(const Key('theme-toggle')));
    await tester.pumpAndSettle();
    await binding.takeScreenshot('android-b02-messages-dark');
    expect(tester.takeException(), isNull);
  });
}
