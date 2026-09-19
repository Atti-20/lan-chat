import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/core/models.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';

const originValue = String.fromEnvironment('MESHX_NODE');

Future<void> register(
  Uri origin,
  String username,
  String password,
  String nickname,
) async {
  final client = HttpClient();
  try {
    final request = await client.postUrl(
      origin.replace(path: '/api/v1/auth/register'),
    );
    request.headers.contentType = ContentType.json;
    request.write(
      jsonEncode({
        'username': username,
        'password': password,
        'nickname': nickname,
      }),
    );
    final response = await request.close();
    final value =
        jsonDecode(await response.transform(utf8.decoder).join()) as Json;
    if (response.statusCode != 200 || value['code'] != 200) {
      throw StateError(
        'fixture registration failed with code ${value['code']}',
      );
    }
  } finally {
    client.close(force: true);
  }
}

Future<Json> nextEvent(
  RealtimeConnection connection,
  String event,
  String clientMsgId,
) => connection.events.stream
    .firstWhere(
      (value) => value['event'] == event && value['clientMsgId'] == clientMsgId,
    )
    .timeout(const Duration(seconds: 10));

void main() {
  test('real Spring group create, chat, roles and leave lifecycle', () async {
    if (originValue.isEmpty) {
      markTestSkipped('MESHX_NODE is required for the isolated live fixture');
      return;
    }
    final origin = Uri.parse(originValue);
    final suffix = DateTime.now().microsecondsSinceEpoch.toRadixString(36);
    final secretTail = suffix.length > 8
        ? suffix.substring(suffix.length - 8)
        : suffix;
    final ownerName = 'group_${suffix}o';
    final memberName = 'group_${suffix}m';
    final password = 'G7${secretTail}x';
    await register(origin, ownerName, password, '群组验收群主');
    await register(origin, memberName, password, '群组验收成员');

    final owner = MeshXApi(origin), member = MeshXApi(origin);
    addTearDown(owner.close);
    addTearDown(member.close);
    await owner.handshake();
    await member.handshake();
    final ownerSession = await owner.login(ownerName, password);
    final memberSession = await member.login(memberName, password);

    await owner.sendFriendRequest(memberSession.userId, '群组闭环好友关系');
    final request = (await member.friendRequests()).single;
    await member.handleFriendRequest(request.id, true);

    final created = await owner.createGroup('真实群聊闭环', [
      memberSession.userId,
      memberSession.userId,
    ]);
    expect(created.ownerId, ownerSession.userId);
    expect((await owner.groups()).map((item) => item.id), contains(created.id));
    expect(
      (await member.groups()).map((item) => item.id),
      contains(created.id),
    );
    expect((await member.groupInfo(created.id)).name, '真实群聊闭环');
    final members = await owner.groupMembers(created.id);
    expect(members.map((item) => item.userId).toSet(), {
      ownerSession.userId,
      memberSession.userId,
    });
    expect(
      members.singleWhere((item) => item.userId == ownerSession.userId).role,
      2,
    );
    expect(
      members.singleWhere((item) => item.userId == memberSession.userId).role,
      0,
    );

    final ownerRealtime = RealtimeConnection(owner);
    final memberRealtime = RealtimeConnection(member);
    addTearDown(ownerRealtime.close);
    addTearDown(memberRealtime.close);
    await ownerRealtime.connect();
    await memberRealtime.connect();
    final conversationId = 'group:${created.id}';
    final acceptedId = requestId();
    final accepted = nextEvent(ownerRealtime, 'CHAT_ACK', acceptedId);
    ownerRealtime.send(
      'CHAT_SEND',
      {
        'groupId': created.id,
        'contentType': 'text',
        'content': '真实群聊消息',
        'isBurn': false,
      },
      clientMsgId: acceptedId,
      conversationId: conversationId,
    );
    expect((await accepted)['event'], 'CHAT_ACK');

    await expectLater(
      owner.leaveGroup(created.id),
      throwsA(
        isA<ApiException>().having(
          (failure) => failure.message,
          'message',
          contains('群主'),
        ),
      ),
    );
    await member.leaveGroup(created.id);
    expect(
      (await member.groups()).where((item) => item.id == created.id),
      isEmpty,
    );
    expect((await owner.groupMembers(created.id)).map((item) => item.userId), [
      ownerSession.userId,
    ]);
    await expectLater(
      member.groupInfo(created.id),
      throwsA(isA<ApiException>()),
    );

    final deniedId = requestId();
    final denied = nextEvent(memberRealtime, 'ERROR', deniedId);
    memberRealtime.send(
      'CHAT_SEND',
      {
        'groupId': created.id,
        'contentType': 'text',
        'content': '退群后不得发送',
        'isBurn': false,
      },
      clientMsgId: deniedId,
      conversationId: conversationId,
    );
    expect(((await denied)['payload'] as Json)['message'], isNotEmpty);

    final ownerOnly = await owner.createGroup('空群导航验证', const []);
    expect(
      (await owner.groupMembers(ownerOnly.id)).single.userId,
      ownerSession.userId,
    );
  });
}
