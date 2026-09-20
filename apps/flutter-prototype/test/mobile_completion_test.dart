import 'dart:async';
import 'package:flutter_test/flutter_test.dart' hide group;
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/application/groups_controller.dart';
import 'package:meshx_flutter_probe/application/platform_coordinator.dart';
import 'package:meshx_flutter_probe/core/models.dart';
import 'package:meshx_flutter_probe/data/groups_models.dart';
import 'package:meshx_flutter_probe/data/temporary_rooms.dart';
import 'platform_fakes.dart';
import 'recovery_test.dart'
    show ControlledApi, CapturingConnection, room, message, eventually;
import 'groups_feature_test.dart' show FakeGroupsApi, group;

class CompletionApi extends ControlledApi {
  Completer<List<ChatMessage>>? historyGate;
  bool removed = false;
  @override
  Future<List<Conversation>> conversations() async => removed ? [] : [room];
  TemporaryRoom temporary = TemporaryRoom(
    id: 7,
    name: '现场协作',
    code: '123456',
    status: 'ACTIVE',
    ownerId: 1,
    expiresAt: DateTime.now().add(const Duration(hours: 1)),
  );
  @override
  Future<List<ChatMessage>> history(
    String id, {
    int? before,
    int limit = 50,
  }) async => historyGate == null ? [] : historyGate!.future;
  @override
  Future<TemporaryRoom> temporaryRoom(int id) async => temporary;
}

class ManagementApi extends FakeGroupsApi {
  final calls = <String>[];
  int role = 2;
  @override
  Future<List<GroupMemberInfo>> groupMembers(int id) async => [
    GroupMemberInfo(
      userId: 1,
      nickname: 'Me',
      avatar: '',
      role: role,
      online: true,
    ),
    const GroupMemberInfo(
      userId: 2,
      nickname: 'Peer',
      avatar: 'letter:P:#123456',
      role: 0,
      online: true,
    ),
  ];
  @override
  Future<void> setGroupAdmin(int id, int member, bool admin) async {
    calls.add('$id:$member:$admin');
  }

  @override
  Future<List<Conversation>> conversations() async => [];
}

void main() {
  test(
    'member removal fences a late history response and prevents a new send',
    () async {
      final api = CompletionApi(), wire = CapturingConnection(CompletionApi());
      final c = ChatController(connectionFactory: (_) => wire)..api = api;
      addTearDown(c.dispose);
      await c.reconnect();
      api.historyGate = Completer<List<ChatMessage>>();
      api.removed = true;
      final select = c.select(room);
      wire.events.add({
        'event': 'CONVERSATION_REMOVED',
        'conversationId': room.id,
        'payload': {'active': false},
      });
      await eventually(() => c.active == null);
      api.historyGate!.complete([message(9)]);
      await select;
      expect(c.messages, isEmpty);
      expect(c.conversations, isEmpty);
      c.active = room;
      expect(c.send('must not send'), false);
    },
  );
  test(
    'a fresh membership snapshot restores access after an offline rejoin',
    () async {
      final api = CompletionApi(), wire = CapturingConnection(CompletionApi());
      final c = ChatController(connectionFactory: (_) => wire)..api = api;
      addTearDown(c.dispose);
      await c.reconnect();
      api.removed = true;
      wire.events.add({
        'event': 'CONVERSATION_REMOVED',
        'conversationId': room.id,
        'payload': {'active': false},
      });
      await eventually(() => !c.conversationAccessible(room.id));
      await c.refreshConversations();
      expect(c.conversations, isEmpty);
      api.removed = false;
      await c.reconnect();
      expect(c.conversationAccessible(room.id), true);
      expect(c.conversations.map((c) => c.id), contains(room.id));
    },
  );
  test(
    'own read receipt cancels an existing ordinary OS notification',
    () async {
      final api = CompletionApi(), wire = CapturingConnection(CompletionApi());
      final c = ChatController(connectionFactory: (_) => wire)..api = api;
      final system = FakeSystem();
      final platform = PlatformCoordinator(
        chat: c,
        lifecycle: FakeLifecycle(),
        notifications: system,
        files: system,
        sharePort: system,
        network: system,
        settings: system,
      )..start();
      addTearDown(() async {
        platform.dispose();
        c.dispose();
        await system.dispose();
      });
      await c.reconnect();
      wire.events.add({
        'event': 'CHAT_DELIVER',
        'payload': message(3).toJson(),
      });
      await eventually(() => system.visible.contains('3'));
      wire.events.add({
        'event': 'CHAT_READ',
        'conversationId': room.id,
        'payload': {
          'userId': 1,
          'lastSequence': 3,
          'lastReadSequence': 3,
          'unreadCount': 0,
        },
      });
      await eventually(() => !system.visible.contains('3'));
      await platform.drain();
      expect(system.visible, isEmpty);
    },
  );
  test(
    'owner can manage members but a refreshed ordinary role cannot',
    () async {
      final api = ManagementApi();
      final c = ChatController()..api = api;
      final groups = GroupsController(chat: c);
      addTearDown(() {
        groups.dispose();
        c.dispose();
      });
      await groups.start();
      await groups.loadDetails(group);
      expect(await groups.manage('admin', member: groups.members.last), true);
      expect(api.calls, ['7:2:true']);
      api.role = 0;
      await groups.loadDetails(group);
      expect(await groups.manage('admin', member: groups.members.last), false);
      expect(api.calls.length, 1);
    },
  );
  test(
    'temporary text uses conversation envelope and expires closed',
    () async {
      final api = CompletionApi(), wire = CapturingConnection(CompletionApi());
      final c = ChatController(connectionFactory: (_) => wire)..api = api;
      addTearDown(c.dispose);
      await c.reconnect();
      await c.openTemporaryRoom(api.temporary);
      expect(c.send('现场消息'), true);
      expect(wire.frames.last['conversationId'], 'temporary:7');
      expect(
        (wire.frames.last['payload'] as Map).containsKey('groupId'),
        false,
      );
      api.temporary = TemporaryRoom(
        id: 7,
        name: '现场协作',
        code: '',
        status: 'FROZEN',
        ownerId: 1,
        expiresAt: DateTime.now().subtract(const Duration(hours: 1)),
      );
      await c.openTemporaryRoom(api.temporary);
      expect(c.send('不能发送'), false);
      expect(wire.frames.length, 1);
    },
  );
  test(
    'reply and mention fields survive retry with the original message identity',
    () async {
      final api = CompletionApi(), wire = CapturingConnection(CompletionApi());
      final c = ChatController(connectionFactory: (_) => wire)..api = api;
      addTearDown(c.dispose);
      await c.reconnect();
      c.active = const Conversation(
        id: 'group:7',
        targetId: 7,
        kind: 'group',
        title: '讨论',
      );
      expect(c.send('回复', replyToId: 'message-123456', mentions: {2, 3}), true);
      final first = wire.frames.last;
      expect(first['payload']['replyToId'], 'message-123456');
      expect(first['payload']['mentionUserIds'], '2,3');
      expect(c.retryMessage(c.messages.last), true);
      expect(wire.frames.last['clientMsgId'], first['clientMsgId']);
      expect(wire.frames.last['payload'], first['payload']);
    },
  );
}
