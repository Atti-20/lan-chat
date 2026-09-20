import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/core/recovery.dart';
import 'package:meshx_flutter_probe/core/recovery_snapshot.dart';

void main() {
  final f =
      jsonDecode(
            File(
              '../../contracts/test-vectors/recovery-snapshot-pages.json',
            ).readAsStringSync(),
          )
          as Map;
  final c = f['context'];
  final context = RecoveryContext(
    origin: c['origin'],
    userId: c['userId'],
    streamEpoch: c['streamEpoch'],
    generation: c['generation'],
  );
  final directory = Map<String, Object?>.from(f['directory']),
      message = Map<String, Object?>.from(f['message']);
  RecoverySnapshotStage begin() =>
      beginFullRecoverySnapshot(context, 'snapshot-a', '0');
  Map<String, Object?> page(List<Object?> items, [String? next]) => {
    'snapshotId': 'snapshot-a',
    'boundary': '0',
    'items': items,
    'nextPageToken': next,
    'snapshotComplete': next == null,
  };
  final rejects = throwsA(isA<RecoveryProtocolError>());
  test(
    'full snapshot binds pages, completes once and ignores old callbacks',
    () {
      final start = begin(),
          first = stageRecoverySnapshotPage(
            begin(),
            context,
            null,
            page([directory], 'next'),
            limit: 1,
          );
      expect(first.stage.complete, isFalse);
      expect(start.state.access, isEmpty);
      final last = stageRecoverySnapshotPage(
        first.stage,
        context,
        'next',
        page([message]),
        limit: 1,
      );
      expect(last.stage.complete, isTrue);
      expect(last.stage.state.messages.length, 1);
      expect(first.stage.state.messages, isEmpty);
      expect(first.stage.sequences, isEmpty);
      expect(() => last.stage.sequences.clear(), throwsUnsupportedError);
      expect(() => last.stage.positions.clear(), throwsUnsupportedError);
      expect(() => last.stage.consumedTokens.clear(), throwsUnsupportedError);
      expect((last.items.single['details'] as Map)['contentType'], 'image');
      expect(
        stageRecoverySnapshotPage(
          last.stage,
          context,
          null,
          page([directory], 'next'),
          limit: 1,
        ).ignored,
        isTrue,
      );
      expect(
        () =>
            stageRecoverySnapshotPage(last.stage, context, 'unknown', page([])),
        rejects,
      );
    },
  );
  test('invalid later item discards page; wrong boundary and cycle fail', () {
    final first = stageRecoverySnapshotPage(
      begin(),
      context,
      null,
      page([directory], 'next'),
      limit: 1,
    ).stage;
    expect(
      () => stageRecoverySnapshotPage(
        first,
        context,
        'next',
        page([
          message,
          {...message, 'messageId': 'b', 'messageSequence': '3'},
        ]),
      ),
      rejects,
    );
    expect(first.state.messages, isEmpty);
    expect(
      () => stageRecoverySnapshotPage(first, context, 'next', page([])),
      rejects,
    );
    expect(
      () => stageRecoverySnapshotPage(first, context, 'next', {
        ...page([message]),
        'boundary': '1',
      }),
      rejects,
    );
    expect(
      () => stageRecoverySnapshotPage(
        first,
        context,
        'next',
        page([message], 'next'),
        limit: 1,
      ),
      rejects,
    );
  });
  test('revocation removes staged normal bodies from returned items', () {
    final result = stageRecoverySnapshotPage(
      begin(),
      context,
      null,
      page([directory, message, f['revoked']]),
    );
    expect(result.stage.state.access['group:21']!.readAllowed, isFalse);
    expect(result.items.any((item) => item['kind'] == 'MESSAGE'), isFalse);
  });
  test('terminal cannot regress and same ID cannot move sequence', () {
    final terminal = {
      ...message,
      'state': 'RECALLED',
      'objectVersion': '2',
      'content': null,
      'details': null,
    };
    final first = stageRecoverySnapshotPage(
      begin(),
      context,
      null,
      page([directory, terminal], 'next'),
      limit: 2,
    ).stage;
    final last = stageRecoverySnapshotPage(
      first,
      context,
      'next',
      page([message]),
    );
    expect(
      last.stage.state.messages['message-a']!.state,
      MessageRecoveryState.recalled,
    );
    expect(last.items, isEmpty);
    expect(
      () => stageRecoverySnapshotPage(
        first,
        context,
        'next',
        page([
          {...terminal, 'state': 'BURNED'},
        ]),
      ),
      rejects,
    );
    expect(
      () => stageRecoverySnapshotPage(
        first,
        context,
        'next',
        page([
          {...terminal, 'messageSequence': '2'},
        ]),
      ),
      rejects,
    );
  });
  test(
    'normal metadata mandatory, terminal bodies forbidden, empty snapshot valid',
    () {
      final details = Map<String, Object?>.from(message['details'] as Map);
      for (final invalid in [
        {...details, 'fromUserId': 0},
        {...details, 'createTime': '2026-02-30T00:00:00'},
        {...details, 'isBurn': true},
      ]) {
        expect(
          () => parseSnapshotItem({...message, 'details': invalid}),
          rejects,
        );
      }
      expect(() => parseSnapshotItem({...message, 'state': 'BURNED'}), rejects);
      expect(
        stageRecoverySnapshotPage(
          begin(),
          context,
          null,
          page([]),
        ).stage.complete,
        isTrue,
      );
    },
  );
}
