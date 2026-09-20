import 'dart:async';
import 'package:flutter/foundation.dart';
import '../data/friends_models.dart';
import '../data/meshx_api.dart';

class FriendsController extends ChangeNotifier {
  FriendsController({
    required this.api,
    required this.currentUserId,
    required this.changes,
    required this.onRelationshipsChanged,
    this.onRequestsChanged,
  });

  final MeshXApi api;
  final int currentUserId;
  final Stream<void> changes;
  final Future<void> Function() onRelationshipsChanged;
  final VoidCallback? onRequestsChanged;
  StreamSubscription<void>? _subscription;
  bool loading = false, searching = false;
  String? error, notice;
  List<FriendContact> friends = const [];
  List<FriendRequestItem> requests = const [];
  List<UserSearchResult> results = const [];
  final Set<String> _operations = {};
  bool operationRunning(String key) => _operations.contains(key);

  Future<void> start() async {
    _subscription ??= changes.listen((_) => unawaited(refresh()));
    await refresh();
  }

  Future<void> refresh() async {
    if (loading) return;
    loading = true;
    error = null;
    notifyListeners();
    try {
      final values = await Future.wait([api.friends(), api.friendRequests()]);
      friends = values[0] as List<FriendContact>;
      requests = values[1] as List<FriendRequestItem>;
    } catch (failure) {
      error = _describe(failure);
    } finally {
      loading = false;
      notifyListeners();
    }
  }

  Future<void> search(String keyword) async {
    final value = keyword.trim();
    if (value.isEmpty) {
      results = const [];
      error = null;
      notifyListeners();
      return;
    }
    searching = true;
    error = null;
    notifyListeners();
    try {
      results = (await api.searchUsers(
        value,
      )).where((item) => item.userId != currentUserId).toList();
    } catch (failure) {
      error = _describe(failure);
    } finally {
      searching = false;
      notifyListeners();
    }
  }

  Future<bool> sendRequest(UserSearchResult user, String message) async {
    return _mutate(
      'request:${user.userId}',
      () => api.sendFriendRequest(user.userId, message),
      '好友申请已发送',
    );
  }

  Future<bool> handleRequest(FriendRequestItem request, bool accept) async {
    final ok = await _mutate(
      'handle:${request.id}',
      () => api.handleFriendRequest(request.id, accept),
      accept ? '已同意好友申请' : '已拒绝好友申请',
    );
    if (ok) {
      onRequestsChanged?.call();
      await refresh();
      if (accept) await _refreshRelationships();
    }
    return ok;
  }

  Future<bool> setRemark(FriendContact friend, String remark) async {
    final ok = await _mutate(
      'remark:${friend.userId}',
      () => api.setFriendRemark(friend.userId, remark.trim()),
      '备注已更新',
    );
    if (ok) {
      await refresh();
      await _refreshRelationships();
    }
    return ok;
  }

  Future<bool> deleteFriend(FriendContact friend) async {
    final ok = await _mutate(
      'delete:${friend.userId}',
      () => api.deleteFriend(friend.userId),
      '已删除联系人，待发消息不会自动重发',
    );
    if (ok) {
      await refresh();
      await _refreshRelationships();
    }
    return ok;
  }

  Future<void> _refreshRelationships() async {
    try {
      await onRelationshipsChanged();
    } catch (failure) {
      error = '操作已完成，但会话刷新失败：${_describe(failure)}';
      notifyListeners();
    }
  }

  Future<bool> _mutate(
    String key,
    Future<void> Function() action,
    String success,
  ) async {
    if (!_operations.add(key)) {
      error = '操作正在进行，请勿重复提交';
      notifyListeners();
      return false;
    }
    error = null;
    notice = null;
    notifyListeners();
    try {
      await action();
      error = null;
      notice = success;
      notifyListeners();
      return true;
    } catch (failure) {
      error = _describe(failure);
      notifyListeners();
      return false;
    } finally {
      _operations.remove(key);
      notifyListeners();
    }
  }

  String _describe(Object failure) => failure is ApiException
      ? failure.message
      : failure is FormatException
      ? failure.message
      : '操作未完成，请稍后重试';

  void clearFeedback() {
    error = null;
    notice = null;
    notifyListeners();
  }

  @override
  void dispose() {
    unawaited(_subscription?.cancel());
    super.dispose();
  }
}
