import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/data/broadcast_models.dart';
import 'package:meshx_flutter_probe/data/friends_models.dart';
import 'package:meshx_flutter_probe/data/groups_models.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';
import 'package:meshx_flutter_probe/data/models.dart';
import 'package:meshx_flutter_probe/ui/app.dart';
import 'package:meshx_flutter_probe/ui/theme.dart';
import 'package:meshx_flutter_probe/application/platform_coordinator.dart';
import 'platform_fakes.dart';

class NavigationApi extends MeshXApi {
  NavigationApi() : super(Uri.parse('https://navigation.invalid')) {
    session = const Session(1, '移动用户', 'synthetic-token');
    node = NodeInfo(origin, '本地空间', '', '/api/v1', '/ws/chat');
  }

  List<FriendRequestItem> pendingRequests = [];
  List<BroadcastSummary> pendingBroadcasts = [];

  @override
  Future<List<FriendContact>> friends() async => const [];

  @override
  Future<List<FriendRequestItem>> friendRequests() async => pendingRequests;

  @override
  Future<List<MeshXGroup>> groups() async => const [];

  @override
  Future<List<BroadcastSummary>> broadcasts({bool pending = false}) async =>
      pendingBroadcasts;

  @override
  Future<List<ChatMessage>> history(
    String id, {
    int? before,
    int limit = 50,
  }) async => [];
}

void main() {
  testWidgets(
    'contact and broadcast navigation counts stay distinct and update after handling',
    (tester) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final semantics = tester.ensureSemantics();
      final api = NavigationApi();
      api.pendingRequests = const [
        FriendRequestItem(
          id: 1,
          fromUserId: 2,
          toUserId: 1,
          message: '',
          status: 0,
        ),
      ];
      api.pendingBroadcasts = [
        for (var id = 1; id <= 3; id++)
          BroadcastSummary(
            id: id,
            senderId: 2,
            title: '任务',
            content: '',
            status: 'ACTIVE',
            priority: 'NORMAL',
            confirmationRequired: true,
            requireImageProof: false,
            requireLocationProof: false,
          ),
      ];
      final chat = ChatController()
        ..api = api
        ..online = true;
      try {
        final nav = find.byKey(const Key('primary-bottom-navigation'));
        for (final brightness in [Brightness.light, Brightness.dark]) {
          await tester.pumpWidget(
            MaterialApp(
              theme: meshXTheme(brightness),
              builder: (context, child) => MediaQuery(
                data: MediaQuery.of(
                  context,
                ).copyWith(textScaler: const TextScaler.linear(1.4)),
                child: child!,
              ),
              home: ChatPage(controller: chat, onToggleTheme: () {}),
            ),
          );
          await tester.pumpAndSettle();
          expect(
            find.descendant(
              of: nav,
              matching: find.bySemanticsLabel(RegExp('1 条待处理好友申请')),
            ),
            findsOneWidget,
          );
          expect(
            find.descendant(
              of: nav,
              matching: find.bySemanticsLabel(RegExp('3 条待办广播')),
            ),
            findsOneWidget,
          );
          expect(tester.takeException(), isNull);
        }
        api.pendingRequests = [];
        chat.notifyFriendRequestsChanged();
        await tester.pumpAndSettle();
        expect(
          find.descendant(of: nav, matching: find.text('1')),
          findsNothing,
        );
        expect(
          find.descendant(of: nav, matching: find.text('3')),
          findsOneWidget,
        );
        api.pendingBroadcasts = [];
        chat.notifyBroadcastsChanged();
        await tester.pumpAndSettle();
        expect(
          find.descendant(of: nav, matching: find.byType(Badge)),
          findsNothing,
        );
        expect(tester.takeException(), isNull);
      } finally {
        await tester.pumpWidget(const SizedBox.shrink());
        semantics.dispose();
        chat.dispose();
      }
    },
  );

  testWidgets(
    'message navigation badge follows current synchronized unread totals',
    (tester) async {
      final semantics = tester.ensureSemantics();
      try {
        tester.view.physicalSize = const Size(320, 640);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        final chat = ChatController()..api = NavigationApi();
        addTearDown(chat.dispose);
        chat.online = true;
        chat.conversations = const [
          Conversation(
            id: 'private:1:2',
            targetId: 2,
            kind: 'private',
            title: '同事',
            unread: 6,
          ),
          Conversation(
            id: 'group:1',
            targetId: 1,
            kind: 'group',
            title: '项目组',
            unread: 100,
          ),
        ];
        Future<void> render(Brightness brightness) async {
          await tester.pumpWidget(
            MaterialApp(
              theme: meshXTheme(brightness),
              builder: (context, child) => MediaQuery(
                data: MediaQuery.of(
                  context,
                ).copyWith(textScaler: const TextScaler.linear(1.4)),
                child: child!,
              ),
              home: ChatPage(controller: chat, onToggleTheme: () {}),
            ),
          );
          await tester.pumpAndSettle();
        }

        final nav = find.byKey(const Key('primary-bottom-navigation'));
        Finder badgeText(String text) =>
            find.descendant(of: nav, matching: find.text(text));
        for (final brightness in [Brightness.light, Brightness.dark]) {
          await render(brightness);
          expect(badgeText('99+'), findsOneWidget);
          expect(
            find.descendant(
              of: nav,
              matching: find.bySemanticsLabel(RegExp('106 条未读消息')),
            ),
            findsOneWidget,
          );
          expect(tester.takeException(), isNull);
        }
        // Switching destination preserves the message badge; it does not add other badge types.
        await tester.tap(find.text('联系人'));
        await tester.pumpAndSettle();
        expect(badgeText('99+'), findsOneWidget);
        chat.online = false;
        await render(Brightness.dark);
        expect(
          find.descendant(of: nav, matching: find.byType(Badge)),
          findsNothing,
        );
        chat.online = true;
        chat.conversations = const [
          Conversation(
            id: 'private:1:2',
            targetId: 2,
            kind: 'private',
            title: '同事',
            unread: 2,
          ),
        ];
        await render(Brightness.dark);
        expect(badgeText('2'), findsOneWidget);
        chat.conversations = const [];
        await render(Brightness.dark);
        expect(
          find.descendant(of: nav, matching: find.byType(Badge)),
          findsNothing,
        );
        expect(tester.takeException(), isNull);
      } finally {
        semantics.dispose();
      }
    },
  );

  testWidgets('notification received before shell mount waits for navigation', (
    tester,
  ) async {
    final chat = ChatController()..api = NavigationApi();
    chat.online = true;
    const room = Conversation(
      id: 'private:1:2',
      targetId: 2,
      kind: 'private',
      title: '同事',
    );
    chat.conversations = [room];
    final system = FakeSystem();
    final platform = PlatformCoordinator(
      chat: chat,
      lifecycle: FakeLifecycle(),
      notifications: system,
      files: system,
      sharePort: system,
      network: system,
      settings: system,
    )..start();
    system.tapEvents.add(NotificationRoute(platform.owner!, room.id));
    await tester.pump();
    expect(chat.active, isNull);
    await tester.pumpWidget(
      MaterialApp(
        home: AnimatedBuilder(
          animation: chat,
          builder: (_, _) => ChatPage(
            controller: chat,
            platform: platform,
            onToggleTheme: () {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('composer')), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
    platform.dispose();
    chat.dispose();
    await system.dispose();
  });

  testWidgets('message notification opens chat from contacts', (tester) async {
    final chat = ChatController()..api = NavigationApi();
    chat.online = true;
    const room = Conversation(
      id: 'private:1:2',
      targetId: 2,
      kind: 'private',
      title: '同事',
    );
    chat.conversations = [room];
    final system = FakeSystem();
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
        home: AnimatedBuilder(
          animation: chat,
          builder: (_, _) => ChatPage(
            controller: chat,
            platform: platform,
            onToggleTheme: () {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('联系人'));
    await tester.pumpAndSettle();
    expect(platform.chatViewVisible, isFalse);
    system.tapEvents.add(NotificationRoute(platform.owner!, room.id));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('composer')), findsOneWidget);
    expect(platform.chatViewVisible, isTrue);
    await tester.pumpWidget(const SizedBox.shrink());
    platform.dispose();
    chat.dispose();
    await system.dispose();
  });

  testWidgets('contact search survives a round trip through messages', (
    tester,
  ) async {
    final chat = ChatController()..api = NavigationApi();
    addTearDown(chat.dispose);
    await tester.pumpWidget(
      MaterialApp(
        home: ChatPage(controller: chat, onToggleTheme: () {}),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('联系人'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('搜索'));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('friend-search-input')),
      '正在查找的同事',
    );
    await tester.tap(
      find.descendant(
        of: find.byType(NavigationBar),
        matching: find.text('消息'),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('联系人'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('friend-search-input')), findsOneWidget);
    expect(find.text('正在查找的同事'), findsOneWidget);
  });

  testWidgets(
    'P0 destinations stay visible and Android back returns to messages',
    (tester) async {
      tester.view.physicalSize = const Size(320, 568);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final chat = ChatController()..api = NavigationApi();
      addTearDown(chat.dispose);

      await tester.pumpWidget(
        MaterialApp(
          theme: meshXTheme(Brightness.light),
          home: ChatPage(controller: chat, onToggleTheme: () {}),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(const Key('primary-bottom-navigation')),
        findsOneWidget,
      );
      expect(find.text('消息'), findsWidgets);
      expect(find.text('联系人'), findsOneWidget);
      expect(find.text('群聊'), findsOneWidget);
      expect(find.text('广播'), findsOneWidget);
      expect(find.byKey(const Key('open-profile')), findsOneWidget);

      await tester.tap(find.text('联系人'));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('friends-list')), findsOneWidget);
      expect(find.byKey(const Key('friends-open-profile')), findsOneWidget);

      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('conversation-list')), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('primary destinations remain reachable with large text', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final chat = ChatController()..api = NavigationApi();
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
        home: ChatPage(controller: chat, onToggleTheme: () {}),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('群聊'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('groups-list')), findsOneWidget);
    await tester.tap(find.text('广播'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('broadcast-list')), findsOneWidget);
    expect(find.byKey(const Key('broadcasts-open-profile')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
