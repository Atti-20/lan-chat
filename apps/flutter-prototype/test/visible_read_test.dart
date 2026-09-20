import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/core/visible_read.dart';

void main() {
  final fixture = jsonDecode(
    File(
      '../../contracts/test-vectors/recovery-visible-read.json',
    ).readAsStringSync(),
  );
  for (final c in fixture['cases']) {
    test(c['name'], () {
      final tracker = VisibleReadTracker('owner', c['baseline'], [
        for (final r in c['rows']) ReadRow(r['sequence'], r['own']),
      ], c['completeThrough']);
      expect([
        for (final s in c['samples'])
          tracker.sample(
            s['scope'],
            s['now'],
            Set<int>.from(s['visible']),
            s['foreground'],
          ),
      ], c['expected']);
    });
  }
}
