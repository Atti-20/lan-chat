import 'dart:convert';
import 'dart:io';

// Separate OS process: Dart file locks are process-scoped on macOS/Linux, so
// spawning a second isolate would not exercise cross-process exclusion.
Future<void> main(List<String> args) async {
  final handle = await File(args.first).open(mode: FileMode.append);
  try {
    await handle.lock(FileLock.blockingExclusive);
    stdout.writeln('LOCKED');
    await stdin.transform(utf8.decoder).transform(const LineSplitter()).first;
    if (args.length == 3) {
      final temporary = File('${args[1]}.other.tmp');
      await temporary.writeAsString(
        await File(args[2]).readAsString(),
        flush: true,
      );
      await temporary.rename(args[1]);
    }
    await handle.unlock();
  } finally {
    await handle.close();
  }
}
