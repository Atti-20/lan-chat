import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/core/notification_recovery.dart';
import 'package:meshx_flutter_probe/core/recovery.dart';

RecoveryState state({bool terminal = false, bool read = true}) => RecoveryState(
  context: const RecoveryContext(
    origin: 'http://node',
    userId: '7',
    streamEpoch: '11111111-1111-4111-8111-111111111111',
    generation: 1,
  ),
  phase: RecoveryPhase.onlineSafe,
  cursor: '1',
  messages: {
    'm': MessageRecoveryProof(
      'group:21',
      terminal ? '2' : '1',
      terminal ? MessageRecoveryState.recalled : MessageRecoveryState.normal,
    ),
  },
  access: {'group:21': AccessRecoveryProof(read ? '1' : '2', read, read)},
);
void main() {
  test(
    'only live candidates create durable SHOW; restart retains route and one intent',
    () {
      final journal = RecoveryNotificationJournal();
      journal.reconcile(state());
      expect(journal.image()['effects'], [
        {'key': 'migration', 'kind': 'CANCEL_ALL'},
      ]);
      journal.acknowledge('migration');
      journal.reconcile(state(), ['m']);
      expect(journal.allows(state(), 'm'), true);
      final restarted = RecoveryNotificationJournal(journal.image());
      restarted.reconcile(state(), ['m']);
      expect((restarted.image()['effects'] as List).length, 1);
      restarted.acknowledge('show:m');
      restarted.reconcile(state());
      expect(restarted.image()['effects'], isEmpty);
      expect(restarted.allows(state(), 'm'), true);
    },
  );
  test(
    'terminal and revoke invalidate route, persist cancellation until acknowledgement',
    () {
      for (final changed in [state(terminal: true), state(read: false)]) {
        final journal = RecoveryNotificationJournal();
        journal.acknowledge('migration');
        journal.reconcile(state(), ['m']);
        journal.reconcile(changed);
        expect(journal.allows(changed, 'm'), false);
        expect(journal.image()['effects'], [
          {'key': 'cancel:m', 'kind': 'CANCEL', 'messageId': 'm'},
        ]);
        final retry = RecoveryNotificationJournal(journal.image());
        retry.reconcile(changed);
        expect((retry.image()['effects'] as List).length, 1);
        retry.acknowledge('cancel:m');
        expect(retry.image()['effects'], isEmpty);
      }
    },
  );
  test('body-bearing journal and orphan SHOW are rejected', () {
    expect(
      () => RecoveryNotificationJournal({
        'version': 1,
        'routes': [],
        'effects': [],
        'body': 'secret',
      }),
      throwsA(isA<RecoveryProtocolError>()),
    );
    expect(
      () => RecoveryNotificationJournal({
        'version': 1,
        'routes': [],
        'effects': [
          {'key': 'show:m', 'kind': 'SHOW', 'messageId': 'm'},
        ],
      }),
      throwsA(isA<RecoveryProtocolError>()),
    );
  });
}
