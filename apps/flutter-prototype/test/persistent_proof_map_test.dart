import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/core/persistent_proof_map.dart';

void main() {
  test(
    'draft updates, removals and clear preserve every frozen generation',
    () {
      final input = {for (var i = 0; i < 5000; i++) 'm$i': '$i'};
      final first = PersistentProofMap<String>.from(input);
      input.clear();
      final draft = ProofMapDraft<String>.from(first);
      for (var i = 0; i < 1000; i++) {
        draft['m$i'] = 'changed';
      }
      draft.remove('m2000');
      draft['new'] = 'new';
      final second = draft.freeze();
      expect(first.length, 5000);
      expect(first['m1'], '1');
      expect(second['m1'], 'changed');
      expect(second.length, 5000);
      expect(second.containsKey('m2000'), false);
      expect(first.containsKey('m2000'), true);
      expect(second.keys.toSet().length, second.length);
      expect(() => draft['late'] = 'late', throwsStateError);
      expect(() => draft.clear(), throwsStateError);
      expect(() => second.remove('m1'), throwsUnsupportedError);
      final third = ProofMapDraft<String>.from(second)..clear();
      expect(third.freeze(), isEmpty);
      expect(second.length, 5000);
    },
  );
}
