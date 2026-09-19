import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/core/recovery.dart';

void main() {
  test(
    'phase-only transitions reuse frozen proof; supplied edits cannot mutate any state',
    () {
      const context = RecoveryContext(
        origin: 'https://node.example',
        userId: '7',
        streamEpoch: '11111111-1111-4111-8111-111111111111',
        generation: 1,
      );
      const proof = MessageRecoveryProof(
        'group:21',
        '1',
        MessageRecoveryState.normal,
      );
      final supplied = {'m1': proof};
      final original = RecoveryState(context: context, messages: supplied);
      supplied.clear();
      final ready = original.copyWith(phase: RecoveryPhase.onlineSafe);
      expect(identical(ready.messages, original.messages), true);
      expect(ready.messages.keys, ['m1']);
      final edits = {'m2': proof};
      final changed = ready.copyWith(messages: edits);
      edits.clear();
      expect(changed.messages.keys, ['m2']);
      expect(original.messages.keys, ['m1']);
      expect(() => changed.messages.clear(), throwsUnsupportedError);
    },
  );
}
