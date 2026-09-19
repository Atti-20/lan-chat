import 'dart:convert';
import 'dart:io';
import 'package:integration_test/integration_test_driver.dart';

Future<void> main() async {
  final out = Directory(
    Platform.environment['PROBE_EVIDENCE_DIR'] ??
        '../../output/flutter-prototype-2026-09-08',
  );
  await out.create(recursive: true);
  await integrationDriver(
    writeResponseOnFailure: true,
    responseDataCallback: (data) async {
      if (data == null) return;
      final screenshots = data.remove('screenshots') as List<dynamic>? ?? [];
      for (final item in screenshots) {
        final name = item['screenshotName'] as String;
        if (!RegExp(r'^[a-z0-9-]+$').hasMatch(name)) {
          throw const FormatException('Invalid evidence name');
        }
        await File(
          '${out.path}/$name.png',
        ).writeAsBytes((item['bytes'] as List).cast<int>());
      }
      final platform =
          (data['metrics'] as Map?)?['platform'] as String? ?? 'unknown';
      await File(
        '${out.path}/$platform-integration.json',
      ).writeAsString(const JsonEncoder.withIndent('  ').convert(data));
      stdout.writeln('Saved integration evidence for $platform');
    },
  );
}
