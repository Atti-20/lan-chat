import 'dart:convert';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/platform/storage.dart';
import 'package:meshx_flutter_probe/core/json_views.dart';
import 'package:meshx_flutter_probe/core/persistent_proof_map.dart';

void main() {
  late Directory directory;
  late FileChatStore store;
  const owner = 'https://node.example|7';
  File active() =>
      File('${directory.path}/${base64Url.encode(utf8.encode(owner))}.json');
  setUp(() async {
    directory = await Directory.systemTemp.createTemp('meshx-recovery-store-');
    store = FileChatStore(directory: directory);
  });
  tearDown(() async => directory.delete(recursive: true));

  test('isolated encoder preserves every bucket-backed proof value', () async {
    final proofs = {for (var i = 0; i < 4096; i++) 'message-$i': '$i'};
    await store.commitRecovery(owner, 0, {
      'proofs': JsonMapView(
        PersistentProofMap<String>.from(proofs),
        (value) => {'version': value},
      ),
    });
    final saved = (await store.loadRecovery(owner))!.snapshot['proofs'];
    expect(saved, {
      for (final entry in proofs.entries) entry.key: {'version': entry.value},
    });
  });

  Future<Process> holdAccountLock({bool replaceOnRelease = false}) async {
    final process = await Process.start('dart', [
      'test/support/account_lock_holder.dart',
      '${active().path}.lock',
      if (replaceOnRelease) active().path,
      if (replaceOnRelease) '${directory.path}/other-image.json',
    ]);
    final errors = process.stderr.transform(utf8.decoder).join();
    addTearDown(() async {
      process.kill();
      await process.exitCode;
    });
    final line = await process.stdout
        .transform(utf8.decoder)
        .transform(const LineSplitter())
        .first
        .timeout(const Duration(seconds: 15));
    if (line != 'LOCKED') fail('Lock holder failed: ${await errors}');
    return process;
  }

  test(
    'another OS process fences replacement and revision is checked after its unlock',
    () async {
      await store.commitRecovery(owner, 0, {'cursor': '1'});
      final holder = await holdAccountLock(replaceOnRelease: true);
      var completed = false;
      final pending = store.commitRecovery(owner, 1, {'cursor': 'stale'});
      final rejection = expectLater(pending, throwsStateError);
      pending.then<void>(
        (_) => completed = true,
        onError: (Object _) {
          completed = true;
        },
      );
      await Future<void>.delayed(const Duration(milliseconds: 500));
      expect(completed, isFalse);
      final payload = jsonEncode({'cursor': 'other-process'});
      final replacement = jsonEncode({
        'owner': owner,
        'version': 2,
        'recoveryStoreVersion': 2,
        'revision': 2,
        'payload': payload,
        'checksum': sha256
            .convert(utf8.encode(jsonEncode([owner, 2, 2, payload])))
            .toString(),
      });
      await File(
        '${directory.path}/other-image.json',
      ).writeAsString(replacement, flush: true);
      holder.stdin.writeln('release');
      expect(await holder.exitCode, 0);
      await rejection;
      expect(await active().readAsString(), replacement);
    },
  );

  test(
    'crashed OS lock holder releases the lock and legacy writes cannot race migration',
    () async {
      await store.commitRecovery(owner, 0, {'cursor': '1'});
      final holder = await holdAccountLock();
      var completed = false;
      final pending = store.save(owner, {
        'messages': ['legacy'],
      });
      final rejection = expectLater(pending, throwsFormatException);
      pending.then<void>(
        (_) => completed = true,
        onError: (Object _) {
          completed = true;
        },
      );
      await Future<void>.delayed(const Duration(milliseconds: 500));
      expect(completed, isFalse);
      expect(holder.kill(ProcessSignal.sigkill), isTrue);
      await holder.exitCode;
      await rejection;
      expect(await store.commitRecovery(owner, 1, {'cursor': '2'}), 2);
      expect((await store.loadRecovery(owner))!.snapshot['cursor'], '2');
    },
  );

  test(
    'valid checksum cannot hide invalid payload during load or replacement',
    () async {
      for (final payload in ['{"nested":[1,]}', '[]', 'null', '"text"']) {
        final bytes = jsonEncode({
          'owner': owner,
          'version': 2,
          'recoveryStoreVersion': 2,
          'revision': 1,
          'payload': payload,
          'checksum': sha256
              .convert(utf8.encode(jsonEncode([owner, 2, 1, payload])))
              .toString(),
        });
        await active().writeAsString(bytes);
        await expectLater(store.loadRecovery(owner), throwsFormatException);
        await expectLater(
          store.commitRecovery(owner, 1, {'cursor': '2'}),
          throwsFormatException,
        );
        expect(await active().readAsString(), bytes);
      }
    },
  );

  test(
    'streamed envelope preserves Unicode across file chunks and replacement',
    () async {
      final snapshot = {
        'messages': List.generate(10000, (i) => '消息$i🙂é\\"\n'),
      };
      await store.commitRecovery(owner, 0, snapshot);
      expect((await store.loadRecovery(owner))!.snapshot, snapshot);
      expect(await store.commitRecovery(owner, 1, snapshot), 2);
      expect((await store.loadRecovery(owner))!.snapshot, snapshot);
    },
  );

  test(
    'lazy JSON views are captured before caller mutation and cannot be edited through the snapshot',
    () async {
      final rows = ['before'];
      final proofs = {'m1': '1'};
      final list = JsonListView(rows, (row) => {'content': row});
      final map = JsonMapView(proofs, (version) => {'objectVersion': version});
      expect(() => list.clear(), throwsUnsupportedError);
      expect(() => map.clear(), throwsUnsupportedError);
      final saving = store.commitRecovery(owner, 0, {
        'messages': list,
        'proofs': map,
      });
      rows.add('late');
      proofs['m1'] = '2';
      await saving;
      expect((await store.loadRecovery(owner))!.snapshot, {
        'messages': [
          {'content': 'before'},
        ],
        'proofs': {
          'm1': {'objectVersion': '1'},
        },
      });
    },
  );

  test(
    'streamed checksum reads and writes the unchanged format 2 byte contract',
    () async {
      final snapshot = {
        'messages': ['中文🙂\\"\n\u0000', 'é/路径'],
        'cursor': '8',
      };
      final payload = jsonEncode(snapshot);
      await active().writeAsString(
        jsonEncode({
          'owner': owner,
          'version': 2,
          'recoveryStoreVersion': 2,
          'revision': 1,
          'payload': payload,
          'checksum': sha256
              .convert(utf8.encode(jsonEncode([owner, 2, 1, payload])))
              .toString(),
        }),
      );
      expect((await store.loadRecovery(owner))!.snapshot, snapshot);
      await store.commitRecovery(owner, 1, snapshot);
      final envelope = jsonDecode(await active().readAsString()) as Map;
      expect(envelope['payload'], payload);
      expect(
        envelope['checksum'],
        sha256
            .convert(utf8.encode(jsonEncode([owner, 2, 2, payload])))
            .toString(),
      );
    },
  );

  test(
    'migration replaces old image; legacy reader and writer cannot revive it',
    () async {
      await store.save(owner, {
        'messages': [
          {'content': 'legacy-secret'},
        ],
      });
      expect(await store.loadRecovery(owner), isNull);
      expect(
        await store.commitRecovery(owner, 0, {
          'messages': [],
          'cursor': '3',
          'outbox': [],
        }),
        1,
      );
      expect(await active().readAsString(), isNot(contains('legacy-secret')));
      await expectLater(store.load(owner), throwsFormatException);
      await expectLater(
        store.save(owner, {
          'messages': ['late-legacy'],
        }),
        throwsFormatException,
      );
      final loaded = await FileChatStore(
        directory: directory,
      ).loadRecovery(owner);
      expect(loaded!.revision, 1);
      expect(loaded.snapshot['cursor'], '3');
      expect(await store.loadRecovery('https://other.example|7'), isNull);
    },
  );

  test(
    'capture is immutable and two store instances cannot commit the same revision',
    () async {
      final rows = <String>['original'];
      final first = store.commitRecovery(owner, 0, {
        'messages': rows,
        'cursor': '0',
      });
      rows.add('late-mutation');
      final other = FileChatStore(directory: directory);
      final second = other.commitRecovery(owner, 0, {'cursor': '100'});
      final rejection = expectLater(second, throwsStateError);
      expect(await first, 1);
      await rejection;
      expect((await store.loadRecovery(owner))!.snapshot['messages'], [
        'original',
      ]);
    },
  );

  test(
    'failed temporary write preserves entire old generation and permits retry',
    () async {
      await store.commitRecovery(owner, 0, {
        'messages': ['old'],
        'cursor': '0',
        'outbox': ['held'],
      });
      final obstacle = Directory('${active().path}.tmp');
      await obstacle.create();
      await expectLater(
        store.commitRecovery(owner, 1, {
          'messages': [],
          'cursor': '1',
          'outbox': [],
        }),
        throwsA(isA<FileSystemException>()),
      );
      final old = await store.loadRecovery(owner);
      expect(old!.revision, 1);
      expect(old.snapshot, {
        'messages': ['old'],
        'cursor': '0',
        'outbox': ['held'],
      });
      await obstacle.delete();
      expect(
        await store.commitRecovery(owner, 1, {
          'messages': [],
          'cursor': '1',
          'outbox': [],
        }),
        2,
      );
    },
  );

  test(
    'checksum corruption and unknown versions fail closed without fallback',
    () async {
      await store.commitRecovery(owner, 0, {'cursor': '1'});
      final bytes =
          jsonDecode(await active().readAsString()) as Map<String, dynamic>;
      bytes['payload'] = '{"cursor":"200"}';
      await active().writeAsString(jsonEncode(bytes));
      await expectLater(store.loadRecovery(owner), throwsFormatException);
      await expectLater(
        store.commitRecovery(owner, 1, {'cursor': '201'}),
        throwsFormatException,
      );
      bytes['version'] = 3;
      await active().writeAsString(jsonEncode(bytes));
      await expectLater(store.loadRecovery(owner), throwsFormatException);
      await expectLater(store.save(owner, {}), throwsFormatException);
    },
  );
}
