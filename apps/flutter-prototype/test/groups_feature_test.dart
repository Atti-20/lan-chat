import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/application/groups_controller.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/core/models.dart';
import 'package:meshx_flutter_probe/data/friends_models.dart';
import 'package:meshx_flutter_probe/data/groups_models.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';

const group = MeshXGroup(
  id: 7,
  name: '产品讨论组',
  ownerId: 1,
  avatar: '',
  announcement: 'LAN first',
  maxMembers: 200,
  joinMode: 0,
);

const memberGroup = MeshXGroup(
  id: 8,
  name: '成员测试群',
  ownerId: 2,
  avatar: '',
  announcement: '',
  maxMembers: 200,
  joinMode: 0,
);

const groupFriend = FriendContact(
  userId: 2,
  username: 'alice',
  nickname: 'Alice',
  remark: '',
  signature: '',
  online: true,
);

const fixtureGroupMembers = [
  GroupMemberInfo(
    userId: 2,
    nickname: 'Alice',
    avatar: '',
    role: 2,
    online: true,
  ),
  GroupMemberInfo(userId: 1, nickname: 'Me', avatar: '', role: 0, online: true),
];

class FakeGroupsApi extends MeshXApi {
  FakeGroupsApi() : super(Uri.parse('https://groups.invalid')) {
    node = NodeInfo(origin, '群组测试节点', '', '/api/v1', '/ws/chat');
    session = const Session(1, 'Me', 'synthetic-token');
  }

  List<MeshXGroup> groupList = [group];
  List<FriendContact> contacts = [groupFriend];
  Completer<MeshXGroup>? createGate;
  Object? leaveFailure;
  int creates = 0, leaves = 0;
  List<int> createdMembers = const [];

  @override
  Future<List<MeshXGroup>> groups() async => groupList;
  @override
  Future<List<FriendContact>> friends() async => contacts;
  @override
  Future<MeshXGroup> groupInfo(int groupId) async =>
      groupList.firstWhere((item) => item.id == groupId);
  @override
  Future<List<GroupMemberInfo>> groupMembers(int groupId) async =>
      fixtureGroupMembers;
  @override
  Future<List<ChatMessage>> history(
    String conversationId, {
    int? before,
    int limit = 50,
  }) async => const [];
  @override
  Future<MeshXGroup> createGroup(String name, Iterable<int> memberIds) async {
    creates++;
    createdMembers = memberIds.toList();
    final pending = createGate;
    if (pending != null) return pending.future;
    return group;
  }

  @override
  Future<void> leaveGroup(int groupId) async {
    leaves++;
    if (leaveFailure case final Object failure) throw failure;
    groupList = groupList.where((item) => item.id != groupId).toList();
  }
}

Json groupJson({int id = 7, int ownerId = 1}) => {
  'id': id,
  'groupName': '产品讨论组',
  'avatar': '',
  'announcement': 'LAN first',
  'ownerId': ownerId,
  'maxMembers': 200,
  'joinMode': 0,
  'createTime': '2026-09-13T10:00:00',
  'updateTime': '2026-09-13T10:00:00',
};

void main() {
  test('group DTOs reject invalid identities, roles and duplicate members', () {
    expect(
      () => MeshXGroup.fromJson({...groupJson(), 'id': 0}),
      throwsFormatException,
    );
    expect(
      () => GroupMemberInfo.fromJson({
        'userId': 2,
        'nickname': 'Alice',
        'role': 3,
      }),
      throwsFormatException,
    );
    expect(
      GroupMemberInfo.fromJson({'userId': 2, 'role': 0}).displayName,
      '成员 2',
    );
  });

  test(
    'group APIs preserve v1 paths, body, auth and strict response ids',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final seen = <Json>[];
      var memberReads = 0;
      server.listen((request) async {
        final body = await utf8.decoder.bind(request).join();
        seen.add({
          'method': request.method,
          'path': request.uri.path,
          'authorization': request.headers.value(
            HttpHeaders.authorizationHeader,
          ),
          'body': body.isEmpty ? null : jsonDecode(body),
        });
        Object? data;
        if (request.uri.path.endsWith('/group/my')) {
          data = [groupJson()];
        } else if (request.uri.path.endsWith('/group/7/members')) {
          memberReads++;
          data = memberReads == 1
              ? [
                  {'userId': 1, 'nickname': 'Me', 'role': 2, 'online': 1},
                  {'userId': 2, 'nickname': 'Alice', 'role': 0, 'online': 0},
                ]
              : [
                  {'userId': 1, 'nickname': 'Me', 'role': 2, 'online': 1},
                  {'userId': 1, 'nickname': 'Me', 'role': 2, 'online': 1},
                ];
        } else if (request.method == 'POST' &&
            request.uri.path.endsWith('/group')) {
          data = groupJson();
        } else if (request.uri.path.endsWith('/group/7')) {
          data = groupJson();
        }
        request.response.write(jsonEncode({'code': 200, 'data': data}));
        await request.response.close();
      });
      final api = MeshXApi(Uri.parse('http://127.0.0.1:${server.port}'));
      api.node = NodeInfo(api.origin, 'test', '', '/api/v1', '/ws/chat');
      api.session = const Session(1, 'Me', 'synthetic-token');
      try {
        expect((await api.groups()).single.id, 7);
        expect((await api.groupInfo(7)).name, '产品讨论组');
        expect(await api.groupMembers(7), hasLength(2));
        expect((await api.createGroup(' 产品讨论组 ', [2, 2])).id, 7);
        await api.leaveGroup(7);
        await expectLater(api.groupMembers(7), throwsFormatException);
        expect(seen.map((item) => '${item['method']} ${item['path']}'), [
          'GET /api/v1/group/my',
          'GET /api/v1/group/7',
          'GET /api/v1/group/7/members',
          'POST /api/v1/group',
          'POST /api/v1/group/7/leave',
          'GET /api/v1/group/7/members',
        ]);
        expect(seen[3]['body'], {
          'groupName': '产品讨论组',
          'memberIds': [2],
        });
        expect(
          seen.every(
            (item) => item['authorization'] == 'Bearer synthetic-token',
          ),
          isTrue,
        );
      } finally {
        api.close();
        await server.close(force: true);
      }
    },
  );

  test(
    'group creation admits only friends and rejects duplicate submission',
    () async {
      final api = FakeGroupsApi(), chat = ChatController()..api = api;
      final controller = GroupsController(chat: chat);
      addTearDown(() {
        controller.dispose();
        chat.dispose();
      });
      await controller.start();
      expect(await controller.create('测试群聊', [99]), isFalse);
      expect(api.creates, 0);
      final gate = Completer<MeshXGroup>();
      api.createGate = gate;
      final first = controller.create('产品讨论组', [2]);
      await Future<void>.delayed(Duration.zero);
      expect(await controller.create('重复提交群', [2]), isFalse);
      expect(api.creates, 1);
      gate.complete(group);
      expect(await first, isTrue);
      expect(chat.active?.id, 'group:7');
      expect(api.createdMembers, [2]);
    },
  );

  test(
    'details load roles; confirmed own leave clears the local conversation',
    () async {
      final api = FakeGroupsApi()..groupList = [memberGroup];
      final chat = ChatController()..api = api;
      final controller = GroupsController(chat: chat);
      addTearDown(() {
        controller.dispose();
        chat.dispose();
      });
      await controller.start();
      await controller.loadDetails(memberGroup);
      expect(controller.members.map((item) => item.roleLabel), ['群主', '成员']);
      await controller.open(memberGroup);
      expect(chat.active?.id, 'group:8');
      expect(await controller.leave(memberGroup), isTrue);
      expect(api.leaves, 1);
      expect(chat.active, isNull);
      expect(chat.conversations.where((item) => item.id == 'group:8'), isEmpty);
    },
  );

  test(
    'server owner-leave rejection remains visible and preserves the group',
    () async {
      final api = FakeGroupsApi()
        ..leaveFailure = const ApiException('群主请先转让群主身份后再退群');
      final chat = ChatController()..api = api;
      final controller = GroupsController(chat: chat);
      addTearDown(() {
        controller.dispose();
        chat.dispose();
      });
      await controller.start();
      expect(await controller.leave(group), isFalse);
      expect(controller.error, '群主请先转让群主身份后再退群');
      expect(controller.groups.single.id, 7);
    },
  );
}
