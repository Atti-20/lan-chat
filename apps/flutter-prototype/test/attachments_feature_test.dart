import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/application/attachment_controller.dart';
import 'package:meshx_flutter_probe/application/platform_coordinator.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/core/models.dart';
import 'package:meshx_flutter_probe/data/attachments_models.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';
import 'platform_fakes.dart';

const attachmentConversation = Conversation(
  id: 'private:1:2',
  targetId: 2,
  kind: 'private',
  title: '附件测试',
);

const attachment = AttachmentData(
  url: '/api/v1/file/content/0123456789abcdef0123456789abcdef.png',
  originalUrl: '/api/v1/file/content/0123456789abcdef0123456789abcdef.png',
  thumbnailUrl:
      '/api/v1/file/content/thumb_0123456789abcdef0123456789abcdef.png',
  name: 'meshx.png',
  size: 3,
  mime: 'image/png',
  fileHash: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
);

class AttachmentApi extends MeshXApi {
  AttachmentApi() : super(Uri.parse('https://attachments.invalid')) {
    node = NodeInfo(origin, '附件测试', '', '/api/v1', '/ws/chat');
    session = const Session(1, 'Me', 'token');
  }

  int uploads = 0, downloads = 0;
  final uploadedNames = <String>[];
  Object? uploadFailure;
  Completer<FileUploadResult>? uploadGate;

  @override
  Future<void> validateCurrentUser() async {}
  @override
  Future<List<Conversation>> conversations() async => [attachmentConversation];
  @override
  Future<List<ChatMessage>> history(
    String conversationId, {
    int? before,
    int limit = 50,
  }) async => const [];
  @override
  Future<FileUploadResult> uploadAttachmentStream({
    required String conversationId,
    required String name,
    required String mime,
    required int size,
    required Future<List<int>> Function(int offset, int length) readChunk,
    bool Function()? cancelled,
    void Function(int sent, int total)? onProgress,
  }) async {
    uploads++;
    uploadedNames.add(name);
    if (uploadFailure case final Object failure) throw failure;
    final bytes = await readChunk(0, size);
    if (bytes.length != size) throw const ApiException('文件读取失败');
    onProgress?.call(size, size);
    return uploadGate?.future ?? const FileUploadResult(attachment: attachment);
  }

  @override
  Future<DownloadedAttachment> downloadAttachment(
    AttachmentData attachment, {
    bool thumbnail = false,
    bool retry = true,
    bool Function()? cancelled,
    void Function(int received, int total)? onProgress,
  }) async {
    downloads++;
    onProgress?.call(3, 3);
    return const DownloadedAttachment(
      bytes: [1, 2, 3],
      mime: 'image/png',
      name: 'meshx.png',
    );
  }
}

class AttachmentWire extends RealtimeConnection {
  AttachmentWire(super.api);
  final frames = <Json>[];
  @override
  Future<void> connect() async {}
  @override
  Future<Json> synchronize(Map<String, int> positions) async => {
    'messages': <Json>[],
    'latestPositions': {for (final id in positions.keys) id: 0},
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
    frames.add({
      'event': event,
      'payload': Map<String, dynamic>.from(payload),
      'clientMsgId': clientMsgId,
      'conversationId': conversationId,
    });
  }
}

PlatformCoordinator attachmentPlatform(
  ChatController chat,
  FakeSystem system,
) => PlatformCoordinator(
  chat: chat,
  lifecycle: FakeLifecycle(),
  notifications: system,
  files: system,
  sharePort: system,
  network: system,
  settings: system,
);

Future<void> eventually(bool Function() condition) async {
  for (var i = 0; i < 100 && !condition(); i++) {
    await Future<void>.delayed(const Duration(milliseconds: 5));
  }
  expect(condition(), isTrue);
}

void main() {
  for (final failure in <Object>[
    const ApiException('文件上传暂时不可用，请稍后重试', code: 503),
    const SocketException('fixture connection closed'),
    TimeoutException('fixture timeout'),
  ]) {
    test(
      'upload ${failure.runtimeType} releases selection and retries once',
      () async {
        final api = AttachmentApi()..uploadFailure = failure;
        final wire = AttachmentWire(api);
        final chat = ChatController(connectionFactory: (_) => wire)..api = api;
        await chat.reconnect();
        chat.active = attachmentConversation;
        final system = FakeSystem()..selectedBytes = [1, 2, 3];
        final platform = attachmentPlatform(chat, system)..start();
        await platform.drain();
        final controller = AttachmentController(chat: chat, platform: platform);
        void select(String handle) {
          system.pickGate = Completer<CapabilityResult<SelectedFile>>()
            ..complete(
              CapabilityResult(
                CapabilityStatus.success,
                value: SelectedFile(
                  handle: handle,
                  name: '$handle.png',
                  mime: 'image/png',
                  size: 3,
                ),
              ),
            );
        }

        try {
          final releases = system.releaseCount;
          select('failed');
          expect(await controller.pickAndSend(), isFalse);
          expect(controller.error, isNotEmpty);
          expect(controller.busy, isFalse);
          expect(controller.completedBytes, 0);
          expect(controller.totalBytes, 0);
          expect(platform.selectedFile, isNull);
          expect(system.releaseCount, releases + 1);
          expect(wire.frames, isEmpty);
          expect(chat.messages, isEmpty);

          // Closing an error and cancelling a picker must not retry the old file.
          controller.clearError();
          system.pickGate = null;
          expect(await controller.pickAndSend(), isFalse);
          expect(controller.error, isNull);
          expect(api.uploads, 1);
          expect(wire.frames, isEmpty);

          api.uploadFailure = null;
          api.uploadGate = Completer<FileUploadResult>();
          select('retry');
          final retry = controller.pickAndSend();
          await eventually(() => api.uploads == 2);
          expect(await controller.pickAndSend(), isFalse);
          expect(system.pickCount, 3);
          expect(wire.frames, isEmpty);
          api.uploadGate!.complete(
            const FileUploadResult(attachment: attachment),
          );
          expect(await retry, isTrue);
          expect(api.uploadedNames, ['failed.png', 'retry.png']);
          expect(wire.frames, hasLength(1));
          expect(platform.selectedFile, isNull);
          expect(system.releaseCount, releases + 2);
          expect(controller.error, isNull);
          expect(controller.busy, isFalse);
        } finally {
          controller.dispose();
          platform.dispose();
          chat.dispose();
          await system.dispose();
        }
      },
    );
  }
  for (final status in [
    CapabilityStatus.cancelled,
    CapabilityStatus.permissionDenied,
    CapabilityStatus.failed,
  ]) {
    test(
      'stale selection is not sent after picker $status and retry uses new file',
      () async {
        final api = AttachmentApi(), wire = AttachmentWire(api);
        final chat = ChatController(connectionFactory: (_) => wire)..api = api;
        await chat.reconnect();
        chat.active = attachmentConversation;
        final system = FakeSystem()..selectedBytes = [1, 2, 3];
        final platform = attachmentPlatform(chat, system)..start();
        await platform.drain();
        final controller = AttachmentController(chat: chat, platform: platform);
        const stale = SelectedFile(
          handle: 'old',
          name: 'previous.txt',
          mime: 'text/plain',
          size: 3,
        );
        platform.selectedFile = stale;
        platform.fileStatus = const CapabilityResult(CapabilityStatus.success);
        try {
          platform.busy = true;
          expect(await controller.pickAndSend(), isFalse);
          expect(api.uploads, 0);
          expect(system.pickCount, 0);
          expect(platform.selectedFile, same(stale));
          platform.busy = false;
          system.pickGate = Completer<CapabilityResult<SelectedFile>>()
            ..complete(CapabilityResult(status));
          expect(await controller.pickAndSend(), isFalse);
          expect(api.uploads, 0);
          expect(wire.frames, isEmpty);
          expect(platform.selectedFile, same(stale));
          expect(controller.busy, isFalse);
          if (status == CapabilityStatus.cancelled) {
            expect(controller.error, isNull);
          }
          system.pickGate = Completer<CapabilityResult<SelectedFile>>()
            ..complete(
              const CapabilityResult(
                CapabilityStatus.success,
                value: SelectedFile(
                  handle: 'new',
                  name: 'new.png',
                  mime: 'image/png',
                  size: 3,
                ),
              ),
            );
          expect(await controller.pickAndSend(), isTrue);
          expect(api.uploadedNames, ['new.png']);
          expect(wire.frames, hasLength(1));
          expect(platform.selectedFile, isNull);
        } finally {
          controller.dispose();
          platform.dispose();
          chat.dispose();
          await system.dispose();
        }
      },
    );
  }
  test(
    'attachment DTO fails closed for foreign, direct and oversized data',
    () {
      expect(
        AttachmentData.decode(attachment.encode()).storedName,
        contains('.png'),
      );
      for (final invalid in [
        {
          ...attachment.toJson(),
          'url': 'https://evil.invalid/file.png',
          'originalUrl': 'https://evil.invalid/file.png',
        },
        {...attachment.toJson(), 'transferPath': 'PEER_TO_PEER'},
        {...attachment.toJson(), 'size': attachmentByteLimit + 1},
        {...attachment.toJson(), 'fileHash': 'bad'},
      ]) {
        expect(() => AttachmentData.fromJson(invalid), throwsFormatException);
      }
    },
  );

  for (final cancelDuringRead in [true, false]) {
    test(
      'cancellation at final chunk (read=$cancelDuringRead) never submits the file',
      () async {
        final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
        var completeRequests = 0;
        server.listen((request) async {
          try {
            await request.drain<void>();
            completeRequests++;
            request.response.statusCode = 503;
            request.response.write('{"code":503,"msg":"unexpected upload"}');
            await request.response.close();
          } catch (_) {
            // Aborted multipart requests are expected.
          }
        });
        final api = MeshXApi(Uri.parse('http://127.0.0.1:${server.port}'));
        api.node = NodeInfo(api.origin, 'test', '', '/api/v1', '/ws/chat');
        api.session = const Session(1, 'Me', 'token');
        var cancelled = false;
        var progressCalls = 0;
        try {
          await expectLater(
            api.uploadAttachmentStream(
              conversationId: attachmentConversation.id,
              name: 'cancelled.txt',
              mime: 'text/plain',
              size: 4,
              readChunk: (offset, length) async {
                cancelled = cancelDuringRead;
                return [1, 2, 3, 4];
              },
              cancelled: () => cancelled,
              onProgress: (_, _) {
                progressCalls++;
                cancelled = true;
              },
            ),
            throwsA(isA<AttachmentCancelledException>()),
          );
          expect(progressCalls, cancelDuringRead ? 0 : 1);
          expect(completeRequests, 0);
        } finally {
          api.close();
          await server.close(force: true);
        }
      },
    );
  }

  for (final partialResponse in [false, true]) {
    test(
      'cancel while waiting for upload response (partial=$partialResponse) settles without the server',
      () async {
        final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
        final received = Completer<void>();
        final releaseResponse = Completer<void>();
        var requests = 0;
        server.listen((request) async {
          await request.drain<void>();
          requests++;
          if (requests == 2) {
            request.response.headers.contentType = ContentType.json;
            request.response.write(
              jsonEncode({
                'code': 200,
                'data': {
                  'url':
                      '/api/v1/file/content/0123456789abcdef0123456789abcdef.txt',
                  'originalName': 'retry.txt',
                  'fileName': '0123456789abcdef0123456789abcdef.txt',
                  'fileSize': 4,
                  'fileType': 'text/plain',
                  'fileHash': sha256.convert([5, 6, 7, 8]).toString(),
                },
              }),
            );
            await request.response.close();
            return;
          }
          if (partialResponse) {
            request.response.headers.contentType = ContentType.json;
            request.response.write('{"code":');
            await request.response.flush();
          }
          received.complete();
          await releaseResponse.future;
          try {
            if (!partialResponse) {
              request.response.statusCode = 503;
            }
            await request.response.close();
          } catch (_) {
            // The client has already cancelled this request.
          }
        });
        final api = MeshXApi(Uri.parse('http://127.0.0.1:${server.port}'));
        api.node = NodeInfo(api.origin, 'test', '', '/api/v1', '/ws/chat');
        api.session = const Session(1, 'Me', 'token');
        var cancelled = false;
        final upload = api.uploadAttachment(
          conversationId: attachmentConversation.id,
          name: 'cancelled.txt',
          mime: 'text/plain',
          bytes: [1, 2, 3, 4],
          cancelled: () => cancelled,
        );
        final outcome = expectLater(
          upload.timeout(const Duration(seconds: 2)),
          throwsA(isA<AttachmentCancelledException>()),
        );
        try {
          await received.future.timeout(const Duration(seconds: 1));
          cancelled = true;
          await outcome;
          // Retry on the same API while the first server handler is still held.
          final retry = await api
              .uploadAttachment(
                conversationId: attachmentConversation.id,
                name: 'retry.txt',
                mime: 'text/plain',
                bytes: [5, 6, 7, 8],
                cancelled: () => false,
              )
              .timeout(const Duration(seconds: 2));
          expect(retry.attachment.name, 'retry.txt');
          expect(
            retry.attachment.fileHash,
            sha256.convert([5, 6, 7, 8]).toString(),
          );
          expect(requests, 2);
        } finally {
          releaseResponse.complete();
          api.close();
          await server.close(force: true);
        }
      },
    );
  }

  test('node upload/download preserve auth, bounds and SHA-256', () async {
    final bytes = utf8.encode('meshx-attachment');
    final digest = sha256.convert(bytes).toString();
    final stored = '0123456789abcdef0123456789abcdef.txt';
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final requests = <String>[];
    server.listen((request) async {
      requests.add(
        '${request.method} ${request.uri.path} ${request.headers.value(HttpHeaders.authorizationHeader)}',
      );
      if (request.method == 'POST') {
        final body = await request.fold<List<int>>(
          <int>[],
          (all, chunk) => all..addAll(chunk),
        );
        expect(
          utf8.decode(body, allowMalformed: true),
          contains('name="conversationId"'),
        );
        expect(
          utf8.decode(body, allowMalformed: true),
          contains('private:1:2'),
        );
        request.response.headers.contentType = ContentType.json;
        request.response.write(
          jsonEncode({
            'code': 200,
            'data': {
              'url': '/api/v1/file/content/$stored',
              'originalName': 'note.txt',
              'fileName': stored,
              'fileSize': bytes.length,
              'fileType': 'text/plain',
              'fileHash': digest,
            },
          }),
        );
      } else {
        request.response.headers.contentType = ContentType.text;
        request.response.contentLength = bytes.length;
        request.response.add(bytes);
      }
      await request.response.close();
    });
    final api = MeshXApi(Uri.parse('http://127.0.0.1:${server.port}'));
    api.node = NodeInfo(api.origin, 'test', '', '/api/v1', '/ws/chat');
    api.session = const Session(1, 'Me', 'token');
    try {
      final result = await api.uploadAttachment(
        conversationId: attachmentConversation.id,
        name: 'note.txt',
        mime: 'text/plain',
        bytes: bytes,
      );
      final downloaded = await api.downloadAttachment(result.attachment);
      expect(downloaded.bytes, bytes);
      expect(requests, [
        'POST /api/v1/file/upload Bearer token',
        'GET /api/v1/file/content/$stored Bearer token',
      ]);
      await expectLater(
        api.downloadAttachment(
          AttachmentData(
            url: result.attachment.url,
            name: result.attachment.name,
            size: result.attachment.size,
            mime: result.attachment.mime,
            fileHash:
                '0000000000000000000000000000000000000000000000000000000000000000',
          ),
        ),
        throwsA(isA<ApiException>()),
      );
    } finally {
      api.close();
      await server.close(force: true);
    }
  });

  test(
    'cancel and unreadable selection create no attachment message',
    () async {
      final api = AttachmentApi(), wire = AttachmentWire(api);
      final chat = ChatController(connectionFactory: (_) => wire)..api = api;
      await chat.reconnect();
      chat.active = attachmentConversation;
      final system = FakeSystem();
      final platform = attachmentPlatform(chat, system)..start();
      final controller = AttachmentController(chat: chat, platform: platform);
      try {
        system.pickGate = Completer<CapabilityResult<SelectedFile>>()
          ..complete(const CapabilityResult(CapabilityStatus.cancelled));
        expect(await controller.pickAndSend(), isFalse);
        expect(controller.error, isNull);
        expect(api.uploads, 0);
        system.pickGate = Completer<CapabilityResult<SelectedFile>>()
          ..complete(
            const CapabilityResult(
              CapabilityStatus.success,
              value: SelectedFile(
                handle: '00000000-0000-0000-0000-000000000001',
                name: 'missing.txt',
                mime: 'text/plain',
                size: 3,
              ),
            ),
          );
        system.readStatus = CapabilityStatus.failed;
        expect(await controller.pickAndSend(), isFalse);
        expect(api.uploads, 1);
        expect(chat.messages, isEmpty);
      } finally {
        controller.dispose();
        platform.dispose();
        chat.dispose();
        await system.dispose();
      }
    },
  );

  test(
    'upload completes before attachment enters ACK outbox and retry preserves id',
    () async {
      final api = AttachmentApi(), wire = AttachmentWire(api);
      final chat = ChatController(connectionFactory: (_) => wire)..api = api;
      await chat.reconnect();
      chat.active = attachmentConversation;
      final system = FakeSystem()
        ..selectedBytes = const [1, 2, 3]
        ..pickGate = (Completer<CapabilityResult<SelectedFile>>()
          ..complete(
            const CapabilityResult(
              CapabilityStatus.success,
              value: SelectedFile(
                handle: '00000000-0000-0000-0000-000000000001',
                name: 'meshx.png',
                mime: 'image/png',
                size: 3,
              ),
            ),
          ));
      final platform = attachmentPlatform(chat, system)..start();
      final controller = AttachmentController(chat: chat, platform: platform);
      try {
        expect(await controller.pickAndSend(), isTrue);
        expect(api.uploads, 1);
        expect(wire.frames.single['payload']['contentType'], 'image');
        final pending = chat.messages.single;
        expect(pending.delivery, Delivery.sending);
        expect(chat.retryMessage(pending), isTrue);
        expect(wire.frames, hasLength(2));
        expect(wire.frames[1], wire.frames[0]);
        expect(chat.messages, hasLength(1));
      } finally {
        controller.dispose();
        platform.dispose();
        chat.dispose();
        await system.dispose();
      }
    },
  );

  test(
    'cancelling an in-flight upload releases the handle and sends nothing',
    () async {
      final api = AttachmentApi()..uploadGate = Completer<FileUploadResult>();
      final wire = AttachmentWire(api);
      final chat = ChatController(connectionFactory: (_) => wire)..api = api;
      await chat.reconnect();
      chat.active = attachmentConversation;
      final system = FakeSystem()
        ..selectedBytes = const [1, 2, 3]
        ..pickGate = (Completer<CapabilityResult<SelectedFile>>()
          ..complete(
            const CapabilityResult(
              CapabilityStatus.success,
              value: SelectedFile(
                handle: '00000000-0000-0000-0000-000000000001',
                name: 'meshx.png',
                mime: 'image/png',
                size: 3,
              ),
            ),
          ));
      final platform = attachmentPlatform(chat, system)..start();
      final controller = AttachmentController(chat: chat, platform: platform);
      try {
        final sending = controller.pickAndSend();
        await eventually(() => api.uploads == 1);
        controller.cancel();
        api.uploadGate!.complete(
          const FileUploadResult(attachment: attachment),
        );
        expect(await sending, isFalse);
        await platform.drain();
        expect(system.releaseCount, greaterThan(0));
        expect(wire.frames, isEmpty);
        expect(chat.messages, isEmpty);
        expect(controller.error, isNull);
      } finally {
        controller.dispose();
        platform.dispose();
        chat.dispose();
        await system.dispose();
      }
    },
  );

  test(
    'upload failure sends nothing and account change clears native cache',
    () async {
      final api = AttachmentApi()..uploadFailure = const ApiException('上传拒绝');
      final wire = AttachmentWire(api);
      final chat = ChatController(connectionFactory: (_) => wire)..api = api;
      await chat.reconnect();
      chat.active = attachmentConversation;
      final system = FakeSystem()
        ..selectedBytes = const [1, 2, 3]
        ..pickGate = (Completer<CapabilityResult<SelectedFile>>()
          ..complete(
            const CapabilityResult(
              CapabilityStatus.success,
              value: SelectedFile(
                handle: '00000000-0000-0000-0000-000000000001',
                name: 'meshx.png',
                mime: 'image/png',
                size: 3,
              ),
            ),
          ));
      final platform = attachmentPlatform(chat, system)..start();
      final controller = AttachmentController(chat: chat, platform: platform);
      try {
        expect(await controller.pickAndSend(), isFalse);
        expect(controller.error, '上传拒绝');
        expect(wire.frames, isEmpty);
        expect(chat.messages, isEmpty);
        final message = ChatMessage(
          messageId: 'm1',
          clientMsgId: 'c1',
          conversationId: attachmentConversation.id,
          fromUserId: 2,
          content: attachment.encode(),
          contentType: 'image',
          sequence: 1,
          createdAt: DateTime(2026),
        );
        expect(await controller.downloadAndShare(message), isTrue);
        expect(api.downloads, 1);
        expect(system.shareCount, 1);
        final before = system.releaseCount;
        await chat.logout(revokeRemote: false);
        await platform.drain();
        expect(system.releaseCount, greaterThan(before));
        expect(await controller.downloadAndShare(message), isFalse);
      } finally {
        controller.dispose();
        platform.dispose();
        chat.dispose();
        await system.dispose();
      }
    },
  );
}
