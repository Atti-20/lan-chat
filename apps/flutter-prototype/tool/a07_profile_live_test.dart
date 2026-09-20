import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
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
        'nickname': '资料验收账号',
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

void main() {
  test('real Spring profile, avatar and password revocation lifecycle', () async {
    if (originValue.isEmpty) {
      markTestSkipped('MESHX_NODE is required for the isolated live fixture');
      return;
    }
    final origin = Uri.parse(originValue);
    final suffix = DateTime.now().microsecondsSinceEpoch.toRadixString(36);
    final secretTail = suffix.length > 8
        ? suffix.substring(suffix.length - 8)
        : suffix;
    final username = 'profile_$suffix';
    final oldPassword = 'O9${secretTail}x';
    final newPassword = 'N8${secretTail}y';
    await register(origin, username, oldPassword);

    final api = MeshXApi(origin);
    addTearDown(api.close);
    await api.handshake();
    final session = await api.login(username, oldPassword);
    final initial = await api.currentUser();
    expect(initial.userId, session.userId);
    expect(initial.username, username);

    final textProfile = await api.updateProfile(
      nickname: '资料验收昵称',
      avatar: 'letter:资:#007AFF',
    );
    expect(textProfile.nickname, '资料验收昵称');
    expect(textProfile.avatar, 'letter:资:#007AFF');

    final png = Uint8List.fromList(
      base64Decode(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
      ),
    );
    final uploaded = await api.uploadAvatar(
      name: 'avatar.png',
      mime: 'image/png',
      bytes: png,
    );
    final imageProfile = await api.updateProfile(
      nickname: textProfile.nickname,
      avatar: uploaded.profileValue,
    );
    expect(imageProfile.avatar, uploaded.profileValue);
    expect(await api.avatarBytes(imageProfile.avatar), isNotEmpty);

    await api.changePassword(oldPassword, newPassword);
    await expectLater(
      api.currentUser(),
      throwsA(isA<ApiException>()),
      reason: 'password change must revoke the existing access/refresh session',
    );

    final oldLogin = MeshXApi(origin), newLogin = MeshXApi(origin);
    addTearDown(oldLogin.close);
    addTearDown(newLogin.close);
    await oldLogin.handshake();
    await newLogin.handshake();
    await expectLater(
      oldLogin.login(username, oldPassword),
      throwsA(isA<ApiException>()),
    );
    final restored = await newLogin.login(username, newPassword);
    expect(restored.userId, session.userId);
    expect((await newLogin.currentUser()).avatar, uploaded.profileValue);
  });
}
