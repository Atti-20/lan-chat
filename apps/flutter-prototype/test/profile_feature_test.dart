import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/application/platform_coordinator.dart';
import 'package:meshx_flutter_probe/application/profile_controller.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/core/models.dart';
import 'package:meshx_flutter_probe/core/store.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';
import 'package:meshx_flutter_probe/data/profile_models.dart';
import 'package:meshx_flutter_probe/platform/storage.dart';
import 'platform_fakes.dart';

const initialProfile = UserProfile(
  userId: 1,
  username: 'alice',
  nickname: 'Alice',
  avatar: 'letter:A:#5856D6',
  signature: 'LAN first',
);

class MemoryCredentials implements CredentialStore {
  Json? value;
  bool failWrite = false;
  @override
  Future<void> clear() async => value = null;
  @override
  Future<Json?> read() async => value;
  @override
  Future<void> write(Json credentials) async {
    if (failWrite) throw const FileSystemException('synthetic write failure');
    value = Map<String, dynamic>.from(credentials);
  }
}

class ProfileApi extends MeshXApi {
  ProfileApi() : super(Uri.parse('https://profile.invalid')) {
    node = NodeInfo(origin, '资料测试节点', '', '/api/v1', '/ws/chat');
    session = const Session(1, 'Before', 'synthetic-token', username: 'alice');
  }
  UserProfile current = initialProfile;
  bool failUpdate = false, failPassword = false;
  int uploads = 0, passwordChanges = 0, updates = 0;
  Completer<UserProfile>? updateGate;
  List<int>? uploadedBytes;

  @override
  Future<UserProfile> currentUser() async => current;

  @override
  Future<UserProfile> updateProfile({
    required String nickname,
    required String avatar,
  }) async {
    updates++;
    if (failUpdate) throw const ApiException('synthetic profile failure');
    final gate = updateGate;
    if (gate != null) return gate.future;
    return current = UserProfile(
      userId: 1,
      username: 'alice',
      nickname: nickname,
      avatar: avatar,
      signature: current.signature,
    );
  }

  @override
  Future<void> changePassword(String oldPassword, String newPassword) async {
    passwordChanges++;
    if (failPassword) throw const ApiException('synthetic password failure');
  }

  @override
  Future<AvatarUpload> uploadAvatar({
    required String name,
    required String mime,
    required List<int> bytes,
  }) async {
    uploads++;
    uploadedBytes = bytes;
    return const AvatarUpload(
      url: '/api/v1/file/content/avatar.png',
      thumbnailUrl: '/api/v1/file/content/thumb_avatar.png',
    );
  }
}

PlatformCoordinator coordinator(ChatController chat, FakeSystem system) =>
    PlatformCoordinator(
      chat: chat,
      lifecycle: FakeLifecycle(),
      notifications: system,
      files: system,
      sharePort: system,
      network: system,
      settings: system,
    )..start();

void main() {
  test(
    'profile DTO and text avatar encoding fail closed and match Web format',
    () {
      expect(
        UserProfile.fromJson({
          'id': 1,
          'username': ' alice ',
          'nickname': ' Alice ',
          'avatar': 'text',
          'signature': null,
        }).username,
        'alice',
      );
      expect(
        () => UserProfile.fromJson({
          'id': 0,
          'username': 'alice',
          'nickname': 'Alice',
        }),
        throwsFormatException,
      );
      expect(
        () => AvatarUpload.fromJson({'url': '', 'thumbnailUrl': ''}),
        throwsFormatException,
      );
      expect(textAvatar(' 许澄 ', '#007AFF'), 'letter:许:#007AFF');
      expect(textAvatar('Alice', '#not-valid'), 'letter:A:#5856D6');
    },
  );

  test(
    'profile success updates durable credentials; failure does not fake success',
    () async {
      final credentials = MemoryCredentials(), api = ProfileApi();
      final chat = ChatController(
        discovery: FakeDiscovery(),
        credentials: credentials,
      )..api = api;
      final controller = ProfileController(chat: chat);
      addTearDown(() {
        controller.dispose();
        chat.dispose();
      });
      await controller.load();
      expect(chat.session!.nickname, 'Alice');
      expect(credentials.value!['nickname'], 'Alice');
      controller.chooseTextAvatar('New Name', '#007AFF');
      expect(await controller.save('New Name'), isTrue);
      expect(chat.session!.nickname, 'New Name');
      expect(credentials.value!['avatar'], 'letter:N:#007AFF');
      api.failUpdate = true;
      expect(await controller.save('Should Not Apply'), isFalse);
      expect(chat.session!.nickname, 'New Name');
      expect(controller.error, 'synthetic profile failure');
    },
  );

  test('credential write failure rolls session snapshot back', () async {
    final credentials = MemoryCredentials(), api = ProfileApi();
    final chat = ChatController(
      discovery: FakeDiscovery(),
      credentials: credentials,
    )..api = api;
    final controller = ProfileController(chat: chat);
    addTearDown(() {
      controller.dispose();
      chat.dispose();
    });
    await controller.load();
    credentials.failWrite = true;
    expect(await controller.save('Server Changed'), isFalse);
    expect(chat.session!.nickname, 'Alice');
    expect(controller.notice, isNull);
  });

  test(
    'profile mutations remain mutually exclusive while a save is pending',
    () async {
      final api = ProfileApi(),
          chat = ChatController(discovery: FakeDiscovery())..api = api;
      final controller = ProfileController(chat: chat);
      addTearDown(() {
        controller.dispose();
        chat.dispose();
      });
      await controller.load();
      controller.chooseTextAvatar('First', '#007AFF');
      final avatar = controller.draftAvatar;
      final gate = Completer<UserProfile>();
      api.updateGate = gate;

      final firstSave = controller.save('First');
      await Future<void>.delayed(Duration.zero);
      expect(controller.busy, isTrue);
      controller.chooseTextAvatar('Second', '#FF3B30');
      expect(controller.draftAvatar, avatar);
      expect(await controller.save('Second'), isFalse);
      expect(
        await controller.changePassword(
          oldPassword: 'old-password1',
          newPassword: 'new-password2',
          confirmation: 'new-password2',
        ),
        isFalse,
      );
      expect(api.updates, 1);
      expect(api.passwordChanges, 0);

      gate.complete(
        UserProfile(
          userId: 1,
          username: 'alice',
          nickname: 'First',
          avatar: avatar,
          signature: initialProfile.signature,
        ),
      );
      expect(await firstSave, isTrue);
      expect(controller.busy, isFalse);
      expect(controller.profile!.nickname, 'First');
    },
  );

  test(
    'avatar picker reads only selected image bytes and releases the copy',
    () async {
      final api = ProfileApi(),
          chat = ChatController(discovery: FakeDiscovery())..api = api;
      final system = FakeSystem()..selectedBytes = [1, 2, 3];
      system.pickGate = Completer<CapabilityResult<SelectedFile>>()
        ..complete(
          const CapabilityResult(
            CapabilityStatus.success,
            value: SelectedFile(
              handle: '00000000-0000-0000-0000-000000000001',
              name: 'avatar.png',
              mime: 'image/png',
              size: 3,
            ),
          ),
        );
      final platform = coordinator(chat, system);
      final controller = ProfileController(chat: chat, platform: platform);
      addTearDown(() async {
        controller.dispose();
        platform.dispose();
        chat.dispose();
        await system.dispose();
      });
      await controller.load();
      final releases = system.releaseCount;
      await controller.chooseImageAvatar();
      expect(api.uploads, 1);
      expect(api.uploadedBytes, [1, 2, 3]);
      expect(controller.draftAvatar, contains('thumb_avatar.png'));
      expect(system.releaseCount, greaterThan(releases));
    },
  );

  test(
    'password validation is local and success clears credentials and session',
    () async {
      final credentials = MemoryCredentials()..value = {'token': 'synthetic'};
      final api = ProfileApi();
      final chat = ChatController(
        discovery: FakeDiscovery(),
        credentials: credentials,
      )..api = api;
      final controller = ProfileController(chat: chat);
      addTearDown(() {
        controller.dispose();
        chat.dispose();
      });
      expect(
        await controller.changePassword(
          oldPassword: 'old',
          newPassword: 'short',
          confirmation: 'short',
        ),
        isFalse,
      );
      expect(api.passwordChanges, 0);
      expect(
        await controller.changePassword(
          oldPassword: 'old-password1',
          newPassword: 'new-password2',
          confirmation: 'new-password2',
        ),
        isTrue,
      );
      expect(api.passwordChanges, 1);
      expect(chat.session, isNull);
      expect(credentials.value, isNull);
    },
  );

  test('ordinary theme preference survives a real file restart', () async {
    final dir = await Directory.systemTemp.createTemp(
      'meshx-profile-preference-',
    );
    addTearDown(() => dir.delete(recursive: true));
    await FilePreferenceStore(directory: dir).write('themeMode', 'dark');
    expect(await FilePreferenceStore(directory: dir).read('themeMode'), 'dark');
    final text = await File('${dir.path}/preferences.json').readAsString();
    expect(text, isNot(contains('token')));
  });

  test(
    'profile HTTP methods retain path/body/auth and bounded avatar binary rules',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final requests = <Map<String, Object?>>[];
      server.listen((request) async {
        final body = await request.fold<List<int>>(
          <int>[],
          (all, chunk) => all..addAll(chunk),
        );
        requests.add({
          'method': request.method,
          'path': request.uri.path,
          'query': request.uri.queryParameters,
          'auth': request.headers.value(HttpHeaders.authorizationHeader),
          'type': request.headers.contentType?.mimeType,
          'body': body,
        });
        if (request.uri.path.endsWith('/file/content/avatar.png')) {
          request.response.headers.contentType = ContentType('image', 'png');
          request.response.add([137, 80, 78, 71]);
        } else {
          Object? data;
          if (request.uri.path.endsWith('/user/info') ||
              request.uri.path.endsWith('/user/profile')) {
            data = {
              'id': 1,
              'username': 'alice',
              'nickname': 'Alice',
              'avatar': 'letter:A:#5856D6',
              'signature': '',
            };
          } else if (request.uri.path.endsWith('/file/avatar')) {
            data = {
              'url': '/api/v1/file/content/avatar.png',
              'thumbnailUrl': '',
            };
          }
          request.response.headers.contentType = ContentType.json;
          request.response.write(jsonEncode({'code': 200, 'data': data}));
        }
        await request.response.close();
      });
      final origin = Uri.parse('http://127.0.0.1:${server.port}');
      final api = MeshXApi(origin)
        ..restoreCredentials({
          'origin': origin.toString(),
          'userId': 1,
          'username': 'alice',
          'nickname': 'Alice',
          'avatar': '',
          'token': 'synthetic-access',
          'apiPath': '/api/v1',
          'wsPath': '/ws/chat',
          'nodeName': 'test',
        });
      try {
        await api.currentUser();
        await api.updateProfile(nickname: 'Alice', avatar: 'letter:A:#5856D6');
        await api.changePassword('old-password1', 'new-password2');
        await api.uploadAvatar(
          name: 'avatar.png',
          mime: 'image/png',
          bytes: Uint8List.fromList([1, 2, 3]),
        );
        expect(await api.avatarBytes('/api/v1/file/content/avatar.png'), [
          137,
          80,
          78,
          71,
        ]);
        expect(requests.map((r) => '${r['method']} ${r['path']}'), [
          'GET /api/v1/user/info',
          'PUT /api/v1/user/profile',
          'PUT /api/v1/user/password',
          'POST /api/v1/file/avatar',
          'GET /api/v1/file/content/avatar.png',
        ]);
        expect(
          requests.every((r) => r['auth'] == 'Bearer synthetic-access'),
          isTrue,
        );
        expect(
          utf8.decode(requests[1]['body']! as List<int>),
          contains('nickname'),
        );
        expect(requests[3]['type'], 'multipart/form-data');
        expect(
          utf8.decode(requests[3]['body']! as List<int>),
          contains('filename="avatar.png"'),
        );
      } finally {
        api.close();
        await server.close(force: true);
      }
    },
  );
}
