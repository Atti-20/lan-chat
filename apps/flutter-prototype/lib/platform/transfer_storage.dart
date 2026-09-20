import 'dart:convert';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:flutter/services.dart';
import '../core/models.dart';
import '../core/transfer_store.dart';

class FileTransferStore implements TransferStore {
  FileTransferStore({this.directory});
  final Directory? directory;
  static const _channel = MethodChannel('com.meshx.mobile/storage');
  Future<Directory> _dir(String owner) async {
    final root =
        directory?.path ??
        (await _channel.invokeMethod<String>('dataDirectory'))!;
    return Directory('$root/transfers/${sha256.convert(utf8.encode(owner))}');
  }

  String _id(String id) {
    if (!RegExp(r'^[a-zA-Z0-9-]{16,80}$').hasMatch(id)) {
      throw const FormatException('传输任务标识无效');
    }
    return id;
  }

  @override
  Future<Json> stage(
    String owner,
    Json metadata,
    Stream<List<int>> bytes,
  ) async {
    final dir = await _dir(owner);
    await dir.create(recursive: true);
    final tasks = await list(owner);
    while (tasks.length >= 20) {
      final cached = tasks.where((t) => t['kind'] == 'direct').toList()
        ..sort((a, b) => ('${a['createdAt']}').compareTo('${b['createdAt']}'));
      if (cached.isEmpty) {
        throw const FormatException('已有20个待续传任务，请继续或删除后再选择文件');
      }
      final oldest = cached.first;
      await remove(owner, oldest['clientUploadId'] as String);
      tasks.remove(oldest);
    }
    final id = _id(metadata['clientUploadId'] as String);
    final file = File('${dir.path}/$id.data');
    if (await file.exists()) throw const FormatException('传输任务已存在');
    final target = await file.open(mode: FileMode.writeOnly);
    var size = 0;
    try {
      await for (final chunk in bytes) {
        size += chunk.length;
        if (size > 25 * 1024 * 1024) throw const FormatException('附件超过25MiB');
        await target.writeFrom(chunk);
      }
      await target.flush();
      await target.close();
      if (size <= 0 || size != metadata['fileSize']) {
        throw const FormatException('文件副本大小发生变化');
      }
      final hash = await sha256.bind(file.openRead()).first;
      final task = <String, dynamic>{
        ...metadata,
        'fileHash': hash.toString(),
        'owner': owner,
        'createdAt': DateTime.now().toIso8601String(),
      };
      await update(owner, task);
      return task;
    } catch (_) {
      try {
        await target.close();
      } catch (_) {}
      if (await file.exists()) await file.delete();
      rethrow;
    }
  }

  @override
  Future<List<Json>> list(String owner) async {
    final dir = await _dir(owner);
    if (!await dir.exists()) return [];
    final values = <Json>[];
    await for (final file in dir.list()) {
      if (file is! File) continue;
      if (file.path.endsWith('.data')) {
        final meta = File(file.path.replaceFirst(RegExp(r'\.data$'), '.json'));
        if (!await meta.exists() &&
            DateTime.now().difference(await file.lastModified()) >
                const Duration(hours: 1)) {
          await file.delete();
        }
      }
      if (!file.path.endsWith('.json')) continue;
      final value = jsonDecode(await file.readAsString()) as Json;
      if (value['owner'] != owner) throw const FormatException('传输任务账号不匹配');
      _id(value['clientUploadId'] as String);
      values.add(value);
    }
    return values;
  }

  @override
  Future<List<int>> read(
    String owner,
    String id,
    int offset,
    int length,
  ) async {
    if (offset < 0 || length <= 0 || length > 8 * 1024 * 1024) {
      throw const FormatException('文件读取范围无效');
    }
    final dir = await _dir(owner);
    final handle = await File('${dir.path}/${_id(id)}.data').open();
    try {
      await handle.setPosition(offset);
      return await handle.read(length);
    } finally {
      await handle.close();
    }
  }

  @override
  Future<void> update(String owner, Json task) async {
    if (task['owner'] != owner) throw const FormatException('传输任务账号不匹配');
    final dir = await _dir(owner);
    await dir.create(recursive: true);
    final destination =
        '${dir.path}/${_id(task['clientUploadId'] as String)}.json';
    final temp = File('$destination.tmp');
    await temp.writeAsString(jsonEncode(task), flush: true);
    await temp.rename(destination);
  }

  @override
  Future<void> remove(String owner, String id) async {
    final dir = await _dir(owner);
    for (final suffix in ['.json', '.data', '.json.tmp']) {
      final file = File('${dir.path}/${_id(id)}$suffix');
      if (await file.exists()) await file.delete();
    }
  }
}
