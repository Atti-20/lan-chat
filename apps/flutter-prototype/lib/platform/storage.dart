import 'dart:convert';
import 'dart:io';
import 'dart:isolate';
import 'package:crypto/crypto.dart';
import 'package:flutter/services.dart';
import '../core/models.dart';
import '../core/store.dart';

const _channel = MethodChannel('com.meshx.mobile/storage');

class _DigestResult implements Sink<Digest> {
  Digest? value;
  @override
  void add(Digest data) => value = data;
  @override
  void close() {}
}

String _recoveryChecksum(String owner, int revision, String payload) {
  final result = _DigestResult();
  final encoder = JsonUtf8Encoder().startChunkedConversion(
    sha256.startChunkedConversion(result),
  );
  encoder.add([owner, 2, revision, payload]);
  encoder.close();
  return result.value!.toString();
}

// The main-isolate queue serializes all in-process callers; this stable lock
// inode additionally serializes cooperating OS processes. Never unlink it while
// another process can be waiting on it. Readers see the atomically renamed image.
Future<T> _withAccountFileLock<T>(
  String destination,
  Future<T> Function() action,
) async {
  final handle = await File('$destination.lock').open(mode: FileMode.append);
  try {
    await handle.lock(FileLock.blockingExclusive);
    try {
      return await action();
    } finally {
      await handle.unlock();
    }
  } finally {
    await handle.close();
  }
}

/// Keychain / Keystore failures propagate. No plaintext fallback.
class NativeCredentialStore implements CredentialStore {
  @override
  Future<Json?> read() async {
    final value = await _channel.invokeMethod<String>('readCredential');
    return value == null ? null : jsonDecode(value) as Json;
  }

  @override
  Future<void> write(Json credentials) =>
      _channel.invokeMethod<void>('writeCredential', jsonEncode(credentials));
  @override
  Future<void> clear() => _channel.invokeMethod<void>('clearCredential');
}

class FileChatStore implements ChatStore, RecoveryChatStore {
  FileChatStore({this.directory});
  final Directory? directory;
  // All instances in the application isolate share the same commit order.
  static Future<void> _tail = Future.value();
  Future<Directory> _directory() async =>
      directory ??
      Directory((await _channel.invokeMethod<String>('dataDirectory'))!);
  String _name(String owner) => base64Url.encode(utf8.encode(owner));
  @override
  Future<Json?> load(String owner) async {
    await _tail;
    final dir = await _directory();
    final file = File('${dir.path}/${_name(owner)}.json');
    if (!await file.exists()) return null;
    final value = jsonDecode(await file.readAsString()) as Json;
    if (value['owner'] != owner || value['version'] != 1) {
      throw const FormatException('本地数据账号或版本不匹配');
    }
    return value['snapshot'] as Json;
  }

  @override
  Future<void> save(String owner, Json snapshot) {
    // Capture before queueing: callers may mutate their lists after this call.
    final bytes = jsonEncode({
      'owner': owner,
      'version': 1,
      'snapshot': snapshot,
    });
    final operation = _tail.then((_) async {
      final dir = await _directory();
      await dir.create(recursive: true);
      final destination = '${dir.path}/${_name(owner)}.json';
      await _withAccountFileLock(destination, () async {
        final existing = File(destination);
        if (await existing.exists()) {
          final envelope = jsonDecode(await existing.readAsString());
          if (envelope is! Map ||
              envelope['owner'] != owner ||
              envelope['version'] != 1) {
            throw const FormatException('恢复缓存不可由旧格式覆盖');
          }
        }
        final temp = File('$destination.tmp');
        await temp.writeAsString(bytes, flush: true);
        await temp.rename(destination);
      });
    });
    _tail = operation.catchError((Object _) {});
    return operation;
  }

  ({int revision, String payload}) _checkedRecoveryPayload(
    String owner,
    Object? value,
  ) {
    if (value is! Map ||
        value['owner'] != owner ||
        value['version'] != 2 ||
        value['recoveryStoreVersion'] != 2 ||
        value['revision'] is! int ||
        value['revision'] < 1 ||
        value['revision'] > 9007199254740991 ||
        value['payload'] is! String ||
        value['checksum'] is! String) {
      throw const FormatException('恢复缓存账号或版本无效');
    }
    final payload = value['payload'] as String;
    if (_recoveryChecksum(owner, value['revision'] as int, payload) !=
        value['checksum']) {
      throw const FormatException('恢复缓存校验失败');
    }
    return (revision: value['revision'] as int, payload: payload);
  }

  RecoveryStoredSnapshot _decodeRecovery(String owner, Object? value) {
    final checked = _checkedRecoveryPayload(owner, value);
    final snapshot = jsonDecode(checked.payload);
    if (snapshot is! Map<String, dynamic>) {
      throw const FormatException('恢复缓存内容无效');
    }
    return RecoveryStoredSnapshot(checked.revision, snapshot);
  }

  int _recoveryRevision(String owner, Object? value) {
    final checked = _checkedRecoveryPayload(owner, value);
    // Commit needs the old revision, not a second live graph of all old bodies.
    // The decoder still validates every byte and the original root's map shape.
    final root = JsonDecoder(
      (key, value) => key == null ? value : null,
    ).convert(checked.payload);
    if (root is! Map<String, dynamic>) {
      throw const FormatException('恢复缓存内容无效');
    }
    return checked.revision;
  }

  Future<Object?> _readRecoveryEnvelope(File file) =>
      file.openRead().transform(utf8.decoder).transform(json.decoder).single;

  @override
  Future<RecoveryStoredSnapshot?> loadRecovery(String owner) async {
    await _tail;
    final dir = await _directory();
    return Isolate.run(
      _RecoveryRead(dir.path, owner).call,
      debugName: 'meshx-recovery-read',
    );
  }

  @override
  Future<int> commitRecovery(
    String owner,
    int expectedRevision,
    Json snapshot,
  ) {
    if (expectedRevision < 0 || expectedRevision >= 9007199254740991) {
      return Future.error(const FormatException('恢复缓存版本溢出'));
    }
    // Capture synchronously, including nested lists, before any asynchronous work.
    // Isolate.run starts sending this request before returning. Its message
    // graph is captured now, before callers can mutate or enqueue another image.
    // A method tear-off prevents capturing this store or a platform channel.
    final encoded = Isolate.run(
      _RecoveryEncode(owner, expectedRevision + 1, snapshot).call,
      debugName: 'meshx-recovery-encode',
    );
    // Attach the error listener immediately, even if another write is queued.
    final operation =
        Future.wait<Object?>([_tail.then<Object?>((_) => null), encoded]).then((
          results,
        ) async {
          final dir = await _directory();
          return Isolate.run(
            _RecoveryWrite(
              dir.path,
              owner,
              expectedRevision,
              results[1] as String,
            ).call,
            debugName: 'meshx-recovery-write',
          );
        });
    _tail = operation.then<void>((_) {}, onError: (Object _) {});
    return operation;
  }
}

class _RecoveryEncode {
  const _RecoveryEncode(this.owner, this.revision, this.snapshot);
  final String owner;
  final int revision;
  final Json snapshot;
  String call() {
    final payload = jsonEncode(snapshot);
    return jsonEncode({
      'owner': owner,
      'version': 2,
      'recoveryStoreVersion': 2,
      'revision': revision,
      'payload': payload,
      'checksum': _recoveryChecksum(owner, revision, payload),
    });
  }
}

class _RecoveryRead {
  const _RecoveryRead(this.path, this.owner);
  final String path, owner;
  Future<RecoveryStoredSnapshot?> call() async {
    final store = FileChatStore(directory: Directory(path));
    final file = File('$path/${store._name(owner)}.json');
    if (!await file.exists()) return null;
    final envelope = await store._readRecoveryEnvelope(file);
    if (envelope is Map &&
        envelope['owner'] == owner &&
        envelope['version'] == 1) {
      return null;
    }
    return store._decodeRecovery(owner, envelope);
  }
}

class _RecoveryWrite {
  const _RecoveryWrite(
    this.path,
    this.owner,
    this.expectedRevision,
    this.bytes,
  );
  final String path, owner, bytes;
  final int expectedRevision;
  Future<int> call() async {
    final directory = Directory(path);
    await directory.create(recursive: true);
    final store = FileChatStore(directory: directory);
    final file = File('$path/${store._name(owner)}.json');
    return _withAccountFileLock(file.path, () async {
      var currentRevision = 0;
      if (await file.exists()) {
        final old = await store._readRecoveryEnvelope(file);
        if (old is! Map || old['owner'] != owner) {
          throw const FormatException('恢复缓存账号无效');
        }
        if (old['version'] != 1) {
          currentRevision = store._recoveryRevision(owner, old);
        }
      }
      if (currentRevision != expectedRevision) {
        throw StateError('RECOVERY_GENERATION_CONFLICT');
      }
      final temp = File('${file.path}.tmp');
      try {
        await temp.writeAsString(bytes, flush: true);
        await temp.rename(file.path);
      } finally {
        if (await temp.exists()) await temp.delete();
      }
      return expectedRevision + 1;
    });
  }
}

class FilePreferenceStore implements PreferenceStore {
  FilePreferenceStore({this.directory});
  final Directory? directory;
  Future<void> _tail = Future.value();
  Future<Directory> _directory() async =>
      directory ??
      Directory((await _channel.invokeMethod<String>('dataDirectory'))!);

  Future<File> _file() async =>
      File('${(await _directory()).path}/preferences.json');

  @override
  Future<String?> read(String key) async {
    await _tail;
    final file = await _file();
    if (!await file.exists()) return null;
    final value = jsonDecode(await file.readAsString());
    if (value is! Map || value['version'] != 1 || value['values'] is! Map) {
      throw const FormatException('本地偏好版本无效');
    }
    final result = (value['values'] as Map)[key];
    if (result != null && result is! String) {
      throw const FormatException('本地偏好内容无效');
    }
    return result as String?;
  }

  @override
  Future<void> write(String key, String value) {
    final operation = _tail.then((_) async {
      final dir = await _directory();
      await dir.create(recursive: true);
      final file = File('${dir.path}/preferences.json');
      final values = <String, String>{};
      if (await file.exists()) {
        final current = jsonDecode(await file.readAsString());
        if (current is! Map ||
            current['version'] != 1 ||
            current['values'] is! Map) {
          throw const FormatException('本地偏好版本无效');
        }
        for (final entry in (current['values'] as Map).entries) {
          if (entry.key is! String || entry.value is! String) {
            throw const FormatException('本地偏好内容无效');
          }
          values[entry.key as String] = entry.value as String;
        }
      }
      values[key] = value;
      final temp = File('${file.path}.tmp');
      await temp.writeAsString(
        jsonEncode({'version': 1, 'values': values}),
        flush: true,
      );
      await temp.rename(file.path);
    });
    _tail = operation.catchError((Object _) {});
    return operation;
  }
}
