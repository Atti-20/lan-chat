import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/application/recovery_coordinator.dart';
import 'package:meshx_flutter_probe/application/recovery_chat_sink.dart';
import 'package:meshx_flutter_probe/core/recovery.dart';
import 'package:meshx_flutter_probe/core/recovery_data.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';
import 'package:meshx_flutter_probe/platform/storage.dart';

class FixtureTransport extends ApiRecoveryTransport {
  FixtureTransport(super.api);
  int snapshots = 0;
  @override
  Future<Object?> snapshot(String id, String? token, int limit) {
    snapshots++;
    return super.snapshot(id, token, limit);
  }

  @override
  Future<Object?> capabilities() async {
    final actual = await api.recoveryCapabilities();
    expect(actual['versions'], isEmpty);
    // Test-only client opt-in; server production capability stays closed.
    return {
      ...actual,
      'versions': [1],
    };
  }
}

void main() {
  test(
    'Spring HTTP/MySQL snapshot then concurrent recall commits terminal state',
    () async {
      final origin = Platform.environment['MESHX_RECOVERY_HTTP_ORIGIN'];
      expect(origin, matches(RegExp(r'^http://127\.0\.0\.1:\d+$')));
      final api = MeshXApi(Uri.parse(origin!));
      api.restoreCredentials({
        'origin': origin,
        'userId': 7,
        'nickname': 'fixture',
        'token': 'fixture',
        'refreshCookie': 'fixture',
        'apiPath': '/api/v1',
        'wsPath': '/ws/chat',
        'nodeName': 'fixture',
      });
      addTearDown(api.close);
      final sharedPath = Platform.environment['MESHX_RECOVERY_CLIENT_STORE'];
      final root = sharedPath == null
          ? await Directory.systemTemp.createTemp('meshx-live-recovery-')
          : Directory(sharedPath);
      if (sharedPath == null) {
        addTearDown(() => root.delete(recursive: true));
      }
      final store = FileChatStore(directory: root);
      RecoveredChatImage? published;
      final sink = RecoveryChatSink(
        store: store,
        seed: () => RecoveryChatData({}, [], {}),
        isCurrent: (_) => true,
        onPublish: (_, image) => published = image,
        onQuarantine: (_) {},
      );
      final transport = FixtureTransport(api);
      final runner = RecoveryCoordinator(transport, sink, (_) => true);
      if (Platform.environment['MESHX_RECOVERY_CLIENT_PHASE'] == 'restart') {
        // This invocation is a new OS process, with no old controller/core state.
        final prior = await store.loadRecovery('$origin|7');
        expect(prior, isNotNull);
        expect(prior!.revision, 2);
        expect(prior.snapshot['recovery']['cursor'], '2');
        expect(published, isNull);
        final restarted = await runner.rebuild(
          RecoveryOwner(origin, '7', 2),
          'flutter-live-process-restart',
        );
        expect(
          restarted?.phase,
          RecoveryPhase.onlineSafe,
          reason: runner.reason,
        );
        expect(transport.snapshots, greaterThan(1));
        final durable = await FileChatStore(
          directory: root,
        ).loadRecovery('$origin|7');
        expect(durable!.revision, 3);
        expect(durable.snapshot['recovery']['cursor'], '2');
        expect(durable.snapshot['messages']['group:21'], hasLength(204));
        expect(
          durable.snapshot['tombstones']['group:21-1']['state'],
          'UNAVAILABLE',
        );
        expect(
          jsonEncode(durable.snapshot),
          isNot(contains('sensitive-first-body')),
        );
        expect(published!.chat.messages['group:21'], hasLength(204));
        return;
      }
      final result = await runner.rebuild(
        RecoveryOwner(origin, '7', 1),
        'flutter-live-http',
      );
      expect(result?.phase, RecoveryPhase.onlineSafe, reason: runner.reason);
      final disk = (await FileChatStore(
        directory: root,
      ).loadRecovery('$origin|7'))!.snapshot;
      expect(disk['recovery']['cursor'], '1');
      expect(disk['messages']['group:21'], hasLength(204));
      expect(disk['tombstones']['group:21-1']['state'], 'RECALLED');
      expect(jsonEncode(disk), isNot(contains('sensitive-first-body')));
      expect(published!.chat.messages['group:21'], hasLength(204));
      final before = transport.snapshots;
      final resumed = await runner.rebuild(
        RecoveryOwner(origin, '7', 1),
        'flutter-live-resume',
        resumeFrom: result,
      );
      expect(resumed?.phase, RecoveryPhase.onlineSafe, reason: runner.reason);
      expect(transport.snapshots, before);
      final after = (await store.loadRecovery('$origin|7'))!.snapshot;
      expect(after['recovery']['cursor'], '2');
      expect(after['tombstones']['group:21-1']['state'], 'UNAVAILABLE');
    },
  );
}
