import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';
import 'attachments_models.dart';
import 'models.dart';
import 'friends_models.dart';
import 'groups_models.dart';
import 'profile_models.dart';
import 'broadcast_models.dart';
import 'rest_contract.g.dart';
import 'ws_contract.g.dart';

class ApiException implements Exception {
  const ApiException(this.message, {this.code = 0, this.data});
  final String message;
  final int code;
  final Object? data;
  @override
  String toString() => message;
}

class SessionRevokedException extends ApiException {
  const SessionRevokedException(super.message) : super(code: 403);
}

Uri parseNodeOrigin(String value, {required bool allowLocalHttp}) {
  final uri = Uri.tryParse(value.trim());
  if (uri == null ||
      !uri.hasAuthority ||
      uri.host.isEmpty ||
      uri.userInfo.isNotEmpty ||
      uri.hasQuery ||
      uri.hasFragment ||
      (uri.path.isNotEmpty && uri.path != '/') ||
      !{'http', 'https'}.contains(uri.scheme)) {
    throw const FormatException('请输入完整的节点地址，例如 https://chat.example.com');
  }
  final host = uri.host.toLowerCase();
  final ip = InternetAddress.tryParse(host);
  final bytes = ip?.rawAddress;
  final local =
      host == 'localhost' ||
      host.endsWith('.local') ||
      (ip?.type == InternetAddressType.IPv4 &&
          bytes != null &&
          (bytes[0] == 10 ||
              bytes[0] == 127 ||
              (bytes[0] == 192 && bytes[1] == 168) ||
              (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31))) ||
      (ip?.type == InternetAddressType.IPv6 &&
          (ip!.isLoopback || (bytes![0] & 0xfe) == 0xfc));
  if (uri.scheme != 'https' && !(allowLocalHttp && local)) {
    throw const FormatException('该节点需要 HTTPS；本地 HTTP 仅供调试');
  }
  return uri.replace(path: '');
}

String requestId() {
  final random = Random.secure();
  return List.generate(
    16,
    (_) => random.nextInt(256).toRadixString(16).padLeft(2, '0'),
  ).join();
}

class MeshXApi {
  MeshXApi(this.origin, {HttpClient? client}) : _http = client ?? HttpClient() {
    _http.connectionTimeout = const Duration(seconds: 8);
  }
  final Uri origin;
  final HttpClient _http;
  NodeInfo? node;
  Session? session;
  List<FriendContact>? cachedFriends;
  List<MeshXGroup>? cachedGroups;
  String? _refreshCookie;
  Future<Session>? _refreshing;
  bool _closed = false;
  Future<void> Function(Json)? onCredentialsChanged;
  Json credentials() => {
    'origin': origin.toString(),
    'userId': session!.userId,
    'username': session!.username,
    'nickname': session!.nickname,
    'avatar': session!.avatar,
    'token': session!.token,
    'refreshCookie': _refreshCookie,
    'apiPath': node!.apiPath,
    'wsPath': node!.wsPath,
    'nodeName': node!.name,
  };
  void restoreCredentials(Json value) {
    if (value['origin'] != origin.toString()) {
      throw const FormatException('凭据节点不匹配');
    }
    session = Session.fromJson(value);
    _refreshCookie = value['refreshCookie'] as String?;
    node = NodeInfo.fromJson(origin, {
      'protocolVersion': 1,
      'nodeName': value['nodeName'],
      'apiBasePath': value['apiPath'],
      'webSocketPath': value['wsPath'],
    });
  }

  Future<dynamic> _request(
    String method,
    String path, {
    Json? body,
    Map<String, String>? query,
    bool authorized = true,
    bool retry = true,
    String? idempotencyKey,
    bool allowNoContent = false,
  }) async {
    if (_closed) throw const ApiException('会话已经关闭');
    final uri = origin.replace(path: path, queryParameters: query);
    final request = await _http
        .openUrl(method, uri)
        .timeout(const Duration(seconds: 10));
    if (_closed) {
      request.abort();
      throw const ApiException('会话已经关闭');
    }
    request.followRedirects = false;
    request.headers.set(HttpHeaders.acceptHeader, 'application/json');
    if (idempotencyKey != null) {
      request.headers.set('Idempotency-Key', idempotencyKey);
    }
    final requestToken = authorized ? session?.token : null;
    if (authorized && session != null) {
      request.headers.set(
        HttpHeaders.authorizationHeader,
        'Bearer $requestToken',
      );
    }
    if (_refreshCookie != null &&
        (path.endsWith('/auth/refresh') || path.endsWith('/auth/logout'))) {
      request.cookies.add(Cookie('lanchat_refresh', _refreshCookie!));
    }
    if (body != null) {
      request.headers.contentType = ContentType.json;
      request.write(jsonEncode(body));
    }
    final response = await request.close().timeout(const Duration(seconds: 12));
    if (_closed) throw const ApiException('会话已经关闭');
    for (final cookie in response.cookies) {
      if (cookie.name == 'lanchat_refresh') {
        _refreshCookie = cookie.value.isEmpty ? null : cookie.value;
      }
    }
    final text = await response
        .transform(utf8.decoder)
        .join()
        .timeout(const Duration(seconds: 12));
    if (_closed) throw const ApiException('会话已经关闭');
    if (allowNoContent && response.statusCode == 204 && text.isEmpty) {
      return null;
    }
    Json envelope;
    try {
      envelope = jsonDecode(text) as Json;
    } catch (_) {
      throw const ApiException('节点返回了无法识别的内容，请检查地址');
    }
    if (_closed) throw const ApiException('会话已经关闭');
    final code = integer(envelope['code']);
    if (response.statusCode == 401 || code == 401) {
      if (authorized && retry && _refreshCookie != null) {
        await refresh(rejectedToken: requestToken);
        return _request(
          method,
          path,
          body: body,
          query: query,
          authorized: true,
          retry: false,
          idempotencyKey: idempotencyKey,
          allowNoContent: allowNoContent,
        );
      }
      throw const ApiException('登录已过期，请重新登录', code: 401);
    }
    if (response.statusCode < 200 ||
        response.statusCode >= 300 ||
        code != 200) {
      throw ApiException(
        envelope['msg'] as String? ?? '请求失败，请重试',
        code: code,
        data: envelope['data'],
      );
    }
    return envelope['data'];
  }

  String _recoveryPath(RestOperation operation, [String? id]) => operation
      .atBase(node?.apiPath ?? '/api/v1')
      .replaceAll('{id}', id == null ? '' : Uri.encodeComponent(id));

  Future<Json> recoveryCapabilities() async =>
      await _request(
            RestOperations.capabilities.method,
            _recoveryPath(RestOperations.capabilities),
          )
          as Json;

  Future<Json> openRecovery(
    Json input, {
    required String idempotencyKey,
  }) async =>
      await _request(
            RestOperations.open.method,
            _recoveryPath(RestOperations.open),
            body: jsonDecode(jsonEncode(input)) as Json,
            idempotencyKey: idempotencyKey,
          )
          as Json;

  Future<Json> recoverySnapshot(
    String id, {
    String? pageToken,
    int limit = 100,
  }) async =>
      await _request(
            RestOperations.snapshot.method,
            _recoveryPath(RestOperations.snapshot, id),
            query: {'limit': '$limit', 'pageToken': ?pageToken},
          )
          as Json;

  Future<Json> recoveryCut(String id) async =>
      await _request(
            RestOperations.cut.method,
            _recoveryPath(RestOperations.cut, id),
          )
          as Json;

  Future<Json> recoveryMutations(
    String id,
    String after,
    String through, {
    int limit = 100,
  }) async =>
      await _request(
            RestOperations.mutations.method,
            _recoveryPath(RestOperations.mutations, id),
            query: {'after': after, 'through': through, 'limit': '$limit'},
          )
          as Json;

  Future<Json> recoveryReady(
    String id,
    String cursor, {
    required bool snapshotComplete,
  }) async =>
      await _request(
            RestOperations.ready.method,
            _recoveryPath(RestOperations.ready, id),
            body: {
              'appliedCursor': cursor,
              'snapshotComplete': snapshotComplete,
            },
          )
          as Json;

  Future<void> releaseRecovery(String id) async {
    await _request(
      RestOperations.release.method,
      _recoveryPath(RestOperations.release, id),
      allowNoContent: true,
    );
  }

  Future<NodeInfo> handshake() async {
    final json =
        await _request(
              RestOperations.info_1.method,
              RestOperations.info_1.path,
              authorized: false,
            )
            as Json;
    return node = NodeInfo.fromJson(origin, json);
  }

  Future<Session> login(String username, String password) async {
    final n = node ?? await handshake();
    final json =
        await _request(
              RestOperations.login.method,
              RestOperations.login.atBase(n.apiPath),
              authorized: false,
              body: RestLoginDTO(
                username: username.trim(),
                password: password,
                deviceType: Platform.isIOS ? 'ios' : 'android',
                deviceName: 'MeshX Flutter 对照原型',
              ).toJson(),
            )
            as Json;
    return session = Session.fromJson(json);
  }

  Future<Session> refresh({String? rejectedToken}) {
    if (rejectedToken != null &&
        session != null &&
        session!.token != rejectedToken) {
      return Future.value(session!);
    }
    if (_refreshing != null) return _refreshing!;
    return _refreshing = (() async {
      try {
        final json =
            await _request(
                  RestOperations.refreshToken.method,
                  RestOperations.refreshToken.atBase(node!.apiPath),
                  authorized: false,
                  retry: false,
                  body: {},
                )
                as Json;
        if (_closed) throw const ApiException('会话已经关闭');
        final updated = Session.fromJson(json);
        if (session != null && updated.userId != session!.userId) {
          throw const SessionRevokedException('刷新账号不匹配');
        }
        session = updated;
        await onCredentialsChanged?.call(credentials());
        if (_closed) throw const ApiException('会话已经关闭');
        return updated;
      } finally {
        _refreshing = null;
      }
    })();
  }

  Future<void> logout() async {
    try {
      await _request(
        RestOperations.logout.method,
        RestOperations.logout.atBase(node!.apiPath),
        retry: false,
      );
    } finally {
      session = null;
      _refreshCookie = null;
    }
  }

  Future<void> validateCurrentUser() async {
    final value =
        await _request(
              RestOperations.getCurrentUserInfo.method,
              RestOperations.getCurrentUserInfo.atBase(node!.apiPath),
            )
            as Json;
    if (integer(value['id']) != session?.userId) {
      throw const SessionRevokedException('当前用户与登录账号不匹配');
    }
  }

  Future<Map<String, int>> recoveryReadPositions() async {
    final data = await _request(
      RestOperations.getConversationSummaries.method,
      RestOperations.getConversationSummaries.atBase(node!.apiPath),
    );
    if (data is! List) throw const FormatException('已读摘要格式无效');
    final result = <String, int>{};
    for (final item in data) {
      if (item is! Map ||
          item['conversationId'] is! String ||
          item['lastReadSequence'] is! int ||
          item['lastReadSequence'] < 0) {
        throw const FormatException('已读摘要格式无效');
      }
      result[item['conversationId'] as String] =
          item['lastReadSequence'] as int;
    }
    return result;
  }

  Future<List<Conversation>> conversations() async {
    final values = await Future.wait([
      _request(
        RestOperations.getConversationSummaries.method,
        RestOperations.getConversationSummaries.atBase(node!.apiPath),
      ),
      friends(),
      groups(),
    ]);
    final friendNames = {
      for (final friend in values[1] as List<FriendContact>)
        friend.userId: friend.displayName,
    };
    final groupNames = {
      for (final group in values[2] as List<MeshXGroup>) group.id: group.name,
    };
    return (values[0] as List).map((value) {
      final j = value as Json;
      final target = integer(j['targetId']);
      final kind = (j['kind'] as String).toLowerCase();
      return Conversation(
        id: j['conversationId'],
        targetId: target,
        kind: kind,
        title:
            (kind == 'private' ? friendNames[target] : groupNames[target]) ??
            '会话 $target',
        preview: j['lastMessage'] as String? ?? '',
        unread: integer(j['unreadCount']),
      );
    }).toList();
  }

  Future<List<FriendContact>> friends() async {
    final data = await _request('GET', '${node!.apiPath}/friend/list');
    if (data is! List) throw const FormatException('好友列表格式无效');
    return cachedFriends = data
        .map((value) => FriendContact.fromJson(value as Json))
        .toList(growable: false);
  }

  Future<List<MeshXGroup>> groups() async {
    final data = await _request('GET', '${node!.apiPath}/group/my');
    if (data is! List) throw const FormatException('群组列表格式无效');
    return cachedGroups = data
        .map((value) => MeshXGroup.fromJson(value as Json))
        .toList(growable: false);
  }

  Future<MeshXGroup> groupInfo(int groupId) async {
    if (groupId <= 0) throw const FormatException('群组标识无效');
    final data = await _request('GET', '${node!.apiPath}/group/$groupId');
    if (data is! Json) throw const FormatException('群组资料格式无效');
    final group = MeshXGroup.fromJson(data);
    if (group.id != groupId) throw const FormatException('群组资料标识不匹配');
    return group;
  }

  Future<List<GroupMemberInfo>> groupMembers(int groupId) async {
    if (groupId <= 0) throw const FormatException('群组标识无效');
    final data = await _request(
      'GET',
      '${node!.apiPath}/group/$groupId/members',
    );
    if (data is! List) throw const FormatException('群成员列表格式无效');
    final seen = <int>{};
    final members = <GroupMemberInfo>[];
    for (final value in data) {
      final member = GroupMemberInfo.fromJson(value as Json);
      if (!seen.add(member.userId)) {
        throw const FormatException('群成员列表包含重复用户');
      }
      members.add(member);
    }
    return List.unmodifiable(members);
  }

  Future<MeshXGroup> createGroup(String name, Iterable<int> memberIds) async {
    final clean = name.trim();
    if (clean.length < 2 || clean.length > 20) {
      throw const FormatException('群名称长度需为2-20字符');
    }
    final ids = memberIds.toSet().toList(growable: false);
    if (ids.any((id) => id <= 0) || ids.length > 199) {
      throw const FormatException('群成员选择无效');
    }
    final data = await _request(
      'POST',
      '${node!.apiPath}/group',
      body: {'groupName': clean, 'memberIds': ids},
    );
    if (data is! Json) throw const FormatException('群组资料格式无效');
    return MeshXGroup.fromJson(data);
  }

  Future<void> leaveGroup(int groupId) {
    if (groupId <= 0) {
      return Future.error(const FormatException('群组标识无效'));
    }
    return _request('POST', '${node!.apiPath}/group/$groupId/leave');
  }

  Future<List<FriendRequestItem>> friendRequests() async {
    final data = await _request('GET', '${node!.apiPath}/friend/requests');
    if (data is! List) throw const FormatException('好友申请格式无效');
    final pending = data
        .map((value) => FriendRequestItem.fromJson(value as Json))
        .where((value) => value.status == 0)
        .toList(growable: false);
    final names = <int, String>{};
    for (final request in pending) {
      names[request.fromUserId] ??= await userDisplayName(request.fromUserId);
    }
    return pending
        .map((value) => value.withSenderName(names[value.fromUserId] ?? ''))
        .toList(growable: false);
  }

  Future<String> userDisplayName(int userId) async {
    final data = await _request('GET', '${node!.apiPath}/user/$userId');
    if (data is! Json) throw const FormatException('用户资料格式无效');
    final nickname = (data['nickname'] as String?)?.trim() ?? '';
    final username = (data['username'] as String?)?.trim() ?? '';
    return nickname.isNotEmpty ? nickname : username;
  }

  Future<UserProfile> currentUser() async {
    final data = await _request('GET', '${node!.apiPath}/user/info');
    if (data is! Json) throw const FormatException('用户资料格式无效');
    final profile = UserProfile.fromJson(data);
    if (profile.userId != session?.userId) {
      throw const SessionRevokedException('当前用户与登录账号不匹配');
    }
    return profile;
  }

  Future<UserProfile> updateProfile({
    required String nickname,
    required String avatar,
  }) async {
    final data = await _request(
      'PUT',
      '${node!.apiPath}/user/profile',
      body: {'nickname': nickname.trim(), 'avatar': avatar.trim()},
    );
    if (data is! Json) throw const FormatException('用户资料格式无效');
    final profile = UserProfile.fromJson(data);
    if (profile.userId != session?.userId) {
      throw const SessionRevokedException('更新资料账号不匹配');
    }
    return profile;
  }

  Future<void> changePassword(String oldPassword, String newPassword) =>
      _request(
        'PUT',
        '${node!.apiPath}/user/password',
        body: {'oldPassword': oldPassword, 'newPassword': newPassword},
      );

  Future<List<BroadcastSummary>> broadcasts({bool pending = false}) async {
    final data = await _request(
      'GET',
      '${node!.apiPath}/broadcast${pending ? '/pending' : ''}',
    );
    if (data is! List) throw const FormatException('广播列表格式无效');
    return data
        .map((value) => BroadcastSummary.fromJson(value as Json))
        .toList(growable: false);
  }

  Future<BroadcastDetail> broadcastDetail(int broadcastId) async {
    if (broadcastId <= 0) throw const FormatException('广播标识无效');
    final data = await _request(
      'GET',
      '${node!.apiPath}/broadcast/$broadcastId',
    );
    if (data is! Json) throw const FormatException('广播详情格式无效');
    return BroadcastDetail.fromJson(data);
  }

  Future<BroadcastReceiverState> viewBroadcast(int broadcastId) =>
      _broadcastAction(broadcastId, 'view');

  Future<BroadcastReceiverState> confirmBroadcast(
    int broadcastId,
    String status,
  ) => _broadcastAction(broadcastId, 'confirm', body: {'status': status});

  Future<BroadcastReceiverState> completeBroadcast(
    int broadcastId, {
    List<int> imageFileIds = const [],
  }) => _broadcastAction(
    broadcastId,
    'complete',
    body: {'imageFileIds': imageFileIds},
  );

  Future<BroadcastReceiverState> _broadcastAction(
    int broadcastId,
    String action, {
    Json? body,
  }) async {
    if (broadcastId <= 0) throw const FormatException('广播标识无效');
    final data = await _request(
      'POST',
      '${node!.apiPath}/broadcast/$broadcastId/$action',
      body: body,
    );
    if (data is! Json) throw const FormatException('广播回执格式无效');
    return BroadcastReceiverState.fromJson(data);
  }

  Future<AvatarUpload> uploadAvatar({
    required String name,
    required String mime,
    required List<int> bytes,
  }) => _uploadAvatar(name: name, mime: mime, bytes: bytes, retry: true);

  Future<BroadcastImageUpload> uploadBroadcastImage({
    required String name,
    required String mime,
    required List<int> bytes,
  }) =>
      _uploadBroadcastImage(name: name, mime: mime, bytes: bytes, retry: true);

  Future<FileUploadResult> uploadAttachment({
    required String conversationId,
    required String name,
    required String mime,
    required List<int> bytes,
    bool Function()? cancelled,
    void Function(int sent, int total)? onProgress,
  }) => _uploadAttachment(
    conversationId: conversationId,
    name: name,
    mime: mime,
    size: bytes.length,
    readChunk: (offset, length) async =>
        bytes.sublist(offset, min(offset + length, bytes.length)),
    cancelled: cancelled,
    onProgress: onProgress,
    retry: true,
  );

  Future<FileUploadResult> uploadAttachmentStream({
    required String conversationId,
    required String name,
    required String mime,
    required int size,
    required Future<List<int>> Function(int offset, int length) readChunk,
    bool Function()? cancelled,
    void Function(int sent, int total)? onProgress,
  }) => _uploadAttachment(
    conversationId: conversationId,
    name: name,
    mime: mime,
    size: size,
    readChunk: readChunk,
    cancelled: cancelled,
    onProgress: onProgress,
    retry: true,
  );

  Future<FileUploadResult> _uploadAttachment({
    required String conversationId,
    required String name,
    required String mime,
    required int size,
    required Future<List<int>> Function(int offset, int length) readChunk,
    required bool retry,
    bool Function()? cancelled,
    void Function(int sent, int total)? onProgress,
  }) async {
    if (_closed) throw const ApiException('会话已经关闭');
    if (session == null) throw const ApiException('请先登录');
    if (conversationId.isEmpty) throw const ApiException('会话标识无效');
    if (size <= 0 || size > attachmentByteLimit) {
      throw const ApiException('文件不能超过 25 MiB');
    }
    if (!RegExp(r'^[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+$').hasMatch(mime)) {
      throw const ApiException('文件类型无效');
    }
    if (cancelled?.call() == true) throw const AttachmentCancelledException();
    final safeName = name.replaceAll(RegExp(r'[\\/\r\n\"]'), '_').trim();
    final filename = safeName.isEmpty
        ? 'attachment'
        : safeName.substring(0, min(120, safeName.length));
    final boundary = 'meshx-${requestId()}';
    final conversationPart = utf8.encode(
      '--$boundary\r\n'
      'Content-Disposition: form-data; name="conversationId"\r\n\r\n'
      '$conversationId\r\n',
    );
    final filePart = utf8.encode(
      '--$boundary\r\n'
      'Content-Disposition: form-data; name="file"; filename="$filename"\r\n'
      'Content-Type: $mime\r\n\r\n',
    );
    final footer = utf8.encode('\r\n--$boundary--\r\n');
    final uri = origin.replace(path: '${node!.apiPath}/file/upload');
    final request = await _http
        .postUrl(uri)
        .timeout(const Duration(seconds: 10));
    // Observe early transport/abort errors even before close() is reached.
    // The awaited close() below still reports errors on the normal path.
    unawaited(
      request.done.then<void>((_) {}, onError: (Object _, StackTrace _) {}),
    );
    final requestToken = session?.token;
    request.followRedirects = false;
    request.headers.set(HttpHeaders.acceptHeader, 'application/json');
    request.headers.set(
      HttpHeaders.authorizationHeader,
      'Bearer $requestToken',
    );
    request.headers.set(
      HttpHeaders.contentTypeHeader,
      'multipart/form-data; boundary=$boundary',
    );
    request.contentLength =
        conversationPart.length + filePart.length + size + footer.length;
    StreamSubscription<String>? responseSubscription;
    Completer<String>? responseText;
    // Cancellation must also interrupt close() while the server is silent.
    final cancellationWatch = cancelled == null
        ? null
        : Timer.periodic(const Duration(milliseconds: 100), (timer) {
            if (cancelled()) {
              timer.cancel();
              request.abort(const AttachmentCancelledException());
              final pendingText = responseText;
              if (pendingText != null && !pendingText.isCompleted) {
                pendingText.completeError(const AttachmentCancelledException());
                unawaited(responseSubscription?.cancel());
              }
            }
          });
    try {
      request.add(conversationPart);
      request.add(filePart);
      const chunkSize = 256 * 1024;
      for (var offset = 0; offset < size; offset += chunkSize) {
        if (cancelled?.call() == true) {
          request.abort();
          throw const AttachmentCancelledException();
        }
        final expected = min(chunkSize, size - offset);
        final chunk = await readChunk(offset, expected);
        // Native reads can finish after the user cancels or switches account.
        if (cancelled?.call() == true) {
          throw const AttachmentCancelledException();
        }
        if (chunk.length != expected) {
          request.abort();
          throw const ApiException('文件在读取期间发生变化');
        }
        request.add(chunk);
        final end = offset + chunk.length;
        onProgress?.call(end, size);
        await Future<void>.delayed(Duration.zero);
      }
      if (cancelled?.call() == true) {
        throw const AttachmentCancelledException();
      }
      request.add(footer);
      final response = await request.close().timeout(
        const Duration(seconds: 45),
      );
      responseText = Completer<String>();
      final buffer = StringBuffer();
      responseSubscription = response
          .transform(utf8.decoder)
          .listen(
            buffer.write,
            onError: (Object error, StackTrace stack) {
              if (!responseText!.isCompleted) {
                responseText.completeError(error, stack);
              }
            },
            onDone: () {
              if (!responseText!.isCompleted) {
                responseText.complete(buffer.toString());
              }
            },
            cancelOnError: true,
          );
      final text = await responseText.future.timeout(
        const Duration(seconds: 45),
      );
      if (cancelled?.call() == true) {
        throw const AttachmentCancelledException();
      }
      Json envelope;
      try {
        envelope = jsonDecode(text) as Json;
      } catch (_) {
        throw const ApiException('节点返回了无法识别的内容，请检查地址');
      }
      final code = integer(envelope['code']);
      if (response.statusCode == 401 || code == 401) {
        if (retry && _refreshCookie != null) {
          await refresh(rejectedToken: requestToken);
          return _uploadAttachment(
            conversationId: conversationId,
            name: name,
            mime: mime,
            size: size,
            readChunk: readChunk,
            retry: false,
            cancelled: cancelled,
            onProgress: onProgress,
          );
        }
        throw const ApiException('登录已过期，请重新登录', code: 401);
      }
      if (response.statusCode < 200 ||
          response.statusCode >= 300 ||
          code != 200) {
        throw ApiException(envelope['msg'] as String? ?? '文件上传失败', code: code);
      }
      final data = envelope['data'];
      if (data is! Json) throw const FormatException('文件上传结果格式无效');
      return FileUploadResult.fromJson(data);
    } catch (_) {
      request.abort();
      if (cancelled?.call() == true) {
        throw const AttachmentCancelledException();
      }
      rethrow;
    } finally {
      cancellationWatch?.cancel();
      await responseSubscription?.cancel();
    }
  }

  Future<DownloadedAttachment> downloadAttachment(
    AttachmentData attachment, {
    bool thumbnail = false,
    bool retry = true,
    bool Function()? cancelled,
    void Function(int received, int total)? onProgress,
  }) async {
    if (_closed) throw const ApiException('会话已经关闭');
    if (session == null) throw const ApiException('请先登录');
    final raw = thumbnail && attachment.thumbnailUrl != null
        ? attachment.thumbnailUrl!
        : (attachment.originalUrl ?? attachment.url);
    // Parsing the stored name rejects absolute, foreign-origin and malformed URLs.
    storedFileName(raw);
    final uri = origin.resolve(raw);
    if (uri.origin != origin.origin) {
      throw const ApiException('附件地址不属于当前节点');
    }
    if (cancelled?.call() == true) throw const AttachmentCancelledException();
    final request = await _http
        .getUrl(uri)
        .timeout(const Duration(seconds: 10));
    final requestToken = session?.token;
    request.followRedirects = false;
    request.headers.set(
      HttpHeaders.authorizationHeader,
      'Bearer $requestToken',
    );
    final response = await request.close().timeout(const Duration(seconds: 30));
    if (response.isRedirect) {
      await response.drain<void>();
      throw const ApiException('附件下载不允许重定向');
    }
    if (response.statusCode == 401) {
      await response.drain<void>();
      if (retry && _refreshCookie != null) {
        await refresh(rejectedToken: requestToken);
        return downloadAttachment(
          attachment,
          thumbnail: thumbnail,
          retry: false,
          cancelled: cancelled,
          onProgress: onProgress,
        );
      }
      throw const ApiException('登录已过期，请重新登录', code: 401);
    }
    if (response.statusCode < 200 || response.statusCode >= 300) {
      await response.drain<void>();
      throw ApiException('附件读取失败（HTTP ${response.statusCode}）');
    }
    final expected = thumbnail ? null : attachment.size;
    if (response.contentLength > attachmentByteLimit ||
        (expected != null &&
            response.contentLength >= 0 &&
            response.contentLength != expected)) {
      await response.drain<void>();
      throw const ApiException('附件大小校验失败');
    }
    final builder = BytesBuilder(copy: false);
    await for (final chunk in response) {
      if (cancelled?.call() == true) throw const AttachmentCancelledException();
      builder.add(chunk);
      if (builder.length > attachmentByteLimit ||
          (expected != null && builder.length > expected)) {
        throw const ApiException('附件大小校验失败');
      }
      onProgress?.call(builder.length, expected ?? response.contentLength);
    }
    final bytes = builder.takeBytes();
    if (bytes.isEmpty || (expected != null && bytes.length != expected)) {
      throw const ApiException('附件大小校验失败');
    }
    if (!thumbnail && sha256.convert(bytes).toString() != attachment.fileHash) {
      throw const ApiException('附件哈希校验失败');
    }
    final contentType =
        response.headers.contentType?.mimeType.toLowerCase() ?? '';
    if (thumbnail && !contentType.startsWith('image/')) {
      throw const ApiException('缩略图不是图片');
    }
    return DownloadedAttachment(
      bytes: bytes,
      mime: contentType.isEmpty ? attachment.mime : contentType,
      name: attachment.name,
    );
  }

  Future<AvatarUpload> _uploadAvatar({
    required String name,
    required String mime,
    required List<int> bytes,
    required bool retry,
  }) async {
    if (_closed) throw const ApiException('会话已经关闭');
    if (bytes.isEmpty || bytes.length > 5 * 1024 * 1024) {
      throw const ApiException('头像图片不能超过 5MB');
    }
    if (!RegExp(r'^image/[a-zA-Z0-9.+-]+$').hasMatch(mime)) {
      throw const ApiException('头像必须是图片文件');
    }
    final boundary = 'meshx-${requestId()}';
    final safeName = name
        .replaceAll(RegExp(r'[\\/\r\n\"]'), '_')
        .trim()
        .substring(
          0,
          min(120, name.replaceAll(RegExp(r'[\\/\r\n\"]'), '_').trim().length),
        );
    final header = utf8.encode(
      '--$boundary\r\n'
      'Content-Disposition: form-data; name="file"; filename="${safeName.isEmpty ? 'avatar' : safeName}"\r\n'
      'Content-Type: $mime\r\n\r\n',
    );
    final footer = utf8.encode('\r\n--$boundary--\r\n');
    final uri = origin.replace(path: '${node!.apiPath}/file/avatar');
    final request = await _http
        .postUrl(uri)
        .timeout(const Duration(seconds: 10));
    final requestToken = session?.token;
    request.followRedirects = false;
    request.headers.set(HttpHeaders.acceptHeader, 'application/json');
    request.headers.set(
      HttpHeaders.authorizationHeader,
      'Bearer $requestToken',
    );
    request.headers.set(
      HttpHeaders.contentTypeHeader,
      'multipart/form-data; boundary=$boundary',
    );
    request.contentLength = header.length + bytes.length + footer.length;
    request.add(header);
    request.add(bytes);
    request.add(footer);
    final response = await request.close().timeout(const Duration(seconds: 30));
    final text = await response.transform(utf8.decoder).join();
    Json envelope;
    try {
      envelope = jsonDecode(text) as Json;
    } catch (_) {
      throw const ApiException('节点返回了无法识别的内容，请检查地址');
    }
    final code = integer(envelope['code']);
    if (response.statusCode == 401 || code == 401) {
      if (retry && _refreshCookie != null) {
        await refresh(rejectedToken: requestToken);
        return _uploadAvatar(
          name: name,
          mime: mime,
          bytes: bytes,
          retry: false,
        );
      }
      throw const ApiException('登录已过期，请重新登录', code: 401);
    }
    if (response.statusCode < 200 ||
        response.statusCode >= 300 ||
        code != 200) {
      throw ApiException(envelope['msg'] as String? ?? '头像上传失败', code: code);
    }
    final data = envelope['data'];
    if (data is! Json) throw const FormatException('头像上传结果格式无效');
    return AvatarUpload.fromJson(data);
  }

  Future<BroadcastImageUpload> _uploadBroadcastImage({
    required String name,
    required String mime,
    required List<int> bytes,
    required bool retry,
  }) async {
    if (_closed) throw const ApiException('会话已经关闭');
    if (bytes.isEmpty || bytes.length > 5 * 1024 * 1024) {
      throw const ApiException('广播凭证图片不能超过 5MB');
    }
    if (!RegExp(r'^image/[a-zA-Z0-9.+-]+$').hasMatch(mime)) {
      throw const ApiException('广播凭证必须是图片');
    }
    final cleaned = name.replaceAll(RegExp(r'[\\/\r\n\"]'), '_').trim();
    final safeName = cleaned.isEmpty
        ? 'broadcast-evidence'
        : cleaned.substring(0, min(120, cleaned.length));
    final boundary = 'meshx-${requestId()}';
    final header = utf8.encode(
      '--$boundary\r\n'
      'Content-Disposition: form-data; name="file"; filename="$safeName"\r\n'
      'Content-Type: $mime\r\n\r\n',
    );
    final footer = utf8.encode('\r\n--$boundary--\r\n');
    final request = await _http
        .postUrl(origin.replace(path: '${node!.apiPath}/file/broadcast-image'))
        .timeout(const Duration(seconds: 10));
    final requestToken = session?.token;
    request.followRedirects = false;
    request.headers.set(HttpHeaders.acceptHeader, 'application/json');
    request.headers.set(
      HttpHeaders.authorizationHeader,
      'Bearer $requestToken',
    );
    request.headers.set(
      HttpHeaders.contentTypeHeader,
      'multipart/form-data; boundary=$boundary',
    );
    request.contentLength = header.length + bytes.length + footer.length;
    request.add(header);
    request.add(bytes);
    request.add(footer);
    final response = await request.close().timeout(const Duration(seconds: 30));
    final text = await response.transform(utf8.decoder).join();
    Json envelope;
    try {
      envelope = jsonDecode(text) as Json;
    } catch (_) {
      throw const ApiException('节点返回了无法识别的内容，请检查地址');
    }
    final code = integer(envelope['code']);
    if (response.statusCode == 401 || code == 401) {
      if (retry && _refreshCookie != null) {
        await refresh(rejectedToken: requestToken);
        return _uploadBroadcastImage(
          name: name,
          mime: mime,
          bytes: bytes,
          retry: false,
        );
      }
      throw const ApiException('登录已过期，请重新登录', code: 401);
    }
    if (response.statusCode < 200 ||
        response.statusCode >= 300 ||
        code != 200) {
      throw ApiException(envelope['msg'] as String? ?? '凭证图片上传失败', code: code);
    }
    final data = envelope['data'];
    if (data is! Json) throw const FormatException('凭证图片上传结果无效');
    return BroadcastImageUpload.fromJson(data);
  }

  Future<Uint8List> avatarBytes(String raw, {bool retry = true}) =>
      _imageBytes(raw, retry: retry, label: '头像');

  Future<Uint8List> broadcastImageBytes(int broadcastId, String raw) async {
    if (_closed || session == null) throw const ApiException('请先登录');
    final userId = session!.userId;
    storedFileName(raw);
    final detail = await broadcastDetail(broadcastId);
    if (_closed ||
        session?.userId != userId ||
        detail.receiver?.targetStatus != 'ACTIVE' ||
        !detail.contentImageUrls.contains(raw)) {
      throw const ApiException('图片不属于当前广播');
    }
    final bytes = await _imageBytes(raw, retry: true, label: '广播图片');
    final current = await broadcastDetail(broadcastId);
    if (_closed ||
        session?.userId != userId ||
        current.receiver?.targetStatus != 'ACTIVE' ||
        !current.contentImageUrls.contains(raw)) {
      throw const ApiException('广播图片已不可用');
    }
    return bytes;
  }

  Future<Uint8List> _imageBytes(
    String raw, {
    required bool retry,
    required String label,
  }) async {
    final parsed = Uri.tryParse(raw.trim());
    if (parsed == null || raw.trim().isEmpty) {
      throw const FormatException('头像地址无效');
    }
    final uri = parsed.hasScheme ? parsed : origin.resolveUri(parsed);
    final sameOrigin = uri.origin == origin.origin;
    if (!sameOrigin && uri.scheme != 'https') {
      throw const ApiException('外部头像需要 HTTPS');
    }
    final request = await _http
        .getUrl(uri)
        .timeout(const Duration(seconds: 10));
    request.followRedirects = false;
    final requestToken = sameOrigin ? session?.token : null;
    if (requestToken != null) {
      request.headers.set(
        HttpHeaders.authorizationHeader,
        'Bearer $requestToken',
      );
    }
    final response = await request.close().timeout(const Duration(seconds: 15));
    if (sameOrigin && response.statusCode == 401) {
      await response.drain<void>();
      if (retry && _refreshCookie != null) {
        await refresh(rejectedToken: requestToken);
        return _imageBytes(raw, retry: false, label: label);
      }
      throw const ApiException('登录已过期，请重新登录', code: 401);
    }
    if (response.statusCode < 200 || response.statusCode >= 300) {
      await response.drain<void>();
      throw ApiException('$label读取失败（HTTP ${response.statusCode}）');
    }
    final type = response.headers.contentType?.mimeType.toLowerCase() ?? '';
    if (!type.startsWith('image/')) {
      await response.drain<void>();
      throw ApiException('$label内容不是图片');
    }
    const limit = 5 * 1024 * 1024;
    if (response.contentLength > limit) {
      await response.drain<void>();
      throw ApiException('$label不能超过 5MB');
    }
    final builder = BytesBuilder(copy: false);
    await for (final chunk in response) {
      builder.add(chunk);
      if (builder.length > limit) {
        throw ApiException('$label不能超过 5MB');
      }
    }
    final bytes = builder.takeBytes();
    if (bytes.isEmpty) throw ApiException('$label内容为空');
    return bytes;
  }

  Future<List<UserSearchResult>> searchUsers(String keyword) async {
    final data = await _request(
      'GET',
      '${node!.apiPath}/user/search',
      query: {'keyword': keyword.trim()},
    );
    if (data is! List) throw const FormatException('用户搜索结果格式无效');
    return data
        .map((value) => UserSearchResult.fromJson(value as Json))
        .toList(growable: false);
  }

  Future<void> sendFriendRequest(int toUserId, String message) => _request(
    'POST',
    '${node!.apiPath}/friend/request',
    body: {'toUserId': toUserId, 'message': message.trim()},
  );

  Future<void> handleFriendRequest(int requestId, bool accept) => _request(
    'POST',
    '${node!.apiPath}/friend/handle',
    body: {'requestId': requestId, 'accept': accept},
  );

  Future<void> deleteFriend(int friendId) =>
      _request('DELETE', '${node!.apiPath}/friend/$friendId');

  Future<void> setFriendRemark(int friendId, String remark) => _request(
    'PUT',
    '${node!.apiPath}/friend/$friendId/remark',
    query: {'remark': remark},
  );

  Future<List<ChatMessage>> history(
    String conversationId, {
    int? before,
    int limit = 50,
  }) async {
    final json =
        await _request(
              RestOperations.getConversationHistory.method,
              RestOperations.getConversationHistory.atBase(node!.apiPath),
              query: {
                'conversationId': conversationId,
                'limit': '$limit',
                if (before != null) 'beforeSequence': '$before',
              },
            )
            as List;
    return json.map((j) => ChatMessage.fromJson(j as Json)).toList();
  }

  Future<void> markRead(String id, int sequence) async {
    await _request(
      'PUT',
      '${node!.apiPath}/chat/conversation/read',
      query: {'conversationId': id, 'lastReadSequence': '$sequence'},
    );
  }

  Future<Map<int, String>> groupMemberNames(int groupId) async {
    return {
      for (final member in await groupMembers(groupId))
        member.userId: member.displayName,
    };
  }

  void close() {
    _closed = true;
    onCredentialsChanged = null;
    session = null;
    _refreshCookie = null;
    _http.close(force: true);
  }
}

class RealtimeConnection {
  RealtimeConnection(this.api);
  final MeshXApi api;
  WebSocket? _socket;
  StreamSubscription<dynamic>? _subscription;
  Timer? _heartbeat;
  final events = StreamController<Json>.broadcast();
  final disconnected = StreamController<void>.broadcast();
  Completer<void>? _authenticated;
  bool _closed = false;
  final Map<String, Completer<Json>> _requests = {};
  Future<Json> synchronize(Map<String, int> positions) async {
    final id = requestId(), done = Completer<Json>();
    _requests[id] = done;
    try {
      _sendFrame(
        WsEvents.sync_request,
        SyncRequestPayload(positions: positions, limit: 200).toJson(),
        id,
      );
      return await done.future.timeout(const Duration(seconds: 15));
    } finally {
      _requests.remove(id);
    }
  }

  Future<void> connect() async {
    final node = api.node!;
    final uri = node.origin.replace(
      scheme: node.origin.scheme == 'https' ? 'wss' : 'ws',
      path: node.wsPath,
    );
    _socket = await WebSocket.connect(
      uri.toString(),
    ).timeout(const Duration(seconds: 10));
    if (_closed) {
      await _socket!.close();
      return;
    }
    _authenticated = Completer<void>();
    _subscription = _socket!.listen(
      (data) {
        try {
          final event = jsonDecode(data as String) as Json;
          final frame = WsFrame.fromJson(event);
          if (frame.isKnown('server')) frame.validateKnownEvent('server');
          if (event['event'] == WsEvents.sync_response) {
            final pending = _requests[event['requestId']];
            if (pending != null && !pending.isCompleted) {
              pending.complete(event['payload'] as Json);
            }
            return;
          }
          if (event['event'] == 'AUTH_OK' && !_authenticated!.isCompleted) {
            _authenticated!.complete();
          } else if (!_authenticated!.isCompleted) {
            final name = event['event'];
            if (name == 'TOKEN_EXPIRED' ||
                name == 'FORCE_LOGOUT' ||
                name == 'ERROR') {
              final message =
                  (event['payload'] as Json?)?['message'] as String?;
              _authenticated!.completeError(
                name == 'FORCE_LOGOUT'
                    ? SessionRevokedException(message ?? '设备会话已经失效')
                    : ApiException(
                        message ?? '实时连接认证失败',
                        code: name == 'TOKEN_EXPIRED' ? 401 : 0,
                      ),
              );
            }
            return;
          }
          events.add(event);
        } catch (error) {
          if (!_authenticated!.isCompleted) {
            _authenticated!.completeError(error);
          }
        }
      },
      onDone: _onDisconnect,
      onError: (Object error) {
        _onDisconnect();
      },
    );
    send('AUTH', {'token': api.session!.token});
    await _authenticated!.future.timeout(const Duration(seconds: 10));
    _heartbeat = Timer.periodic(const Duration(seconds: 25), (_) {
      try {
        send(WsEvents.ping, {});
      } catch (_) {
        _onDisconnect();
      }
    });
  }

  void _onDisconnect() {
    _heartbeat?.cancel();
    for (final pending in _requests.values) {
      if (!pending.isCompleted) {
        pending.completeError(const ApiException('同步连接已中断'));
      }
    }
    if (_authenticated != null && !_authenticated!.isCompleted) {
      _authenticated!.completeError(const ApiException('连接已中断'));
    }
    if (!_closed) disconnected.add(null);
  }

  void send(
    String event,
    Json payload, {
    String? clientMsgId,
    String? conversationId,
  }) {
    if (_closed || _socket?.readyState != WebSocket.open) {
      throw const ApiException('连接已断开，请重连后重试');
    }
    _sendFrame(
      event,
      payload,
      requestId(),
      clientMsgId: clientMsgId,
      conversationId: conversationId,
    );
  }

  void _sendFrame(
    String event,
    Json payload,
    String id, {
    String? clientMsgId,
    String? conversationId,
  }) {
    if (_closed || _socket?.readyState != WebSocket.open) {
      throw const ApiException('连接已断开');
    }
    _socket!.add(
      jsonEncode({
        'version': 1,
        'event': event,
        'requestId': id,
        'timestamp': DateTime.now().millisecondsSinceEpoch,
        'clientMsgId': ?clientMsgId,
        'conversationId': ?conversationId,
        'payload': payload,
      }),
    );
  }

  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    _onDisconnect();
    _heartbeat?.cancel();
    await _subscription?.cancel();
    await _socket?.close();
    await events.close();
    await disconnected.close();
  }
}
