import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/core/models.dart';
import 'package:meshx_flutter_probe/data/attachments_models.dart';
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
  String event, {
  String? clientMsgId,
}) => connection.events.stream
    .firstWhere(
      (value) =>
          value['event'] == event &&
          (clientMsgId == null || value['clientMsgId'] == clientMsgId),
    )
    .timeout(const Duration(seconds: 10));

Future<FileUploadResult> upload(
  MeshXApi api,
  String conversationId,
  String name,
  String mime,
  List<int> bytes,
) => api.uploadAttachmentStream(
  conversationId: conversationId,
  name: name,
  mime: mime,
  size: bytes.length,
  readChunk: (offset, length) async =>
      bytes.sublist(offset, (offset + length).clamp(offset, bytes.length)),
);

void main() {
  test(
    'real Spring node relay file/image send, ACK and authorized download',
    () async {
      if (originValue.isEmpty) {
        markTestSkipped('MESHX_NODE is required for the isolated live fixture');
        return;
      }
      final origin = Uri.parse(originValue);
      final suffix = DateTime.now().microsecondsSinceEpoch.toRadixString(36);
      final secretTail = suffix.length > 8
          ? suffix.substring(suffix.length - 8)
          : suffix;
      final ownerName = 'attach_${suffix}o';
      final memberName = 'attach_${suffix}m';
      final password = 'F7${secretTail}x';
      await register(origin, ownerName, password, '附件验收发送方');
      await register(origin, memberName, password, '附件验收接收方');

      final owner = MeshXApi(origin), member = MeshXApi(origin);
      addTearDown(owner.close);
      addTearDown(member.close);
      await owner.handshake();
      await member.handshake();
      final ownerSession = await owner.login(ownerName, password);
      final memberSession = await member.login(memberName, password);
      await owner.sendFriendRequest(memberSession.userId, '附件闭环好友关系');
      final friendRequest = (await member.friendRequests()).single;
      await member.handleFriendRequest(friendRequest.id, true);
      final group = await owner.createGroup('附件闭环群', [memberSession.userId]);
      final conversationId = 'group:${group.id}';

      final ownerRealtime = RealtimeConnection(owner);
      final memberRealtime = RealtimeConnection(member);
      addTearDown(ownerRealtime.close);
      addTearDown(memberRealtime.close);
      await ownerRealtime.connect();
      await memberRealtime.connect();

      final textBytes = utf8.encode('MeshX Flutter node relay attachment');
      final uploadedText = await upload(
        owner,
        conversationId,
        'meshx-live.txt',
        'text/plain',
        textBytes,
      );
      final fileId = requestId();
      final fileAck = nextEvent(ownerRealtime, 'CHAT_ACK', clientMsgId: fileId);
      final fileDeliver = nextEvent(memberRealtime, 'CHAT_DELIVER');
      ownerRealtime.send(
        'CHAT_SEND',
        {
          'groupId': group.id,
          'contentType': 'file',
          'content': uploadedText.attachment.encode(),
          'isBurn': false,
        },
        clientMsgId: fileId,
        conversationId: conversationId,
      );
      expect(((await fileAck)['payload'] as Json)['duplicated'], isFalse);
      final deliveredFile = AttachmentData.decode(
        ((await fileDeliver)['payload'] as Json)['content'] as String,
      );
      expect((await member.downloadAttachment(deliveredFile)).bytes, textBytes);

      final retryAck = nextEvent(
        ownerRealtime,
        'CHAT_ACK',
        clientMsgId: fileId,
      );
      ownerRealtime.send(
        'CHAT_SEND',
        {
          'groupId': group.id,
          'contentType': 'file',
          'content': uploadedText.attachment.encode(),
          'isBurn': false,
        },
        clientMsgId: fileId,
        conversationId: conversationId,
      );
      expect(((await retryAck)['payload'] as Json)['duplicated'], isTrue);

      final pngBytes = base64Decode(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
      );
      final uploadedImage = await upload(
        owner,
        conversationId,
        'meshx-live.png',
        'image/png',
        pngBytes,
      );
      expect(uploadedImage.attachment.image, isTrue);
      final imageId = requestId();
      final imageAck = nextEvent(
        ownerRealtime,
        'CHAT_ACK',
        clientMsgId: imageId,
      );
      final imageDeliver = nextEvent(memberRealtime, 'CHAT_DELIVER');
      ownerRealtime.send(
        'CHAT_SEND',
        {
          'groupId': group.id,
          'contentType': 'image',
          'content': uploadedImage.attachment.encode(),
          'isBurn': false,
        },
        clientMsgId: imageId,
        conversationId: conversationId,
      );
      await imageAck;
      final deliveredImage = AttachmentData.decode(
        ((await imageDeliver)['payload'] as Json)['content'] as String,
      );
      expect((await member.downloadAttachment(deliveredImage)).bytes, pngBytes);

      await member.leaveGroup(group.id);
      await expectLater(
        member.downloadAttachment(deliveredFile),
        throwsA(isA<ApiException>()),
      );
      expect(ownerSession.userId, isNot(memberSession.userId));
    },
  );
}
