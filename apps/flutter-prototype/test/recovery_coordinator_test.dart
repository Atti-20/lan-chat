import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/application/recovery_coordinator.dart';
import 'package:meshx_flutter_probe/core/recovery.dart';
import 'package:meshx_flutter_probe/core/recovery_snapshot.dart';

class TestSink implements RecoverySink {
  @override
  Future<void> resume(RecoveryContext context, RecoveryState state) async {
    events.add('resume:${state.cursor}');
  }

  TestSink(this.events);
  final List<String> events;
  Future<void> Function(RecoveryState)? onCommit;
  @override
  Future<void> begin(RecoveryContext context) async {
    events.add('begin');
  }

  @override
  Future<void> snapshot(
    RecoveryContext context,
    List<SnapshotItem> items,
  ) async {
    events.add('snapshot');
  }

  @override
  Future<void> mutations(RecoveryContext context, RecoveryPlan plan) async {
    events.add('apply:${plan.next.cursor}');
  }

  @override
  Future<void> commit(RecoveryContext context, RecoveryState state) async {
    if (onCommit != null) {
      await onCommit!(state);
    } else {
      events.add('commit:${state.cursor}');
    }
  }

  @override
  void publish(RecoveryContext context, RecoveryState state) {
    events.add('publish:${state.cursor}');
  }

  @override
  void quarantine(String reason) {
    events.add(reason);
  }
}

class TestTransport implements RecoveryTransport {
  TestTransport(this.fixture, this.events);
  final Map fixture;
  final List<String> events;
  int cuts = 0, readyCalls = 0, pageSize = 100;
  bool supported = true, resuming = false;
  Future<void> Function()? onRelease;
  Future<Object?> Function(String? token, int limit)? onSnapshot;
  String get epoch => fixture['context']['streamEpoch'];
  @override
  Future<Object?> capabilities() async => {
    'capability': 'meshx.mutation-recovery',
    'versions': supported ? [1] : [],
    'recordVersion': 1,
    'maxPageSize': pageSize,
  };
  @override
  Future<Object?> open(Map<String, dynamic> input, String key) async {
    resuming = input['mode'] == 'resume';
    if (resuming) {
      expect(input['cursor'], {'streamEpoch': epoch, 'position': '2'});
      return {
        'recoveryId': 'resumed',
        'mode': 'resume',
        'streamEpoch': epoch,
        'startCursor': '2',
        'floor': '0',
        'latest': '2',
      };
    }
    return {
      'recoveryId': 'session',
      'mode': 'rebuild',
      'streamEpoch': epoch,
      'startCursor': '0',
      'snapshotBoundary': '0',
      'floor': '0',
      'latest': '0',
      'snapshotId': 'snapshot',
    };
  }

  @override
  Future<Object?> snapshot(String id, String? token, int limit) async =>
      onSnapshot != null
      ? onSnapshot!(token, limit)
      : {
          'snapshotId': 'snapshot',
          'boundary': '0',
          'items': [fixture['directory'], fixture['message']],
          'nextPageToken': null,
          'snapshotComplete': true,
        };
  @override
  Future<Object?> cut(String id) async => {
    'through': resuming ? '2' : '${++cuts}',
    'floor': '0',
    'streamEpoch': epoch,
  };
  @override
  Future<Object?> mutations(
    String id,
    String after,
    String through,
    int limit,
  ) async => {
    'fromExclusive': after,
    'through': through,
    'nextCursor': through,
    'hasMore': false,
    'floor': '0',
    'latest': through,
    'streamEpoch': epoch,
    'records': [
      {
        'recordVersion': 1,
        'eventId': through == '1'
            ? '22222222-2222-4222-8222-222222222222'
            : '33333333-3333-4333-8333-333333333333',
        'streamEpoch': epoch,
        'cursor': through,
        'type': through == '1' ? 'MESSAGE_RECALLED' : 'MESSAGE_UNAVAILABLE',
        'conversationId': 'group:21',
        'messageId': 'message-a',
        'objectVersion': '${int.parse(through) + 1}',
        'committedAt': '2026-09-14T00:00:00.000Z',
      },
    ],
  };
  @override
  Future<Object?> ready(String id, String cursor, bool complete) async {
    readyCalls++;
    events.add('ready:$cursor');
    return {
      'ready': readyCalls > 1,
      'acceptedCursor': cursor,
      'latest': '2',
      'streamEpoch': epoch,
    };
  }

  @override
  Future<void> release(String id) async {
    if (onRelease != null) await onRelease!();
    events.add('release');
  }
}

void main() {
  final f =
      jsonDecode(
            File(
              '../../contracts/test-vectors/recovery-snapshot-pages.json',
            ).readAsStringSync(),
          )
          as Map;
  final owner = RecoveryOwner(
    f['context']['origin'],
    f['context']['userId'],
    1,
  );
  late List<String> events;
  late TestSink sink;
  late TestTransport transport;
  late RecoveryCoordinator runner;
  var current = true;
  setUp(() {
    events = [];
    sink = TestSink(events);
    transport = TestTransport(f, events);
    current = true;
    runner = RecoveryCoordinator(transport, sink, (_) => current);
  });
  test(
    'event-loop cancellation interrupts immediately completed snapshot pages before commit',
    () async {
      var pages = 0;
      transport.pageSize = 1;
      Timer? cancellation;
      transport.onSnapshot = (token, limit) async {
        final index = ++pages;
        cancellation ??= Timer(Duration.zero, runner.cancel);
        return {
          'snapshotId': 'snapshot',
          'boundary': '0',
          'items': [
            if (index == 1)
              {...f['directory'] as Map, 'messageSequenceAtH': '100'},
            if (index > 1)
              {
                ...f['message'] as Map,
                'messageId': 'm$index',
                'messageSequence': '${index - 1}',
              },
          ],
          'nextPageToken': index == 101 ? null : '$index',
          'snapshotComplete': index == 101,
        };
      };
      try {
        expect(await runner.rebuild(owner, 'cancel-paged'), isNull);
        expect(pages, inInclusiveRange(1, 4));
        expect(transport.readyCalls, 0);
        expect(
          events.any(
            (event) =>
                event.startsWith('commit:') || event.startsWith('publish:'),
          ),
          isFalse,
        );
      } finally {
        cancellation?.cancel();
      }
    },
  );
  test(
    'snapshot replay durable commit READY; newer latest needs another commit',
    () async {
      final result = await runner.rebuild(owner, 'request-key');
      expect(result!.phase, RecoveryPhase.onlineSafe);
      expect(runner.durableCursor, '2');
      expect(events, [
        'RECOVERING',
        'begin',
        'snapshot',
        'apply:1',
        'commit:1',
        'ready:1',
        'apply:2',
        'commit:2',
        'ready:2',
        'publish:2',
        'release',
      ]);
    },
  );
  test('pending or failed commit cannot call READY or publish', () async {
    final gate = Completer<void>(), entered = Completer<void>();
    sink.onCommit = (_) async {
      entered.complete();
      await gate.future;
      throw FileSystemException('disk full');
    };
    final running = runner.rebuild(owner, 'request-key');
    await entered.future;
    expect(transport.readyCalls, 0);
    expect(runner.durableCursor, isNull);
    gate.complete();
    expect((await running)!.phase, RecoveryPhase.storageBlocked);
    expect(transport.readyCalls, 0);
    expect(runner.durableCursor, isNull);
    expect(events.any((event) => event.startsWith('publish:')), isFalse);
  });
  test(
    'owner switch during commit cannot publish or release as new owner',
    () async {
      final gate = Completer<void>(), entered = Completer<void>();
      sink.onCommit = (_) async {
        entered.complete();
        await gate.future;
      };
      final running = runner.rebuild(owner, 'request-key');
      await entered.future;
      current = false;
      runner.cancel();
      gate.complete();
      expect(await running, isNull);
      expect(transport.readyCalls, 0);
      expect(events.contains('release'), isFalse);
    },
  );
  test('unsupported capability never opens staging', () async {
    transport.supported = false;
    expect(await runner.rebuild(owner, 'request-key'), isNull);
    expect(runner.reason, 'BLOCKED_UPGRADE');
    expect(events.contains('begin'), isFalse);
  });
  test('cancellation during cleanup cannot return stale ONLINE_SAFE', () async {
    final entered = Completer<void>(), gate = Completer<void>();
    transport.onRelease = () async {
      entered.complete();
      await gate.future;
    };
    final running = runner.rebuild(owner, 'request-key');
    await entered.future;
    current = false;
    runner.cancel();
    gate.complete();
    expect(await running, isNull);
    expect(runner.state!.phase, RecoveryPhase.quarantined);
  });
  test(
    'resume reuses committed data and does not reload snapshot pages',
    () async {
      final first = await runner.rebuild(owner, 'first');
      expect(first?.phase, RecoveryPhase.onlineSafe);
      final count = events.where((e) => e == 'snapshot').length;
      final second = await runner.rebuild(owner, 'resume', resumeFrom: first);
      expect(second?.phase, RecoveryPhase.onlineSafe);
      expect(events.where((e) => e == 'snapshot').length, count);
      expect(events, contains('resume:2'));
      expect(runner.durableCursor, '2');
    },
  );
}
