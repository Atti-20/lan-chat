import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/application/broadcasts_controller.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/data/broadcast_models.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';
import 'package:meshx_flutter_probe/data/models.dart';
import 'package:meshx_flutter_probe/application/platform_coordinator.dart';
import 'platform_fakes.dart';

Map<String, dynamic> summaryJson({
  String status = 'ACTIVE',
  bool image = false,
  bool location = false,
}) => {
  'id': 7,
  'senderId': 2,
  'title': '机房检查',
  'content': '请完成检查',
  'status': status,
  'priority': 'IMPORTANT',
  'confirmationRequired': true,
  'confirmationOptions': '["RECEIVED","EXECUTED"]',
  'requireImageProof': image,
  'requireLocationProof': location,
};

Map<String, dynamic> receiverJson({
  String target = 'ACTIVE',
  String confirmation = 'PENDING',
  bool viewed = false,
}) => {
  'id': 12,
  'broadcastId': 7,
  'userId': 1,
  'targetStatus': target,
  'confirmStatus': confirmation,
  'viewedAt': viewed ? '2026-09-13T10:00:00' : null,
};

Map<String, dynamic> detailJson({
  bool image = false,
  bool location = false,
  String target = 'ACTIVE',
  String confirmation = 'PENDING',
  bool viewed = false,
}) => {
  'broadcast': summaryJson(image: image, location: location),
  'receiver': receiverJson(
    target: target,
    confirmation: confirmation,
    viewed: viewed,
  ),
  'sender': {'id': 2, 'username': 'owner', 'nickname': '值班员'},
  'contentEvidence': {'imageUrls': <String>[]},
  'confirmationOptions': ['RECEIVED', 'EXECUTED'],
  'createdByCurrentUser': false,
};

class FakeBroadcastApi extends MeshXApi {
  FakeBroadcastApi() : super(Uri.parse('https://broadcast.invalid')) {
    node = NodeInfo(origin, 'test', '', '/api/v1', '/ws/chat');
    session = const Session(1, 'Me', 'synthetic-token');
  }
  BroadcastDetail current = BroadcastDetail.fromJson(detailJson());
  int detailReads = 0, views = 0, confirms = 0, completes = 0;
  Completer<BroadcastReceiverState>? confirmGate;
  Completer<BroadcastDetail>? detailGate;
  Object? detailFailure;
  bool hidePending = false;
  int uploads = 0;
  String? uploadedName;
  Object? uploadFailure;

  @override
  Future<BroadcastImageUpload> uploadBroadcastImage({
    required String name,
    required String mime,
    required List<int> bytes,
  }) async {
    uploads++;
    uploadedName = name;
    if (uploadFailure != null) throw uploadFailure!;
    return const BroadcastImageUpload(22);
  }

  @override
  Future<List<BroadcastSummary>> broadcasts({bool pending = false}) async => [
    if (!pending || !hidePending) current.broadcast,
  ];
  @override
  Future<BroadcastDetail> broadcastDetail(int broadcastId) async {
    detailReads++;
    if (detailFailure != null) throw detailFailure!;
    if (detailGate != null) return detailGate!.future;
    return current;
  }

  @override
  Future<BroadcastReceiverState> viewBroadcast(int broadcastId) async {
    views++;
    current = BroadcastDetail.fromJson(
      detailJson(
        viewed: true,
        image: current.broadcast.requireImageProof,
        location: current.broadcast.requireLocationProof,
        target: current.receiver!.targetStatus,
        confirmation: current.receiver!.confirmStatus,
      ),
    );
    return current.receiver!;
  }

  @override
  Future<BroadcastReceiverState> confirmBroadcast(
    int broadcastId,
    String status,
  ) async {
    confirms++;
    final pending = confirmGate;
    if (pending != null) return pending.future;
    current = BroadcastDetail.fromJson(
      detailJson(viewed: true, confirmation: status),
    );
    return current.receiver!;
  }

  @override
  Future<BroadcastReceiverState> completeBroadcast(
    int broadcastId, {
    List<int> imageFileIds = const [],
  }) async {
    completes++;
    current = BroadcastDetail.fromJson(
      detailJson(viewed: true, confirmation: 'EXECUTED'),
    );
    return current.receiver!;
  }
}

void main() {
  test(
    'deadline rechecks stop after five attempts without hiding server pending',
    () async {
      final api = FakeBroadcastApi()
        ..current = BroadcastDetail.fromJson({
          ...detailJson(viewed: true),
          'broadcast': {
            ...summaryJson(),
            'deadlineAt': DateTime.now()
                .subtract(const Duration(seconds: 1))
                .toIso8601String(),
          },
        });
      final chat = ChatController()..api = api;
      final controller = BroadcastsController(
        chat: chat,
        api: api,
        changes: const Stream.empty(),
      );
      addTearDown(() {
        controller.dispose();
        chat.dispose();
      });
      await controller.start();
      await controller.open(7);
      await Future<void>.delayed(const Duration(milliseconds: 6300));
      expect(api.detailReads, 6);
      expect(controller.pending, hasLength(1));
      expect(api.completes, 0);
    },
  );
  test(
    'local deadline ahead of server rechecks the authoritative pending list',
    () async {
      final api = FakeBroadcastApi()
        ..current = BroadcastDetail.fromJson({
          ...detailJson(viewed: true),
          'broadcast': {
            ...summaryJson(),
            'deadlineAt': DateTime.now()
                .subtract(const Duration(seconds: 1))
                .toIso8601String(),
          },
        });
      final chat = ChatController()..api = api;
      final controller = BroadcastsController(
        chat: chat,
        api: api,
        changes: const Stream.empty(),
      );
      addTearDown(() {
        controller.dispose();
        chat.dispose();
      });
      await controller.start();
      await controller.open(7);
      expect(controller.pending, hasLength(1));
      api.hidePending = true;
      final converged = Completer<void>();
      controller.addListener(() {
        if (controller.pending.isEmpty && !converged.isCompleted) {
          converged.complete();
        }
      });
      await converged.future.timeout(const Duration(seconds: 3));
      expect(api.completes, 0);
    },
  );
  test(
    'new detail cancels the old deadline and disposal stops its timer',
    () async {
      final api = FakeBroadcastApi()
        ..current = BroadcastDetail.fromJson({
          ...detailJson(viewed: true),
          'broadcast': {
            ...summaryJson(),
            'deadlineAt': DateTime.now()
                .add(const Duration(milliseconds: 150))
                .toIso8601String(),
          },
        });
      final chat = ChatController()..api = api;
      final controller = BroadcastsController(
        chat: chat,
        api: api,
        changes: const Stream.empty(),
      );
      await controller.open(7);
      api.current = BroadcastDetail.fromJson(detailJson(viewed: true));
      await controller.open(7);
      final reads = api.detailReads;
      await Future<void>.delayed(const Duration(milliseconds: 200));
      expect(api.detailReads, reads);
      expect(controller.detail!.canSubmit, isTrue);
      api.current = BroadcastDetail.fromJson({
        ...detailJson(viewed: true),
        'broadcast': {
          ...summaryJson(),
          'deadlineAt': DateTime.now()
              .add(const Duration(milliseconds: 100))
              .toIso8601String(),
        },
      });
      await controller.open(7);
      final beforeDispose = api.detailReads;
      controller.dispose();
      await Future<void>.delayed(const Duration(milliseconds: 150));
      expect(api.detailReads, beforeDispose);
      chat.dispose();
    },
  );
  test('open detail notifies at deadline without a realtime event', () async {
    final api = FakeBroadcastApi()
      ..current = BroadcastDetail.fromJson({
        ...detailJson(viewed: true),
        'broadcast': {
          ...summaryJson(),
          'deadlineAt': DateTime.now()
              .add(const Duration(milliseconds: 150))
              .toIso8601String(),
        },
      });
    final chat = ChatController()..api = api;
    final controller = BroadcastsController(
      chat: chat,
      api: api,
      changes: const Stream.empty(),
    );
    addTearDown(() {
      controller.dispose();
      chat.dispose();
    });
    await controller.open(7);
    expect(controller.detail!.canSubmit, isTrue);
    final expiredNotification = Completer<void>();
    controller.addListener(() {
      if (controller.detail?.broadcast.expired == true &&
          !expiredNotification.isCompleted) {
        expiredNotification.complete();
      }
    });
    await expiredNotification.future.timeout(const Duration(seconds: 2));
    expect(await controller.complete(), isFalse);
    expect(api.completes, 0);
  });
  test(
    'update during initial open is revalidated after the open finishes',
    () async {
      final api = FakeBroadcastApi()
        ..current = BroadcastDetail.fromJson(detailJson(viewed: true));
      final chat = ChatController()..api = api;
      final controller = BroadcastsController(
        chat: chat,
        api: api,
        changes: const Stream.empty(),
      );
      final stale = api.current;
      final gate = Completer<BroadcastDetail>();
      api.detailGate = gate;
      final opening = controller.open(7);
      await controller.refresh();
      api.detailGate = null;
      api.current = BroadcastDetail.fromJson(
        detailJson(target: 'REMOVED', viewed: true),
      );
      gate.complete(stale);
      await opening;
      await Future<void>.delayed(Duration.zero);
      expect(controller.detail!.canSubmit, isFalse);
      controller.dispose();
      chat.dispose();
    },
  );
  test('failed detail revalidation removes stale submit authority', () async {
    final api = FakeBroadcastApi();
    final chat = ChatController()..api = api;
    final controller = BroadcastsController(
      chat: chat,
      api: api,
      changes: const Stream.empty(),
    );
    await controller.open(7);
    api.detailFailure = const FormatException('invalid authoritative detail');
    await controller.refresh();
    expect(controller.detail, isNull);
    expect(controller.error, isNotNull);
    expect(await controller.complete(), isFalse);
    expect(api.completes, 0);
    controller.dispose();
    chat.dispose();
  });
  test(
    'late detail refresh cannot overwrite a newer open or notify after dispose',
    () async {
      final api = FakeBroadcastApi();
      final chat = ChatController()..api = api;
      final controller = BroadcastsController(
        chat: chat,
        api: api,
        changes: const Stream.empty(),
      );
      await controller.open(7);
      final old = controller.detail!;
      final gate = Completer<BroadcastDetail>();
      api.detailGate = gate;
      final refresh = controller.refresh();
      api.detailGate = null;
      api.current = BroadcastDetail.fromJson(
        detailJson(target: 'REMOVED', viewed: true),
      );
      await controller.open(7);
      gate.complete(old);
      await refresh;
      expect(controller.detail!.canSubmit, isFalse);
      final disposeGate = Completer<BroadcastDetail>();
      api.detailGate = disposeGate;
      final afterDispose = controller.refresh();
      controller.dispose();
      disposeGate.complete(old);
      await afterDispose;
      chat.dispose();
    },
  );

  test('detail opened by deep link subscribes without list start', () async {
    final api = FakeBroadcastApi();
    final chat = ChatController()..api = api;
    final changes = StreamController<void>();
    final controller = BroadcastsController(
      chat: chat,
      api: api,
      changes: changes.stream,
    );
    await controller.open(7);
    api.current = BroadcastDetail.fromJson({
      ...detailJson(viewed: true),
      'broadcast': summaryJson(status: 'CANCELLED'),
    });
    changes.add(null);
    await Future<void>.delayed(Duration.zero);
    expect(controller.detail!.broadcast.status, 'CANCELLED');
    expect(await controller.complete(), isFalse);
    controller.dispose();
    chat.dispose();
    await changes.close();
  });
  test(
    'realtime refresh removes permission from an already open detail',
    () async {
      final api = FakeBroadcastApi();
      final chat = ChatController()..api = api;
      final changes = StreamController<void>();
      final controller = BroadcastsController(
        chat: chat,
        api: api,
        changes: changes.stream,
      );
      addTearDown(() async {
        controller.dispose();
        chat.dispose();
        await changes.close();
      });
      await controller.start();
      await controller.open(7);
      expect(controller.detail!.canSubmit, isTrue);
      api.current = BroadcastDetail.fromJson(
        detailJson(target: 'REMOVED', viewed: true),
      );
      changes.add(null);
      await Future<void>.delayed(Duration.zero);
      expect(controller.detail!.canSubmit, isFalse);
      expect(await controller.complete(), isFalse);
      expect(api.completes, 0);
    },
  );
  test(
    'image completion locks all actions until picker cancellation',
    () async {
      final api = FakeBroadcastApi()
        ..current = BroadcastDetail.fromJson(detailJson(image: true));
      final chat = ChatController()..api = api;
      final system = FakeSystem()
        ..pickGate = Completer<CapabilityResult<SelectedFile>>();
      final platform = PlatformCoordinator(
        chat: chat,
        lifecycle: FakeLifecycle(),
        notifications: system,
        files: system,
        sharePort: system,
        network: system,
        settings: system,
      )..start();
      await platform.drain();
      final controller = BroadcastsController(
        chat: chat,
        api: api,
        platform: platform,
        changes: const Stream.empty(),
      )..detail = api.current;
      final first = controller.complete();
      await Future<void>.delayed(Duration.zero);
      expect(controller.busy, isTrue);
      expect(await controller.complete(), isFalse);
      expect(await controller.confirm('RECEIVED'), isFalse);
      expect(await controller.open(8), isFalse);
      expect(system.pickCount, 1);
      api.current = BroadcastDetail.fromJson(
        detailJson(image: true, target: 'REMOVED', viewed: true),
      );
      await controller.refresh();
      system.pickGate!.complete(
        const CapabilityResult(CapabilityStatus.cancelled),
      );
      expect(await first, isFalse);
      await Future<void>.delayed(Duration.zero);
      expect(controller.busy, isFalse);
      expect(controller.detail!.canSubmit, isFalse);
      expect(controller.error, isNull);
      expect(api.completes, 0);
      expect(api.confirms, 0);
      controller.dispose();
      platform.dispose();
      chat.dispose();
      await system.dispose();
    },
  );
  test(
    'cancelled image selection never uploads a previous selection',
    () async {
      final api = FakeBroadcastApi()
        ..current = BroadcastDetail.fromJson(detailJson(image: true));
      final chat = ChatController()..api = api;
      final system = FakeSystem()..selectedBytes = [1, 2, 3];
      final platform = PlatformCoordinator(
        chat: chat,
        lifecycle: FakeLifecycle(),
        notifications: system,
        files: system,
        sharePort: system,
        network: system,
        settings: system,
      )..start();
      await platform.drain();
      platform.selectedFile = const SelectedFile(
        handle: 'previous-selection',
        name: 'previous.png',
        mime: 'image/png',
        size: 3,
      );
      final controller = BroadcastsController(
        chat: chat,
        api: api,
        platform: platform,
        changes: const Stream.empty(),
      )..detail = api.current;
      expect(await controller.complete(), isFalse);
      expect(api.uploads, 0);
      expect(api.completes, 0);
      expect(controller.error, isNull);
      expect(controller.busy, isFalse);
      // A concurrent platform operation must not authorize the old selection.
      platform.busy = true;
      platform.fileStatus = const CapabilityResult(CapabilityStatus.success);
      expect(await controller.complete(), isFalse);
      expect(system.pickCount, 1);
      expect(api.uploads, 0);
      platform.busy = false;
      system.pickGate = Completer<CapabilityResult<SelectedFile>>()
        ..complete(
          const CapabilityResult(
            CapabilityStatus.success,
            value: SelectedFile(
              handle: 'new-selection',
              name: 'new.png',
              mime: 'image/png',
              size: 3,
            ),
          ),
        );
      expect(await controller.complete(), isTrue);
      expect(api.uploads, 1);
      expect(api.uploadedName, 'new.png');
      expect(api.completes, 1);
      expect(platform.selectedFile, isNull);
      controller.dispose();
      platform.dispose();
      chat.dispose();
      await system.dispose();
    },
  );
  for (final failure in [
    'wrong-type',
    'read-denied',
    'oversize',
    'upload',
    'fileMissing',
    'fileUnreadable',
    'invalidFileContent',
  ]) {
    test(
      'image $failure leaves completion untouched and permits retry',
      () async {
        final api = FakeBroadcastApi()
          ..current = BroadcastDetail.fromJson(detailJson(image: true));
        final chat = ChatController()..api = api;
        final system = FakeSystem()..selectedBytes = [1, 2, 3];
        final platform = PlatformCoordinator(
          chat: chat,
          lifecycle: FakeLifecycle(),
          notifications: system,
          files: system,
          sharePort: system,
          network: system,
          settings: system,
        )..start();
        await platform.drain();
        final controller = BroadcastsController(
          chat: chat,
          api: api,
          platform: platform,
          changes: const Stream.empty(),
        )..detail = api.current;
        void select(String mime) {
          system.pickGate = Completer<CapabilityResult<SelectedFile>>()
            ..complete(
              CapabilityResult(
                CapabilityStatus.success,
                value: SelectedFile(
                  handle: 'test-selection',
                  name: 'proof.png',
                  mime: mime,
                  size: 3,
                ),
              ),
            );
        }

        select(failure == 'wrong-type' ? 'application/pdf' : 'image/png');
        if (failure == 'read-denied') {
          system.readStatus = CapabilityStatus.permissionDenied;
        }
        if ([
          'fileMissing',
          'fileUnreadable',
          'invalidFileContent',
        ].contains(failure)) {
          system.readStatus = CapabilityStatus.failed;
          system.readReason = failure;
        }
        if (failure == 'oversize') {
          system.selectedBytes = List.filled(5 * 1024 * 1024 + 1, 0);
        }
        if (failure == 'upload') {
          api.uploadFailure = const ApiException(
            'test upload rejected',
            code: 503,
          );
        }
        final releases = system.releaseCount;
        expect(await controller.complete(), isFalse);
        expect(api.uploads, failure == 'upload' ? 1 : 0);
        expect(api.completes, 0);
        expect(api.confirms, 0);
        expect(controller.detail!.receiver!.confirmStatus, 'PENDING');
        expect(controller.error, isNotEmpty);
        if (failure == 'oversize') {
          expect(controller.error, '广播凭证图片不能超过 5MB，请重新选择');
        }
        if (failure == 'fileMissing') {
          expect(controller.error, '图片已不可用，请重新选择');
        }
        if (failure == 'fileUnreadable' || failure == 'invalidFileContent') {
          expect(controller.error, '无法读取这张图片，请重新选择');
        }
        expect(controller.notice, isNull);
        expect(controller.busy, isFalse);
        expect(platform.selectedFile, isNull);
        expect(system.releaseCount, greaterThan(releases));
        system.readStatus = CapabilityStatus.success;
        system.selectedBytes = [1, 2, 3];
        api.uploadFailure = null;
        select('image/png');
        expect(await controller.complete(), isTrue);
        expect(api.completes, 1);
        expect(system.pickCount, 2);
        expect(controller.error, isNull);
        expect(platform.selectedFile, isNull);
        controller.dispose();
        platform.dispose();
        chat.dispose();
        await system.dispose();
      },
    );
  }
  test('broadcast wire models reject invalid lifecycle and target states', () {
    expect(
      () => BroadcastSummary.fromJson(summaryJson(status: 'DELETED')),
      throwsFormatException,
    );
    expect(
      () => BroadcastDetail.fromJson({
        ...detailJson(),
        'receiver': receiverJson(target: 'UNKNOWN'),
      }),
      throwsFormatException,
    );
  });

  test('broadcast APIs use receiver-only v1 paths and bodies', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final seen = <Map<String, dynamic>>[];
    server.listen((request) async {
      final bodyBytes = await request.fold<List<int>>(
        <int>[],
        (bytes, chunk) => bytes..addAll(chunk),
      );
      final isJson =
          request.headers.contentType?.mimeType == 'application/json';
      final body = isJson ? utf8.decode(bodyBytes) : latin1.decode(bodyBytes);
      final jsonBody = isJson ? (body.isEmpty ? null : jsonDecode(body)) : null;
      seen.add({
        'method': request.method,
        'path': request.uri.path,
        'auth': request.headers.value(HttpHeaders.authorizationHeader),
        'body': jsonBody,
        'rawBody': body,
      });
      Object data;
      if (request.uri.path.endsWith('/broadcast-image')) {
        data = {'id': 22};
      } else if (request.uri.path.endsWith('/pending') ||
          request.uri.path == '/api/v1/broadcast') {
        data = [summaryJson()];
      } else if (request.method == 'GET') {
        data = detailJson(viewed: true);
      } else {
        data = receiverJson(viewed: true);
      }
      request.response.write(jsonEncode({'code': 200, 'data': data}));
      await request.response.close();
    });
    final api = MeshXApi(Uri.parse('http://127.0.0.1:${server.port}'));
    api.node = NodeInfo(api.origin, 'test', '', '/api/v1', '/ws/chat');
    api.session = const Session(1, 'Me', 'synthetic-token');
    try {
      await api.broadcasts();
      await api.broadcasts(pending: true);
      await api.broadcastDetail(7);
      await api.viewBroadcast(7);
      await api.confirmBroadcast(7, 'RECEIVED');
      await api.completeBroadcast(7, imageFileIds: [22]);
      expect(
        (await api.uploadBroadcastImage(
          name: 'proof.png',
          mime: 'image/png',
          bytes: [137, 80, 78, 71],
        )).id,
        22,
      );
      expect(seen.map((item) => '${item['method']} ${item['path']}'), [
        'GET /api/v1/broadcast',
        'GET /api/v1/broadcast/pending',
        'GET /api/v1/broadcast/7',
        'POST /api/v1/broadcast/7/view',
        'POST /api/v1/broadcast/7/confirm',
        'POST /api/v1/broadcast/7/complete',
        'POST /api/v1/file/broadcast-image',
      ]);
      expect(seen[4]['body'], {'status': 'RECEIVED'});
      expect(seen[5]['body'], {
        'imageFileIds': [22],
      });
      expect(seen[6]['rawBody'], contains('filename="proof.png"'));
      expect(
        seen.every((item) => item['auth'] == 'Bearer synthetic-token'),
        isTrue,
      );
    } finally {
      api.close();
      await server.close(force: true);
    }
  });

  test(
    'opening always fetches, marks view once, and fetches authoritative state',
    () async {
      final api = FakeBroadcastApi(), chat = ChatController()..api = api;
      final controller = BroadcastsController(
        chat: chat,
        api: api,
        changes: const Stream.empty(),
      );
      addTearDown(() {
        controller.dispose();
        chat.dispose();
      });
      expect(await controller.open(7), isTrue);
      expect(api.views, 1);
      expect(api.detailReads, 2);
      expect(controller.detail!.receiver!.viewedAt, isNotNull);
    },
  );

  test(
    'duplicate confirmation is blocked while first request is in flight',
    () async {
      final api = FakeBroadcastApi(), chat = ChatController()..api = api;
      var changes = 0;
      final subscription = chat.broadcastChanges.listen((_) => changes++);
      addTearDown(subscription.cancel);
      final controller = BroadcastsController(
        chat: chat,
        api: api,
        changes: const Stream.empty(),
      );
      addTearDown(() {
        controller.dispose();
        chat.dispose();
      });
      await controller.open(7);
      final gate = Completer<BroadcastReceiverState>();
      api.confirmGate = gate;
      final first = controller.confirm('RECEIVED');
      await Future<void>.delayed(Duration.zero);
      expect(await controller.confirm('RECEIVED'), isFalse);
      expect(api.confirms, 1);
      expect(changes, 0);
      api.confirmGate = null;
      api.current = BroadcastDetail.fromJson(
        detailJson(viewed: true, confirmation: 'RECEIVED'),
      );
      gate.complete(api.current.receiver!);
      expect(await first, isTrue);
      await Future<void>.delayed(Duration.zero);
      expect(changes, 1);
    },
  );

  test(
    'location proof is read-only and never calls confirm or complete',
    () async {
      final api = FakeBroadcastApi()
        ..current = BroadcastDetail.fromJson(detailJson(location: true));
      final chat = ChatController()..api = api;
      final controller = BroadcastsController(
        chat: chat,
        api: api,
        changes: const Stream.empty(),
      );
      addTearDown(() {
        controller.dispose();
        chat.dispose();
      });
      await controller.open(7);
      expect(await controller.confirm('EXECUTED'), isFalse);
      expect(await controller.complete(), isFalse);
      expect(api.confirms, 0);
      expect(api.completes, 0);
      expect(controller.error, contains('Web'));
    },
  );

  test('removed target cannot submit from a formerly visible card', () async {
    final api = FakeBroadcastApi()
      ..current = BroadcastDetail.fromJson(detailJson(target: 'REMOVED'));
    final chat = ChatController()..api = api;
    final controller = BroadcastsController(
      chat: chat,
      api: api,
      changes: const Stream.empty(),
    );
    addTearDown(() {
      controller.dispose();
      chat.dispose();
    });
    await controller.open(7);
    expect(await controller.confirm('RECEIVED'), isFalse);
    expect(api.confirms, 0);
  });
}
