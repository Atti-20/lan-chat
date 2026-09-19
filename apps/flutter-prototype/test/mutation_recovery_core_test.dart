import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/core/recovery.dart';

RecoveryContext context(Map<String, dynamic> value) => RecoveryContext(
  origin: value['origin'],
  userId: value['userId'],
  streamEpoch: value['streamEpoch'],
  generation: value['generation'],
);
RecoveryState initial(Map<String, dynamic> data) {
  final message = data['message'], access = data['access'];
  return RecoveryState(
    context: context(data['owner']),
    phase: RecoveryPhase.onlineSafe,
    cursor: data['mutationCursor'],
    messages: {
      if (message != null)
        message['id']: MessageRecoveryProof(
          access['conversationId'],
          message['objectVersion'],
          MessageRecoveryState.values.byName(
            (message['state'] as String).toLowerCase(),
          ),
        ),
    },
    access: {
      access['conversationId']: AccessRecoveryProof(
        access['accessVersion'],
        access['readAllowed'],
        access['sendAllowed'],
      ),
    },
  );
}

String phaseName(RecoveryPhase phase) => switch (phase) {
  RecoveryPhase.quarantined => 'QUARANTINED',
  RecoveryPhase.catchingUp => 'CATCHING_UP',
  RecoveryPhase.onlineSafe => 'ONLINE_SAFE',
  RecoveryPhase.storageBlocked => 'STORAGE_BLOCKED',
  RecoveryPhase.blockedUpgrade => 'BLOCKED_UPGRADE',
};
void main() {
  final targets =
      jsonDecode(
            File(
              '../../contracts/test-vectors/mutation-recovery-v1-targets.json',
            ).readAsStringSync(),
          )
          as Map<String, dynamic>;
  final base = targets['baseState'] as Map<String, dynamic>;
  test('HTTP page boundaries cannot jump cursor or hide missing records', () {
    final state = initial(base);
    final record = targets['cases'][0]['steps'][0]['record'];
    final page = <String, Object?>{
      'records': [record],
      'fromExclusive': '0',
      'through': '1',
      'nextCursor': '1',
      'hasMore': false,
      'floor': '0',
      'latest': '2',
      'streamEpoch': state.context.streamEpoch,
    };
    RecoveryPlan apply(Object? value) =>
        planRecoveryPage(state, state.context, '0', '1', value);
    final first = apply(page);
    expect(first.next.cursor, '1');
    expect(
      planRecoveryPage(first.next, state.context, '0', '1', page).changed,
      isFalse,
    );
    for (final value in [
      {...page, 'records': []},
      {...page, 'nextCursor': '2'},
      {...page, 'hasMore': true},
      {...page, 'through': '2'},
      {...page, 'fromExclusive': '1'},
      {...page, 'latest': '0'},
      {...page, 'extra': true},
    ]) {
      final rejected = apply(value);
      expect(rejected.changed, isFalse);
      expect(rejected.next.cursor, '0');
      expect(rejected.next.phase, RecoveryPhase.quarantined);
      expect(rejected.effects.eraseMessages, isEmpty);
    }
    expect(apply({...page, 'floor': '1'}).next.reason, 'CURSOR_EXPIRED');
    final partial = {...page, 'through': '2', 'hasMore': true};
    expect(
      planRecoveryPage(
        state,
        state.context,
        '0',
        '2',
        partial,
        limit: 1,
      ).next.cursor,
      '1',
    );
    expect(
      planRecoveryPage(state, state.context, '0', '2', partial).changed,
      isFalse,
    );
    expect(
      identical(
        planRecoveryPage(state, state.context, '0', '0', {
          ...page,
          'records': [],
          'through': '0',
          'nextCursor': '0',
        }).next,
        state,
      ),
      isTrue,
    );
  });
  test(
    'READY requires durable cut, complete snapshot and matching generation',
    () {
      final state = initial(base).copyWith(phase: RecoveryPhase.catchingUp);
      final reply = <String, Object?>{
        'ready': true,
        'acceptedCursor': '0',
        'latest': '0',
        'streamEpoch': state.context.streamEpoch,
      };
      RecoveryState ready([
        Object? value,
        bool complete = true,
        RecoveryState? input,
      ]) {
        final current = input ?? state;
        return acceptRecoveryReady(
          current,
          current.context,
          '0',
          complete,
          value ?? reply,
        );
      }

      expect(ready().phase, RecoveryPhase.onlineSafe);
      expect(ready(reply, false).phase, RecoveryPhase.catchingUp);
      expect(
        ready(reply, true, state.copyWith(rebuild: {'group:21'})).phase,
        RecoveryPhase.catchingUp,
      );
      expect(
        ready({...reply, 'ready': false, 'latest': '1'}).phase,
        RecoveryPhase.catchingUp,
      );
      for (final value in [
        {...reply, 'latest': '1'},
        {...reply, 'acceptedCursor': '1', 'latest': '1'},
        {...reply, 'ready': false},
        {...reply, 'latest': 0},
        {...reply, 'content': 'unexpected'},
      ]) {
        expect(ready(value).phase, RecoveryPhase.quarantined);
        expect(ready(value).cursor, '0');
      }
      expect(
        ready({...reply, 'streamEpoch': 'different'}).reason,
        'STREAM_RESET',
      );
      final stale = RecoveryContext(
        origin: state.context.origin,
        userId: state.context.userId,
        streamEpoch: state.context.streamEpoch,
        generation: state.context.generation + 1,
      );
      expect(
        identical(acceptRecoveryReady(state, stale, '0', true, reply), state),
        isTrue,
      );
      final blocked = storageBlockedRecovery(state);
      expect(identical(ready(reply, true, blocked), blocked), isTrue);
      final disconnected = quarantineRecovery(state, 'DISCONNECTED');
      expect(identical(ready(reply, true, disconnected), disconnected), isTrue);
    },
  );
  // Only mutation metadata is projected here; persistence and OS effects need adapter tests.
  for (final target in targets['cases'] as List) {
    if (!(target['steps'] as List).every(
      (step) => step['op'] == 'applyMutation',
    )) {
      continue;
    }
    test('shared mutation planning projection: ${target['id']}', () {
      var state = initial({
        ...base,
        ...target['initialOverride'] as Map<String, dynamic>,
      });
      final before = state,
          expected = target['expected'] as Map<String, dynamic>;
      final stopped = <String>{}, revoked = <String>{};
      for (final step in target['steps']) {
        final plan = planRecoveryMutations(state, context(step['context']), [
          step['record'],
        ]);
        state = plan.next;
        stopped.addAll(plan.effects.stopAutomaticSend);
        revoked.addAll(plan.effects.revokeConversations);
      }
      if (expected.containsKey('mutationCursor')) {
        expect(state.cursor, expected['mutationCursor']);
      }
      if (expected.containsKey('messageState')) {
        expect(
          state.messages['message-a']?.state.name.toUpperCase(),
          expected['messageState'],
        );
      }
      if (expected.containsKey('phase')) {
        expect(phaseName(state.phase), expected['phase']);
      }
      if (expected.containsKey('reason')) {
        expect(state.reason, expected['reason']);
      }
      if (expected.containsKey('readAllowed')) {
        expect(state.access['group:21']?.readAllowed, expected['readAllowed']);
      }
      if (expected.containsKey('sendAllowed')) {
        expect(state.access['group:21']?.sendAllowed, expected['sendAllowed']);
      }
      if ((expected.containsKey('body') && expected['body'] == null) ||
          expected['cacheVisible'] == false) {
        expect(recoveryMessageVisible(state, 'message-a'), false);
      }
      if (expected['outboxState'] == 'DROP_BODY_REVOKED') {
        expect(revoked.contains('group:21'), true);
      }
      if (expected['autoSend'] == false) {
        expect(stopped.contains('group:21'), true);
      }
      if (expected['noStateWrite'] == true) {
        expect(identical(state, before), true);
      }
      expect(before.cursor, '0');
    });
  }
  final record =
      targets['cases'][0]['steps'][0]['record'] as Map<String, dynamic>;
  test('whole-page failure abandons preceding changes and effects', () {
    final state = initial(base);
    final plan = planRecoveryMutations(state, state.context, [
      record,
      {
        ...record,
        'eventId': '33333333-3333-4333-8333-333333333333',
        'cursor': '3',
      },
    ]);
    expect(plan.next.cursor, '0');
    expect(plan.next.reason, 'CURSOR_GAP');
    expect(plan.next.messages['message-a']!.state, MessageRecoveryState.normal);
    expect(plan.effects.eraseMessages, isEmpty);
  });
  test(
    'same event cannot move to another cursor and forbidden body fields fail closed',
    () {
      final state = initial(base),
          first = planRecoveryMutations(initial(base), context(base['owner']), [
            record,
          ]).next;
      expect(
        planRecoveryMutations(first, first.context, [
          {...record, 'cursor': '2'},
        ]).next.reason,
        'PROTOCOL_ERROR',
      );
      expect(
        planRecoveryMutations(state, state.context, [
          {...record, 'content': 'forbidden'},
        ]).next.cursor,
        '0',
      );
      for (final value in <Object>[
        '01',
        '-1',
        '1.0',
        '9223372036854775808',
        1,
      ]) {
        expect(
          () => recoveryDecimal(value),
          throwsA(isA<RecoveryProtocolError>()),
        );
      }
      expect(
        recoveryDecimal('9223372036854775807'),
        BigInt.parse('9223372036854775807'),
      );
    },
  );
  test(
    'planning beyond the JavaScript safe integer range preserves exact cursor positions',
    () {
      final state = initial(base).copyWith(cursor: '9007199254740991');
      final plan = planRecoveryMutations(state, state.context, [
        {...record, 'cursor': '9007199254740992'},
      ]);
      expect(plan.next.cursor, '9007199254740992');
      expect(plan.changed, true);
    },
  );
}
