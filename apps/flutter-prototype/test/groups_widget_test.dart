import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/data/groups_models.dart';
import 'package:meshx_flutter_probe/ui/groups_page.dart';
import 'package:meshx_flutter_probe/ui/theme.dart';
import 'groups_feature_test.dart';

class WidgetGroupsApi extends FakeGroupsApi {
  WidgetGroupsApi() {
    groupList = [memberGroup];
  }

  @override
  Future<MeshXGroup> createGroup(String name, Iterable<int> memberIds) async {
    creates++;
    createdMembers = memberIds.toList();
    return MeshXGroup(
      id: 9,
      name: name,
      ownerId: 1,
      avatar: '',
      announcement: '',
      maxMembers: 200,
      joinMode: 0,
    );
  }
}

void main() {
  testWidgets(
    'group directory search filters existing groups without changing membership',
    (tester) async {
      final api = WidgetGroupsApi(), chat = ChatController()..api = api;
      addTearDown(chat.dispose);
      await tester.pumpWidget(
        MaterialApp(
          theme: meshXTheme(Brightness.light),
          home: GroupsPage(chat: chat),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('group-search')), findsOneWidget);
      expect(find.byKey(const Key('group-8')), findsOneWidget);

      await tester.enterText(find.byKey(const Key('group-search')), '不存在');
      await tester.pump();
      expect(find.byKey(const Key('group-8')), findsNothing);
      expect(find.byKey(const Key('group-search-empty')), findsOneWidget);
      expect(api.groupList, [memberGroup]);
    },
  );

  testWidgets(
    'group page creates from friends and opens the new conversation',
    (tester) async {
      final api = WidgetGroupsApi(), chat = ChatController()..api = api;
      addTearDown(chat.dispose);
      await tester.pumpWidget(
        MaterialApp(
          theme: meshXTheme(Brightness.light),
          home: GroupsPage(chat: chat),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('group-8')), findsOneWidget);
      await tester.tap(find.byKey(const Key('create-group')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('group-name')), '新建讨论组');
      await tester.tap(find.byKey(const Key('group-friend-2')));
      await tester.tap(find.byKey(const Key('submit-group')));
      await tester.pumpAndSettle();
      expect(api.creates, 1);
      expect(api.createdMembers, [2]);
      expect(chat.active?.id, 'group:9');
    },
  );

  testWidgets('member details and confirmed leave remain reachable on 320px', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 568);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final api = WidgetGroupsApi(), chat = ChatController()..api = api;
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
        home: GroupsPage(chat: chat),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('group-8')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('group-member-2')), findsOneWidget);
    expect(find.byKey(const Key('group-member-1')), findsOneWidget);
    await tester.scrollUntilVisible(
      find.byKey(const Key('leave-group')),
      180,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(find.byKey(const Key('leave-group')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('confirm-leave-group')));
    await tester.pumpAndSettle();
    expect(api.leaves, 1);
    expect(find.text('暂无群聊，可从好友创建一个群聊'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
