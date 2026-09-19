import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/application/recovery_coordinator.dart';
import 'package:meshx_flutter_probe/application/recovery_chat_sink.dart';
import 'package:meshx_flutter_probe/core/recovery.dart';
import 'package:meshx_flutter_probe/core/recovery_data.dart';
import 'package:meshx_flutter_probe/platform/storage.dart';

class CapacityTransport implements RecoveryTransport {
  static const count = 200000, conversations = 100, perConversation = 2000;
  static const epoch = '11111111-1111-4111-8111-111111111111';
  int pages = 0;
  Map<String, dynamic> item(int index) => index < conversations
      ? {
          'kind': 'CONVERSATION',
          'conversationId': 'group:${index + 1}',
          'accessVersion': '1',
          'readAllowed': true,
          'sendAllowed': true,
          'messageSequenceAtH': '$perConversation',
        }
      : {
          'kind': 'MESSAGE',
          'conversationId':
              'group:${(index - conversations) ~/ perConversation + 1}',
          'messageId': 'm${index - conversations}',
          'objectVersion': '1',
          'state': 'NORMAL',
          'messageSequence': '${(index - conversations) % perConversation + 1}',
          'content': 'capacity fixture',
          'details': {
            'fromUserId': 8,
            'contentType': 'text',
            'createTime': '2026-09-20T00:00:00',
            'isBurn': 0,
          },
        };
  @override
  Future<Object?> capabilities() async => {
    'capability': 'meshx.mutation-recovery',
    'versions': [1],
    'recordVersion': 1,
    'maxPageSize': 200,
  };
  @override
  Future<Object?> open(Map<String, dynamic> input, String key) async => {
    'recoveryId': 'capacity',
    'mode': 'rebuild',
    'streamEpoch': epoch,
    'startCursor': '0',
    'snapshotBoundary': '0',
    'floor': '0',
    'latest': '0',
    'snapshotId': 'capacity',
  };
  @override
  Future<Object?> snapshot(String id, String? token, int limit) async {
    final offset = int.parse(token ?? '0'),
        end = min(int.parse(token ?? '0') + limit, count + conversations);
    pages++;
    if (pages % 100 == 0) stderr.writeln('capacity pages=$pages');
    return {
      'snapshotId': 'capacity',
      'boundary': '0',
      'items': List.generate(end - offset, (i) => item(offset + i)),
      'nextPageToken': end == count + conversations ? null : '$end',
      'snapshotComplete': end == count + conversations,
    };
  }

  @override
  Future<Object?> cut(String id) async => {
    'through': '0',
    'floor': '0',
    'streamEpoch': epoch,
  };
  @override
  Future<Object?> mutations(
    String id,
    String after,
    String through,
    int limit,
  ) async => throw StateError('unexpected mutation page');
  @override
  Future<Object?> ready(String id, String cursor, bool complete) async => {
    'ready': true,
    'acceptedCursor': '0',
    'latest': '0',
    'streamEpoch': epoch,
  };
  @override
  Future<void> release(String id) async {}
}

Future<Map<String, Object>> runCapacityProbe({
  bool repeatRecovery = false,
}) async {
  final directory = await Directory.systemTemp.createTemp('meshx-capacity-');
  try {
    final store = FileChatStore(directory: directory),
        transport = CapacityTransport();
    var published = 0;
    final sink = RecoveryChatSink(
      store: store,
      seed: () => RecoveryChatData({}, [], {}),
      isCurrent: (_) => true,
      onPublish: (_, image) {
        published = image.chat.messages.values.fold(
          0,
          (sum, rows) => sum + rows.length,
        );
      },
      onQuarantine: (_) {},
    );
    final runner = RecoveryCoordinator(transport, sink, (_) => true),
        timer = Stopwatch()..start();
    final state = await runner.rebuild(
      const RecoveryOwner('http://capacity.invalid', '7', 1),
      'capacity',
    );
    expect(state?.phase, RecoveryPhase.onlineSafe, reason: runner.reason);
    expect(published, CapacityTransport.count);
    final firstCommitMs = timer.elapsedMilliseconds;
    if (repeatRecovery) {
      final repeated = await runner.rebuild(
        const RecoveryOwner('http://capacity.invalid', '7', 1),
        'capacity-replacement',
      );
      expect(repeated?.phase, RecoveryPhase.onlineSafe, reason: runner.reason);
      expect(published, CapacityTransport.count);
    }
    final allCommitsMs = timer.elapsedMilliseconds;
    final disk = await FileChatStore(
      directory: directory,
    ).loadRecovery('http://capacity.invalid|7');
    expect(
      (disk!.snapshot['messages'] as Map).values.fold<int>(
        0,
        (sum, rows) => sum + (rows as List).length,
      ),
      CapacityTransport.count,
    );
    expect(
      (disk.snapshot['recovery']['messages'] as Map).length,
      CapacityTransport.count,
    );
    expect((disk.snapshot['conversations'] as List).length, 100);
    expect(disk.revision, repeatRecovery ? 2 : 1);
    return <String, Object>{
      'status': 'PASS',
      'messages': published,
      'conversations': 100,
      'pages': transport.pages,
      'recoveries': repeatRecovery ? 2 : 1,
      'firstCommitMs': firstCommitMs,
      'replacementCommitMs': allCommitsMs - firstCommitMs,
      'reloadMs': timer.elapsedMilliseconds - allCommitsMs,
      'elapsedMs': timer.elapsedMilliseconds,
      'rssBytes': ProcessInfo.currentRss,
      'peakRssBytes': ProcessInfo.maxRss,
      'transport': 'INJECTED',
      'storage': 'REAL_FILE_COMMIT_AND_RELOAD',
    };
  } finally {
    await directory.delete(recursive: true);
  }
}

void main() {
  test(
    '200k retained messages and 100 conversations survive real file commit and restart',
    () async {
      stdout.writeln(jsonEncode(await runCapacityProbe()));
    },
    timeout: const Timeout(Duration(minutes: 10)),
  );
}
