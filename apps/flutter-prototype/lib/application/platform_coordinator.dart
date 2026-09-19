import 'dart:async';
import 'dart:convert';
import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import '../chat_controller.dart';
import '../core/models.dart';
import '../core/platform_ports.dart';
import '../core/store.dart';

/// Application ownership and policy. OS APIs and plugin DTOs stay in adapters.
class PlatformCoordinator extends ChangeNotifier {
  PlatformCoordinator({
    required this.chat,
    required this.lifecycle,
    required this.notifications,
    required this.files,
    required this.sharePort,
    required this.network,
    required this.settings,
    this.runtimeInfo,
    this.disposeAdapters,
  });
  final Future<void> Function()? disposeAdapters;
  final ChatController chat;
  final LifecyclePort lifecycle;
  final NotificationPort notifications;
  final FilePickerPort files;
  final SharePort sharePort;
  final NetworkChangePort network;
  final PermissionSettingsPort settings;
  final RuntimeInfoPort? runtimeInfo;
  CapabilityResult<void> lifecycleStatus = const CapabilityResult(
    CapabilityStatus.available,
  );
  final NotificationPolicy _notificationPolicy = NotificationPolicy();
  final List<StreamSubscription<Object?>> _subscriptions = [];
  Future<void> _lifecycleTail = Future.value(),
      _notificationTail = Future.value();
  AppVisibility visibility = AppVisibility.foreground;
  CapabilityResult<void> notificationStatus = const CapabilityResult(
    CapabilityStatus.permissionRequired,
  );
  CapabilityResult<void> fileStatus = const CapabilityResult(
    CapabilityStatus.available,
  );
  CapabilityResult<void>? settingsStatus;
  SelectedFile? selectedFile;
  bool busy = false, _disposed = false, _started = false;
  Future<void> _ownerBarrier = Future.value(), _fileBarrier = Future.value();
  bool _ownerReady = false;
  bool chatViewVisible = true;
  String? _owner;
  int _generation = 0;
  NotificationRoute? _pendingTap;
  bool _routingTap = false;
  void Function(int? broadcastId)? _broadcastNavigation;
  void Function()? _conversationNavigation;
  Timer? _networkDebounce, _notificationRetry;
  bool _drainingRecoveryNotifications = false;
  int lifecycleTransitions = 0, liveNotificationAttempts = 0;
  String? get owner => chat.session == null
      ? null
      : accountScope(chat.api!.origin, chat.session!.userId);
  void _notify() {
    if (!_disposed) notifyListeners();
  }

  void start() {
    if (_started) return;
    _started = true;
    visibility = lifecycle.current;
    chat.onLiveMessage = _liveMessage;
    chat.addListener(_accountChanged);
    _subscriptions.add(
      lifecycle.changes.listen((state) {
        visibility = state;
        _notify();
        // Inactive includes the system permission/picker overlay; no false app logout.
        if (state == AppVisibility.inactive) return;
        _enqueueLifecycle(() async {
          lifecycleTransitions++;
          if (state == AppVisibility.foreground) {
            await chat.resume();
            unawaited(requestNotifications(request: false));
          } else {
            await chat.pause();
          }
        });
      }),
    );
    _subscriptions.add(
      network.changes.listen((result) {
        if (!result.ok) {
          lifecycleStatus = result;
          _notify();
          return;
        }
        _networkDebounce?.cancel();
        _networkDebounce = Timer(
          const Duration(milliseconds: 250),
          () => _enqueueLifecycle(chat.networkChanged),
        );
      }),
    );
    _subscriptions.add(
      notifications.taps.listen((route) {
        _pendingTap = route;
        _consumeTap();
      }),
    );
    _accountChanged();
    if (visibility == AppVisibility.background) _enqueueLifecycle(chat.pause);
  }

  void _enqueueLifecycle(Future<void> Function() action) {
    _lifecycleTail = _lifecycleTail
        .then((_) async {
          if (!_disposed) await action();
        })
        .catchError((Object _) {
          if (!_disposed) {
            lifecycleStatus = const CapabilityResult(
              CapabilityStatus.failed,
              reason: 'lifecycleRecoveryFailed',
            );
            _notify();
          }
        });
  }

  void _accountChanged() {
    if (_disposed) return;
    final next = owner;
    if (next != _owner) {
      _owner = next;
      ++_generation;
      selectedFile = null;
      _notificationPolicy.reset(next);
      final generation = _generation;
      _ownerReady = false;
      // Invalidate native ownership immediately, before any old asynchronous show completes.
      _ownerBarrier = notifications.setOwner(next).then((result) {
        if (!_disposed && generation == _generation) {
          _ownerReady = result.ok;
          if (!result.ok) notificationStatus = result;
        }
      });
      _fileBarrier = files.releaseAll().then((result) {
        if (!_disposed && generation == _generation && !result.ok) {
          fileStatus = result;
          _notify();
        }
      });
    }
    _consumeTap();
    _drainRecoveryNotifications();
  }

  void _drainRecoveryNotifications() {
    if (_disposed ||
        _drainingRecoveryNotifications ||
        !chat.usesMutationRecovery ||
        chat.recoveryNotificationEffects.isEmpty) {
      return;
    }
    _notificationRetry?.cancel();
    _drainingRecoveryNotifications = true;
    final generation = _generation, current = owner;
    _notificationTail = _notificationTail
        .catchError((Object _) {})
        .then((_) async {
          await _ownerBarrier;
          while (!_disposed &&
              generation == _generation &&
              current == owner &&
              _ownerReady &&
              chat.online) {
            final effects = chat.recoveryNotificationEffects;
            if (effects.isEmpty) break;
            final effect = effects.first,
                key = effect['key'] as String,
                id = effect['messageId'] as String?;
            final osId = id == null
                ? null
                : 'meshx-${sha256.convert(utf8.encode(jsonEncode([current, id])))}';
            CapabilityResult<void> result;
            if (effect['kind'] == 'CANCEL_ALL') {
              result = await notifications.cancelAll(owner: current);
            } else if (effect['kind'] == 'CANCEL') {
              result = await notifications.cancel(osId!);
            } else {
              final message = chat.recoveryNotificationMessage(id!);
              final viewing =
                  visibility == AppVisibility.foreground &&
                  chatViewVisible &&
                  chat.active?.id == message?.conversationId;
              if (message == null || viewing) {
                await chat.acknowledgeRecoveryNotification(key);
                continue;
              }
              liveNotificationAttempts++;
              result = await notifications.show(
                osId!,
                NotificationRoute(
                  current!,
                  message.conversationId,
                  messageId: id,
                  broadcastId: _broadcastId(message),
                ),
              );
              if (_disposed ||
                  generation != _generation ||
                  current != owner ||
                  !chat.recoveryNotificationAllows(
                    id,
                    message.conversationId,
                  )) {
                if (current == owner) await notifications.cancel(osId);
                break;
              }
            }
            if (_disposed || generation != _generation || current != owner) {
              break;
            }
            notificationStatus = result;
            if (!result.ok) break;
            await chat.acknowledgeRecoveryNotification(key);
          }
        })
        .catchError((Object _) {
          if (!_disposed && generation == _generation) {
            notificationStatus = const CapabilityResult(
              CapabilityStatus.failed,
              reason: 'notificationRecoveryFailed',
            );
          }
        })
        .whenComplete(() {
          _drainingRecoveryNotifications = false;
          if (!_disposed && generation == _generation) {
            if (chat.recoveryNotificationEffects.isNotEmpty) {
              _notificationRetry = Timer(
                const Duration(seconds: 5),
                _drainRecoveryNotifications,
              );
            }
            _notify();
          }
        });
  }

  void setBroadcastNavigationHandler(void Function(int? broadcastId)? handler) {
    _broadcastNavigation = handler;
    _consumeTap();
  }

  void setConversationNavigationHandler(void Function()? handler) {
    _conversationNavigation = handler;
    _consumeTap();
  }

  void _consumeTap() {
    final route = _pendingTap;
    if (_disposed ||
        route == null ||
        owner == null ||
        !chat.online ||
        _routingTap) {
      return;
    }
    if (route.owner != owner) {
      _pendingTap = null;
      return;
    }
    if (chat.usesMutationRecovery &&
        !chat.recoveryNotificationAllows(
          route.messageId,
          route.conversationId,
        )) {
      _pendingTap = null;
      return;
    }
    if (route.broadcastId case final broadcastId?) {
      if (_broadcastNavigation == null) return;
      _routingTap = true;
      final expectedOwner = owner, generation = _generation;
      unawaited(() async {
        var valid = false;
        try {
          final detail = await chat.api!.broadcastDetail(broadcastId);
          valid = detail.canSubmit;
        } catch (_) {
          valid = false;
        }
        if (!_disposed &&
            generation == _generation &&
            expectedOwner == owner &&
            route == _pendingTap) {
          _pendingTap = null;
          _broadcastNavigation?.call(valid ? broadcastId : null);
        }
        _routingTap = false;
        _consumeTap();
      }());
      return;
    }
    if (_conversationNavigation == null) return;
    _pendingTap = null;
    final matches = chat.conversations.where(
      (item) => item.id == route.conversationId,
    );
    if (matches.isNotEmpty) {
      _conversationNavigation?.call();
      unawaited(chat.select(matches.first));
    }
  }

  void _liveMessage(ChatMessage message, bool live, bool alreadyStored) {
    final current = owner;
    if (_disposed || current == null || chat.usesMutationRecovery) return;
    final viewing =
        visibility == AppVisibility.foreground &&
        chatViewVisible &&
        chat.active?.id == message.conversationId;
    if (!_notificationPolicy.shouldShow(
      owner: current,
      id: message.messageId,
      live: live,
      ownMessage: message.fromUserId == chat.session?.userId,
      alreadyStored: alreadyStored,
      viewingConversation: viewing,
    )) {
      return;
    }
    final generation = _generation;
    _notificationTail = _notificationTail.then((_) async {
      await _ownerBarrier;
      if (!_ownerReady ||
          _disposed ||
          generation != _generation ||
          current != owner) {
        return;
      }
      liveNotificationAttempts++;
      final result = await notifications.show(
        message.messageId,
        NotificationRoute(
          current,
          message.conversationId,
          broadcastId: _broadcastId(message),
        ),
      );
      if (!_disposed && generation == _generation) {
        notificationStatus = result;
        _notify();
      }
    });
  }

  int? _broadcastId(ChatMessage message) {
    if (message.contentType != 'broadcast' || message.recalled) return null;
    try {
      final value = jsonDecode(message.content);
      if (value is! Map) return null;
      final raw = value['broadcastId'];
      final parsed = raw is int ? raw : int.tryParse('$raw');
      return parsed != null && parsed > 0 ? parsed : null;
    } catch (_) {
      return null;
    }
  }

  Future<void> requestNotifications({bool request = true}) async {
    notificationStatus = await notifications.permission(request: request);
    _notify();
  }

  Future<void> openSettings() async {
    if (busy || _disposed) return;
    busy = true;
    _notify();
    settingsStatus = await settings.openSettings();
    busy = false;
    _notify();
  }

  Future<CapabilityResult<RuntimeInfo>> readRuntimeInfo() async =>
      runtimeInfo?.readRuntimeInfo() ??
      const CapabilityResult(
        CapabilityStatus.unsupported,
        reason: 'runtimeInfoUnavailable',
      );

  Future<void> pickFile({int maxBytes = 25 * 1024 * 1024}) async {
    if (busy || _disposed) return;
    busy = true;
    _notify();
    final generation = _generation;
    try {
      await _fileBarrier; // Account cleanup must precede selection.
      final result = await files.pick(maxBytes: maxBytes);
      if (_disposed || generation != _generation) {
        if (result.value != null) await files.release(result.value!);
        return;
      }
      if (result.ok && result.value != null) {
        final previous = selectedFile;
        selectedFile = result.value;
        if (previous != null) await files.release(previous);
      }
      fileStatus = CapabilityResult(result.status, reason: result.reason);
    } finally {
      busy = false;
      _notify();
    }
  }

  Future<void> shareFile() async {
    if (busy || selectedFile == null || _disposed) return;
    busy = true;
    _notify();
    try {
      final generation = _generation;
      final result = await sharePort.share(selectedFile!);
      if (!_disposed && generation == _generation) fileStatus = result;
    } finally {
      busy = false;
      _notify();
    }
  }

  Future<CapabilityResult<List<int>>> readSelectedFile({
    required int maxBytes,
  }) async {
    final file = selectedFile;
    if (busy || file == null || _disposed) {
      return const CapabilityResult(
        CapabilityStatus.cancelled,
        reason: 'fileMissing',
      );
    }
    busy = true;
    _notify();
    final generation = _generation;
    try {
      final result = await files.read(file, maxBytes: maxBytes);
      if (_disposed || generation != _generation || selectedFile != file) {
        return const CapabilityResult(
          CapabilityStatus.cancelled,
          reason: 'sessionChanged',
        );
      }
      fileStatus = CapabilityResult(result.status, reason: result.reason);
      return result;
    } finally {
      busy = false;
      _notify();
    }
  }

  Future<CapabilityResult<List<int>>> readSelectedFileChunk({
    required int offset,
    required int length,
  }) async {
    final file = selectedFile;
    if (file == null || _disposed) {
      return const CapabilityResult(
        CapabilityStatus.cancelled,
        reason: 'fileMissing',
      );
    }
    final generation = _generation;
    final result = await files.readChunk(file, offset: offset, length: length);
    if (_disposed || generation != _generation || selectedFile != file) {
      return const CapabilityResult(
        CapabilityStatus.cancelled,
        reason: 'sessionChanged',
      );
    }
    fileStatus = CapabilityResult(result.status, reason: result.reason);
    return result;
  }

  Future<CapabilityResult<void>> shareDownloadedFile({
    required String name,
    required String mime,
    required List<int> bytes,
  }) async {
    if (busy || _disposed) {
      return const CapabilityResult(
        CapabilityStatus.temporarilyUnavailable,
        reason: 'operationBusy',
      );
    }
    busy = true;
    _notify();
    final generation = _generation;
    try {
      final cached = await files.cache(name: name, mime: mime, bytes: bytes);
      if (_disposed || generation != _generation) {
        if (cached.value != null) await files.release(cached.value!);
        return const CapabilityResult(
          CapabilityStatus.cancelled,
          reason: 'sessionChanged',
        );
      }
      if (!cached.ok || cached.value == null) {
        fileStatus = CapabilityResult(cached.status, reason: cached.reason);
        return fileStatus;
      }
      final shared = await sharePort.share(cached.value!);
      if (!_disposed && generation == _generation) fileStatus = shared;
      return shared;
    } finally {
      busy = false;
      _notify();
    }
  }

  Future<void> clearFile() async {
    if (_disposed) return;
    fileStatus = await files.releaseAll();
    selectedFile = null;
    _notify();
  }

  Future<void> drain() async {
    await _lifecycleTail;
    await _ownerBarrier;
    await _fileBarrier;
    await _notificationTail;
  }

  @override
  void dispose() {
    if (_disposed) return;
    _disposed = true;
    _broadcastNavigation = null;
    ++_generation;
    _networkDebounce?.cancel();
    _notificationRetry?.cancel();
    chat.removeListener(_accountChanged);
    chat.onLiveMessage = null;
    for (final subscription in _subscriptions) {
      unawaited(subscription.cancel());
    }
    lifecycle.dispose();
    unawaited(
      files.releaseAll().then((_) async {
        await disposeAdapters?.call();
      }),
    );
    super.dispose();
  }
}
