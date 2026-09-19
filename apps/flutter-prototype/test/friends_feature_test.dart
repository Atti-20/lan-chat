import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/application/friends_controller.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/core/models.dart';
import 'package:meshx_flutter_probe/data/friends_models.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';

const contact = FriendContact(
  userId: 2,
  username: 'alice',
  nickname: 'Alice',
  remark: '',
  signature: 'LAN first',
  online: true,
);

const requestItem = FriendRequestItem(
  id: 9,
  fromUserId: 2,
  toUserId: 1,
  message: 'hello',
  status: 0,
  senderName: 'Alice',
);

class FakeFriendsApi extends MeshXApi {
  FakeFriendsApi() : super(Uri.parse('https://test.invalid')) {
    node = NodeInfo(origin, 'test', '', '/api/v1', '/ws/chat');
    session = const Session(1, 'Me', 'synthetic-token');
  }

  List<FriendContact> contacts = [contact];
  List<FriendRequestItem> pending = [requestItem];
  Object? failure;
  Completer<void>? sendGate;
  int sent = 0, handled = 0, deleted = 0, remarked = 0;

  @override
  Future<List<FriendContact>> friends() async => contacts;
  @override
  Future<List<FriendRequestItem>> friendRequests() async => pending;
  @override
  Future<List<ChatMessage>> history(
    String conversationId, {
    int? before,
    int limit = 50,
  }) async => [];
  @override
  Future<List<UserSearchResult>> searchUsers(String keyword) async => const [
    UserSearchResult(userId: 1, username: 'me', nickname: 'Me', signature: ''),
    UserSearchResult(
      userId: 3,
      username: 'bob',
      nickname: 'Bob',
      signature: '',
    ),
  ];

  void _check() {
    if (failure case final Object value) throw value;
  }

  @override
  Future<void> sendFriendRequest(int toUserId, String message) async {
    _check();
    sent++;
    await sendGate?.future;
  }

  @override
  Future<void> handleFriendRequest(int requestId, bool accept) async {
    _check();
    handled++;
    pending = [];
  }

  @override
  Future<void> deleteFriend(int friendId) async {
    _check();
    deleted++;
    contacts = [];
  }

  @override
  Future<void> setFriendRemark(int friendId, String remark) async {
    _check();
    remarked++;
  }
}

class RelationshipApi extends MeshXApi {
  RelationshipApi() : super(Uri.parse('http://127.0.0.1')) {
    node = NodeInfo(origin, 'test', '', '/api/v1', '/ws/chat');
    session = const Session(1, 'Me', 'synthetic-token');
  }
  bool related = true;
  static const room = Conversation(
    id: 'private:1:2',
    targetId: 2,
    kind: 'private',
    title: 'Alice',
  );
  @override
  Future<void> validateCurrentUser() async {}
  @override
  Future<List<Conversation>> conversations() async {
    cachedFriends = related ? [contact] : [];
    return [room];
  }

  @override
  Future<List<ChatMessage>> history(
    String conversationId, {
    int? before,
    int limit = 50,
  }) async => [];
}

class RelationshipConnection extends RealtimeConnection {
  RelationshipConnection(super.api);
  final frames = <Json>[];
  @override
  Future<void> connect() async {}
  @override
  Future<Json> synchronize(Map<String, int> positions) async => {
    'messages': <Json>[],
    'latestPositions': {for (final key in positions.keys) key: 0},
    'deniedConversationIds': <String>[],
    'hasMore': false,
  };
  @override
  void send(
    String event,
    Json payload, {
    String? clientMsgId,
    String? conversationId,
  }) {
    frames.add({'event': event, 'clientMsgId': clientMsgId});
  }
}

void main() {
  test('friend DTOs reject missing ids and unknown request states', () {
    expect(
      () => FriendContact.fromJson({'friendId': 0}),
      throwsFormatException,
    );
    expect(
      () => FriendRequestItem.fromJson({
        'id': 1,
        'fromUserId': 2,
        'toUserId': 1,
        'status': 9,
      }),
      throwsFormatException,
    );
  });

  test('friend APIs use the frozen v1 paths and request bodies', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final seen = <Json>[];
    server.listen((request) async {
      final body = await utf8.decoder.bind(request).join();
      seen.add({
        'method': request.method,
        'path': request.uri.path,
        'query': request.uri.queryParameters,
        'body': body.isEmpty ? null : jsonDecode(body),
      });
      Object? data;
      if (request.uri.path.endsWith('/friend/list')) {
        data = [
          {
            'friendId': 2,
            'username': 'alice',
            'nickname': 'Alice',
            'online': 1,
          },
        ];
      } else if (request.uri.path.endsWith('/friend/requests')) {
        data = [
          {
            'id': 9,
            'fromUserId': 2,
            'toUserId': 1,
            'message': 'hello',
            'status': 0,
          },
        ];
      } else if (request.uri.path.endsWith('/user/2')) {
        data = {'id': 2, 'username': 'alice', 'nickname': 'Alice'};
      } else if (request.uri.path.endsWith('/user/search')) {
        data = [
          {'id': 3, 'username': 'bob', 'nickname': 'Bob'},
        ];
      }
      request.response.write(jsonEncode({'code': 200, 'data': data}));
      await request.response.close();
    });
    final api = MeshXApi(Uri.parse('http://127.0.0.1:${server.port}'));
    api.node = NodeInfo(api.origin, 'test', '', '/api/v1', '/ws/chat');
    api.session = const Session(1, 'Me', 'synthetic-token');
    try {
      expect(await api.friends(), hasLength(1));
      expect((await api.friendRequests()).single.senderName, 'Alice');
      expect((await api.searchUsers(' Bob ')).single.userId, 3);
      await api.sendFriendRequest(3, ' hi ');
      await api.handleFriendRequest(9, true);
      await api.setFriendRemark(2, ' teammate ');
      await api.deleteFriend(2);
      expect(seen.map((item) => '${item['method']} ${item['path']}'), [
        'GET /api/v1/friend/list',
        'GET /api/v1/friend/requests',
        'GET /api/v1/user/2',
        'GET /api/v1/user/search',
        'POST /api/v1/friend/request',
        'POST /api/v1/friend/handle',
        'PUT /api/v1/friend/2/remark',
        'DELETE /api/v1/friend/2',
      ]);
      expect(seen[3]['query'], {'keyword': 'Bob'});
      expect(seen[4]['body'], {'toUserId': 3, 'message': 'hi'});
      expect(seen[5]['body'], {'requestId': 9, 'accept': true});
      expect(seen[6]['query'], {'remark': ' teammate '});
    } finally {
      api.close();
      await server.close(force: true);
    }
  });

  test(
    'friends controller isolates self-search and preserves API failures',
    () async {
      final api = FakeFriendsApi();
      var relationshipRefreshes = 0, requestChanges = 0;
      final controller = FriendsController(
        api: api,
        currentUserId: 1,
        changes: const Stream.empty(),
        onRelationshipsChanged: () async => relationshipRefreshes++,
        onRequestsChanged: () => requestChanges++,
      );
      addTearDown(controller.dispose);
      await controller.start();
      expect(controller.friends.single.userId, 2);
      await controller.search('bob');
      expect(controller.results.map((item) => item.userId), [3]);
      expect(await controller.handleRequest(requestItem, true), isTrue);
      expect(api.handled, 1);
      expect(requestChanges, 1);
      expect(await controller.handleRequest(requestItem, false), isTrue);
      expect(requestChanges, 2);
      expect(await controller.deleteFriend(contact), isTrue);
      expect(api.deleted, 1);
      expect(relationshipRefreshes, 2);
      api.failure = const ApiException('重复申请', code: 400);
      expect(
        await controller.sendRequest(
          const UserSearchResult(
            userId: 3,
            username: 'bob',
            nickname: 'Bob',
            signature: '',
          ),
          'hello',
        ),
        isFalse,
      );
      expect(controller.error, '重复申请');
    },
  );

  test(
    'duplicate friend mutations are rejected while the first is pending',
    () async {
      final api = FakeFriendsApi()..sendGate = Completer<void>();
      final controller = FriendsController(
        api: api,
        currentUserId: 1,
        changes: const Stream.empty(),
        onRelationshipsChanged: () async {},
      );
      addTearDown(controller.dispose);
      const user = UserSearchResult(
        userId: 3,
        username: 'bob',
        nickname: 'Bob',
        signature: '',
      );
      final first = controller.sendRequest(user, 'hello');
      await Future<void>.delayed(Duration.zero);
      expect(await controller.sendRequest(user, 'duplicate'), isFalse);
      expect(api.sent, 1);
      api.sendGate!.complete();
      expect(await first, isTrue);
      expect(controller.error, isNull);
    },
  );

  test(
    'relationship removal fails queued wire state and blocks retry',
    () async {
      final api = RelationshipApi();
      final wire = RelationshipConnection(api);
      final chat = ChatController(connectionFactory: (_) => wire)..api = api;
      addTearDown(chat.dispose);
      await chat.reconnect();
      await chat.select(RelationshipApi.room);
      expect(chat.send('queued before deletion'), isTrue);
      expect(chat.messages.single.delivery, Delivery.sending);
      api.related = false;
      await chat.relationshipsChanged();
      expect(chat.messages.single.delivery, Delivery.failed);
      expect(chat.send('', retry: chat.messages.single), isFalse);
      expect(chat.error, contains('不在联系人'));
      expect(wire.frames, hasLength(1));
    },
  );

  test('initial relationship snapshot blocks stale private sending', () async {
    final api = RelationshipApi()..related = false;
    final wire = RelationshipConnection(api);
    final chat = ChatController(connectionFactory: (_) => wire)..api = api;
    addTearDown(chat.dispose);
    await chat.reconnect();
    await chat.select(RelationshipApi.room);
    expect(chat.send('must not leave the old outbox'), isFalse);
    expect(chat.error, contains('不在联系人'));
    expect(wire.frames, isEmpty);
  });
}
