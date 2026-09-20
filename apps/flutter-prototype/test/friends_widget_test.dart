import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/core/models.dart';
import 'package:meshx_flutter_probe/data/friends_models.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';
import 'package:meshx_flutter_probe/ui/friends_page.dart';
import 'package:meshx_flutter_probe/ui/theme.dart';

class WidgetFriendsApi extends MeshXApi {
  WidgetFriendsApi() : super(Uri.parse('https://test.invalid')) {
    node = NodeInfo(origin, 'test', '', '/api/v1', '/ws/chat');
    session = const Session(1, 'Me', 'synthetic-token');
  }
  var handled = 0, sent = 0;
  var pending = const [
    FriendRequestItem(
      id: 9,
      fromUserId: 2,
      toUserId: 1,
      message: 'hello',
      status: 0,
      senderName: 'Alice',
    ),
  ];
  @override
  Future<List<FriendContact>> friends() async => const [
    FriendContact(
      userId: 2,
      username: 'alice',
      nickname: 'Alice',
      remark: '',
      signature: 'LAN first',
      online: true,
    ),
  ];
  @override
  Future<List<Conversation>> conversations() async {
    cachedFriends = await friends();
    return const [];
  }

  @override
  Future<List<FriendRequestItem>> friendRequests() async => pending;
  @override
  Future<List<UserSearchResult>> searchUsers(String keyword) async => const [
    UserSearchResult(
      userId: 3,
      username: 'bob',
      nickname: 'Bob',
      signature: '',
    ),
  ];
  @override
  Future<void> handleFriendRequest(int requestId, bool accept) async {
    handled++;
    pending = [];
  }

  @override
  Future<void> sendFriendRequest(int toUserId, String message) async {
    sent++;
  }
}

class LongWidgetFriendsApi extends WidgetFriendsApi {
  LongWidgetFriendsApi() {
    pending = const [
      FriendRequestItem(
        id: 19,
        fromUserId: 22,
        toUserId: 1,
        message: '这是一段很长的好友申请验证消息，用于验证窄屏和大字体时操作仍然可达。',
        status: 0,
        senderName: '名字非常长的局域网协作伙伴',
      ),
    ];
  }
  @override
  Future<List<FriendContact>> friends() async => const [
    FriendContact(
      userId: 22,
      username: 'long_user_name',
      nickname: '名字非常长的局域网协作伙伴',
      remark: '',
      signature: '这是一段很长的签名，用于验证联系人列表在窄屏和大字体下不会溢出。',
      online: false,
    ),
  ];
}

void main() {
  testWidgets(
    'back from inline friend search returns to the contact directory',
    (tester) async {
      final api = WidgetFriendsApi();
      final chat = ChatController()..api = api;
      addTearDown(chat.dispose);
      await tester.pumpWidget(
        MaterialApp(
          theme: meshXTheme(Brightness.light),
          home: FriendsPage(chat: chat),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('friends-open-search')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('friend-search-list')), findsOneWidget);

      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('friends-list')), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('friends page exposes contacts, requests, and search actions', (
    tester,
  ) async {
    final api = WidgetFriendsApi();
    final chat = ChatController()..api = api;
    addTearDown(chat.dispose);
    await tester.pumpWidget(
      MaterialApp(
        theme: meshXTheme(Brightness.light),
        home: FriendsPage(chat: chat),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('friend-2')), findsOneWidget);

    await tester.tap(find.byKey(const Key('friend-requests-shortcut')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('friend-request-9')), findsOneWidget);
    await tester.tap(find.byKey(const Key('accept-request-9')));
    await tester.pumpAndSettle();
    expect(api.handled, 1);
    expect(find.text('暂无待处理申请'), findsOneWidget);

    await tester.tap(find.byKey(const Key('friends-open-search')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('friend-search-input')), 'Bob');
    await tester.tap(find.byKey(const Key('friend-search-submit')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('search-user-3')), findsOneWidget);
    await tester.tap(find.text('添加'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('send-friend-request')));
    await tester.pumpAndSettle();
    expect(api.sent, 1);
    expect(find.text('好友申请已发送'), findsOneWidget);
  });

  testWidgets('320px dark mode keeps long friend actions reachable', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 568);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final api = LongWidgetFriendsApi();
    final chat = ChatController()..api = api;
    addTearDown(chat.dispose);
    await tester.pumpWidget(
      MaterialApp(
        theme: meshXTheme(Brightness.dark),
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: const TextScaler.linear(1.5)),
          child: child!,
        ),
        home: FriendsPage(chat: chat),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('friend-22')), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.tap(find.byKey(const Key('friend-requests-shortcut')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('accept-request-19')), findsOneWidget);
    expect(find.byKey(const Key('reject-request-19')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
