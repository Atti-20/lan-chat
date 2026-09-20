import 'dart:async';
import 'package:flutter/foundation.dart';
import '../chat_controller.dart';
import '../data/meshx_api.dart';
import '../data/broadcast_models.dart';

/// Read-only navigation summaries, scoped to the currently synchronized owner.
class NavigationCountsController extends ChangeNotifier {
  NavigationCountsController(this.chat) {
    chat.addListener(_ownerChanged);
    _friends = chat.friendChanges.listen((_) => refreshFriends());
    _broadcasts = chat.broadcastChanges.listen((_) => refreshBroadcasts());
    _ownerChanged();
  }

  final ChatController chat;
  late final StreamSubscription<void> _friends, _broadcasts;
  MeshXApi? _api;
  int? _user;
  int _epoch = 0;
  bool _disposed = false;
  int? friendRequests, broadcastTasks;
  final _running = <String, int>{};
  final _again = <String>{};
  Timer? _deadlineTimer;
  final _deadlineRechecks = <String, int>{};

  void _ownerChanged() {
    final api = chat.online ? chat.api : null;
    final user = chat.online ? chat.session?.userId : null;
    if (identical(api, _api) && user == _user) return;
    _api = api;
    _user = user;
    ++_epoch;
    _deadlineTimer?.cancel();
    _deadlineRechecks.clear();
    _running.clear();
    _again.clear();
    friendRequests = broadcastTasks = null;
    notifyListeners();
    refresh();
  }

  void refresh() {
    refreshFriends();
    refreshBroadcasts();
  }

  void refreshFriends() => unawaited(_refresh('friends'));
  void refreshBroadcasts() => unawaited(_refresh('broadcasts'));

  Future<void> _refresh(String kind) async {
    final api = _api, user = _user, epoch = _epoch;
    if (_disposed || api == null || user == null) return;
    if (_running[kind] == epoch) {
      _again.add(kind);
      return;
    }
    _running[kind] = epoch;
    bool current() =>
        !_disposed &&
        epoch == _epoch &&
        identical(chat.api, api) &&
        chat.session?.userId == user &&
        chat.online;
    try {
      if (kind == 'friends') {
        final requests = await api.friendRequests();
        if (!current()) return;
        friendRequests = requests
            .where((r) => r.toUserId == user && r.status == 0)
            .map((r) => r.id)
            .toSet()
            .length;
      } else {
        final pending = await api.broadcasts(pending: true);
        if (!current()) return;
        broadcastTasks = pending.map((b) => b.id).toSet().length;
        _scheduleDeadlines(pending);
      }
    } catch (_) {
      if (!current()) return;
      // Unknown is hidden, never presented as an authoritative zero.
      if (kind == 'friends') {
        friendRequests = null;
      } else {
        broadcastTasks = null;
        _deadlineTimer?.cancel();
      }
    } finally {
      if (current()) {
        _running.remove(kind);
        notifyListeners();
        if (_again.remove(kind)) unawaited(_refresh(kind));
      }
    }
  }

  void _scheduleDeadlines(List<BroadcastSummary> pending) {
    _deadlineTimer?.cancel();
    _deadlineTimer = null;
    final now = DateTime.now();
    final active = <String>{}, expired = <String>{};
    Duration? next;
    for (final item in pending) {
      final deadline = item.deadlineAt;
      if (!item.active || deadline == null) continue;
      final key = '${item.id}:${deadline.microsecondsSinceEpoch}';
      active.add(key);
      final past = !deadline.isAfter(now);
      if (past && (_deadlineRechecks[key] ?? 0) >= 5) continue;
      if (past) expired.add(key);
      final delay = past
          ? const Duration(seconds: 1)
          : deadline.difference(now) + const Duration(milliseconds: 1);
      if (next == null || delay < next) next = delay;
    }
    _deadlineRechecks.removeWhere((key, _) => !active.contains(key));
    if (_disposed || next == null) return;
    final epoch = _epoch;
    _deadlineTimer = Timer(next, () {
      if (_disposed || epoch != _epoch || !chat.online) return;
      // One read rechecks every expired item; do not create one timer per item.
      for (final key in expired) {
        _deadlineRechecks[key] = (_deadlineRechecks[key] ?? 0) + 1;
      }
      refreshBroadcasts();
    });
  }

  @override
  void dispose() {
    _disposed = true;
    _deadlineTimer?.cancel();
    ++_epoch;
    chat.removeListener(_ownerChanged);
    unawaited(_friends.cancel());
    unawaited(_broadcasts.cancel());
    super.dispose();
  }
}
