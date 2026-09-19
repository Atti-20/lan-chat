import 'dart:async';
import 'package:flutter/foundation.dart';
import '../chat_controller.dart';
import '../core/platform_ports.dart';
import '../data/broadcast_models.dart';
import '../data/meshx_api.dart';
import 'platform_coordinator.dart';

class BroadcastsController extends ChangeNotifier {
  BroadcastsController({
    required this.chat,
    this.platform,
    MeshXApi? api,
    Stream<void>? changes,
  }) : api = api ?? chat.api!,
       _changes = changes ?? chat.broadcastChanges;

  final ChatController chat;
  final MeshXApi api;
  final PlatformCoordinator? platform;
  final Stream<void> _changes;
  StreamSubscription<void>? _subscription;
  List<BroadcastSummary> items = const [], pending = const [];
  BroadcastDetail? detail;
  bool loading = false, busy = false;
  String? error, notice;
  int _generation = 0;
  int _detailGeneration = 0;
  bool _disposed = false, _detailRefreshPending = false;
  Timer? _deadlineTimer;
  int? _deadlineBroadcastId;
  DateTime? _scheduledDeadline;
  int _deadlineRechecks = 0;

  Future<void> start() async {
    _subscription ??= _changes.listen((_) => unawaited(refresh()));
    await refresh();
  }

  Future<void> refresh() async {
    if (_disposed) return;
    final detailRefresh = _refreshOpenDetail();
    final generation = ++_generation;
    loading = true;
    error = null;
    notifyListeners();
    try {
      final result = await Future.wait([
        api.broadcasts(),
        api.broadcasts(pending: true),
      ]);
      if (generation != _generation) return;
      items = result[0];
      pending = result[1];
    } catch (failure) {
      if (generation == _generation) error = chat.describe(failure);
    } finally {
      if (generation == _generation) {
        loading = false;
        _scheduleDeadline();
        notifyListeners();
      }
    }
    await detailRefresh;
  }

  Future<void> _refreshOpenDetail() async {
    if (_disposed) return;
    if (busy) {
      _detailRefreshPending = true;
      return;
    }
    if (detail == null) return;
    final current = detail!;
    final generation = ++_detailGeneration;
    bool isCurrent() =>
        !_disposed &&
        generation == _detailGeneration &&
        !busy &&
        identical(detail, current);
    try {
      final fresh = await api.broadcastDetail(current.broadcast.id);
      if (isCurrent()) detail = fresh;
    } catch (failure) {
      if (isCurrent()) {
        detail = null;
        error = chat.describe(failure);
      }
    } finally {
      if (!_disposed && generation == _detailGeneration) {
        _scheduleDeadline();
        notifyListeners();
      }
    }
  }

  void _scheduleDeadline() {
    _deadlineTimer?.cancel();
    _deadlineTimer = null;
    final current = detail;
    final deadline = current?.broadcast.deadlineAt;
    if (_deadlineBroadcastId != current?.broadcast.id ||
        _scheduledDeadline != deadline) {
      _deadlineBroadcastId = current?.broadcast.id;
      _scheduledDeadline = deadline;
      _deadlineRechecks = 0;
    }
    if (_disposed ||
        current == null ||
        deadline == null ||
        !current.broadcast.active) {
      return;
    }
    final locallyExpired = current.broadcast.expired;
    if (locallyExpired &&
        (!pending.any((item) => item.id == current.broadcast.id) ||
            _deadlineRechecks >= 5)) {
      return;
    }
    _deadlineTimer = Timer(
      locallyExpired
          ? const Duration(seconds: 1)
          : deadline.difference(DateTime.now()) +
                const Duration(milliseconds: 1),
      () {
        if (_disposed || !identical(detail, current)) return;
        if (locallyExpired) ++_deadlineRechecks;
        // Rebuild local eligibility immediately; the server still authorizes writes.
        notifyListeners();
        unawaited(refresh());
      },
    );
  }

  void _finishBusy() {
    busy = false;
    if (_disposed) return;
    _scheduleDeadline();
    notifyListeners();
    if (_detailRefreshPending) {
      _detailRefreshPending = false;
      unawaited(_refreshOpenDetail());
    }
  }

  Future<bool> open(int broadcastId) async {
    if (busy || _disposed) return false;
    _subscription ??= _changes.listen((_) => unawaited(refresh()));
    ++_detailGeneration;
    busy = true;
    error = null;
    notice = null;
    notifyListeners();
    try {
      var fresh = await api.broadcastDetail(broadcastId);
      if (fresh.receiver != null && fresh.receiver!.viewedAt == null) {
        await api.viewBroadcast(broadcastId);
        fresh = await api.broadcastDetail(broadcastId);
      }
      detail = fresh;
      return true;
    } catch (failure) {
      detail = null; // A cached card never authorizes a detail view.
      error = chat.describe(failure);
      return false;
    } finally {
      _finishBusy();
    }
  }

  Future<bool> confirm(String status) async {
    final current = detail;
    final normalized = status.trim().toUpperCase();
    if (busy || current == null || !current.canSubmit) return false;
    if (!current.confirmationOptions.contains(normalized)) {
      error = '该回执不在服务器允许的选项中';
      notifyListeners();
      return false;
    }
    if (normalized == 'EXECUTED' &&
        (current.broadcast.requireImageProof || current.locationReadOnly)) {
      error = current.locationReadOnly
          ? '该广播需要定位凭证，请使用 Web 端办理'
          : '该广播需要图片凭证，请使用“上传图片并完成”';
      notifyListeners();
      return false;
    }
    return _submit(
      () => api.confirmBroadcast(current.broadcast.id, normalized),
    );
  }

  Future<bool> complete() async {
    final current = detail;
    if (busy || current == null || !current.canSubmit) return false;
    if (current.locationReadOnly) {
      error = '该广播需要定位凭证，移动端仅可查看，请使用 Web 端办理';
      notifyListeners();
      return false;
    }
    busy = true;
    ++_detailGeneration;
    error = null;
    notice = null;
    notifyListeners();
    try {
      return await _completeWithProof(current);
    } finally {
      _finishBusy();
    }
  }

  Future<bool> _completeWithProof(BroadcastDetail current) async {
    var imageIds = <int>[];
    if (current.broadcast.requireImageProof) {
      final coordinator = platform;
      if (coordinator == null) {
        error = '当前环境不可用图片选择能力';
        notifyListeners();
        return false;
      }
      if (coordinator.busy) {
        error = '请先完成当前文件或系统操作';
        notifyListeners();
        return false;
      }
      await coordinator.pickFile(maxBytes: 5 * 1024 * 1024);
      final selected = coordinator.selectedFile;
      // A cancelled picker may retain another flow's previous selection.
      // Only the successful result of this selection authorizes an upload.
      if (!coordinator.fileStatus.ok || selected == null) {
        if (coordinator.fileStatus.status != CapabilityStatus.cancelled) {
          error = _imageFailureMessage(coordinator.fileStatus);
        }
        notifyListeners();
        return false;
      }
      if (!selected.mime.toLowerCase().startsWith('image/')) {
        error = '请选择图片文件';
        await coordinator.clearFile();
        notifyListeners();
        return false;
      }
      final read = await coordinator.readSelectedFile(
        maxBytes: 5 * 1024 * 1024,
      );
      if (!read.ok || read.value == null) {
        error = _imageFailureMessage(read);
        await coordinator.clearFile();
        notifyListeners();
        return false;
      }
      try {
        final uploaded = await api.uploadBroadcastImage(
          name: selected.name,
          mime: selected.mime,
          bytes: read.value!,
        );
        imageIds = [uploaded.id];
      } catch (failure) {
        error = chat.describe(failure);
        await coordinator.clearFile();
        notifyListeners();
        return false;
      }
      await coordinator.clearFile();
    }
    return _submit(
      () => api.completeBroadcast(current.broadcast.id, imageFileIds: imageIds),
      ownsBusy: true,
    );
  }

  String _imageFailureMessage<T>(CapabilityResult<T> result) {
    if (result.status == CapabilityStatus.failed) {
      return switch (result.reason) {
        'fileTooLarge' => '广播凭证图片不能超过 5MB，请重新选择',
        'fileMissing' => '图片已不可用，请重新选择',
        'fileUnreadable' || 'invalidFileContent' => '无法读取这张图片，请重新选择',
        _ => capabilityMessage(result.status),
      };
    }
    return capabilityMessage(result.status);
  }

  Future<bool> _submit(
    Future<BroadcastReceiverState> Function() operation, {
    bool ownsBusy = false,
  }) async {
    if ((!ownsBusy && busy) || detail == null) return false;
    ++_detailGeneration;
    busy = true;
    error = null;
    notice = null;
    notifyListeners();
    try {
      await operation();
      chat.notifyBroadcastsChanged();
      final id = detail!.broadcast.id;
      detail = await api.broadcastDetail(id);
      notice = '回执已由节点确认';
      await refresh();
      return true;
    } catch (failure) {
      error = chat.describe(failure);
      try {
        detail = await api.broadcastDetail(detail!.broadcast.id);
      } catch (_) {
        detail = null;
      }
      return false;
    } finally {
      _finishBusy();
    }
  }

  void clearFeedback() {
    error = null;
    notice = null;
    notifyListeners();
  }

  @override
  void dispose() {
    _deadlineTimer?.cancel();
    _disposed = true;
    ++_detailGeneration;
    ++_generation;
    unawaited(_subscription?.cancel());
    super.dispose();
  }
}
