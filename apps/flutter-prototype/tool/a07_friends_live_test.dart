import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/core/models.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';

const originValue = String.fromEnvironment('MESHX_NODE');

Future<void> register(Uri origin, String username, String password) async {
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
        'nickname': username.endsWith('a') ? '好友测试甲' : '好友测试乙',
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
  test('real Spring friend lifecycle and post-delete send denial', () async {
    if (originValue.isEmpty) {
      markTestSkipped('MESHX_NODE is required for the isolated live fixture');
      return;
    }
    final origin = Uri.parse(originValue);
    final suffix = DateTime.now().microsecondsSinceEpoch.toRadixString(36);
    final secretTail = suffix.length > 8
        ? suffix.substring(suffix.length - 8)
        : suffix;
    final usernameA = 'friend_${suffix}a';
    final usernameB = 'friend_${suffix}b';
    final password = 'F9${secretTail}x';
    await register(origin, usernameA, password);
    await register(origin, usernameB, password);

    final first = MeshXApi(origin), second = MeshXApi(origin);
    addTearDown(first.close);
    addTearDown(second.close);
    await first.handshake();
    await second.handshake();
    final sessionA = await first.login(usernameA, password);
    final sessionB = await second.login(usernameB, password);

    final result = await first.searchUsers(usernameB);
    expect(result.single.userId, sessionB.userId);
    await first.sendFriendRequest(sessionB.userId, '真实好友闭环');
    await expectLater(
      first.sendFriendRequest(sessionB.userId, '重复申请'),
      throwsA(isA<ApiException>()),
    );

    final pending = await second.friendRequests();
    expect(pending.single.fromUserId, sessionA.userId);
    await second.handleFriendRequest(pending.single.id, false);
    expect(await second.friendRequests(), isEmpty);

    await first.sendFriendRequest(sessionB.userId, '再次申请');
    final accepted = (await second.friendRequests()).single;
    await second.handleFriendRequest(accepted.id, true);
    expect((await first.friends()).single.userId, sessionB.userId);
    expect((await second.friends()).single.userId, sessionA.userId);

    await first.setFriendRemark(sessionB.userId, '验收联系人');
    expect((await first.friends()).single.remark, '验收联系人');

    final realtime = RealtimeConnection(first);
    addTearDown(realtime.close);
    await realtime.connect();
    final conversationId = privateConversationId(
      sessionA.userId,
      sessionB.userId,
    );
    final acceptedId = requestId();
    final acceptedEvent = nextEvent(realtime, 'CHAT_ACK', acceptedId);
    realtime.send(
      'CHAT_SEND',
      {
        'toUserId': sessionB.userId,
        'contentType': 'text',
        'content': '删除关系前允许发送',
        'isBurn': false,
      },
      clientMsgId: acceptedId,
      conversationId: conversationId,
    );
    expect((await acceptedEvent)['event'], 'CHAT_ACK');

    await first.deleteFriend(sessionB.userId);
    expect(await first.friends(), isEmpty);
    expect(await second.friends(), isEmpty);
    final deniedId = requestId();
    final deniedEvent = nextEvent(realtime, 'ERROR', deniedId);
    realtime.send(
      'CHAT_SEND',
      {
        'toUserId': sessionB.userId,
        'contentType': 'text',
        'content': '删除关系后必须拒绝',
        'isBurn': false,
      },
      clientMsgId: deniedId,
      conversationId: conversationId,
    );
    final failure = await deniedEvent;
    expect((failure['payload'] as Json)['message'], isNotEmpty);
  });
}
