import 'dart:async';
import 'package:flutter/foundation.dart';
import '../chat_controller.dart';
import '../data/friends_models.dart';
import '../data/groups_models.dart';
import '../data/meshx_api.dart';

class GroupsController extends ChangeNotifier {
  GroupsController({required this.chat})
    : api = chat.api!,
      currentUserId = chat.session!.userId;

  final ChatController chat;
  final MeshXApi api;
  final int currentUserId;
  bool loading = false, creating = false, loadingDetails = false;
  String? error, notice;
  List<MeshXGroup> groups = const [];
  List<FriendContact> friends = const [];
  MeshXGroup? detail;
  List<GroupMemberInfo> members = const [];
  final Set<int> _leaving = {};
  bool _disposed = false;

  bool get busy => loading || creating || loadingDetails || _leaving.isNotEmpty;
  bool leaving(int groupId) => _leaving.contains(groupId);
  bool get _current =>
      !_disposed &&
      identical(chat.api, api) &&
      chat.session?.userId == currentUserId;

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  Future<void> start() => refresh();

  Future<void> refresh() async {
    if (loading || !_current) return;
    loading = true;
    error = null;
    _notify();
    try {
      final values = await Future.wait([api.groups(), api.friends()]);
      if (!_current) return;
      groups = values[0] as List<MeshXGroup>;
      friends = values[1] as List<FriendContact>;
    } catch (failure) {
      if (_current) error = _describe(failure);
    } finally {
      if (_current) {
        loading = false;
        _notify();
      }
    }
  }

  Future<void> loadDetails(MeshXGroup group) async {
    if (loadingDetails || !_current) return;
    loadingDetails = true;
    error = null;
    detail = group;
    members = const [];
    _notify();
    try {
      final values = await Future.wait([
        api.groupInfo(group.id),
        api.groupMembers(group.id),
      ]);
      if (!_current || detail?.id != group.id) return;
      detail = values[0] as MeshXGroup;
      members = values[1] as List<GroupMemberInfo>;
    } catch (failure) {
      if (_current && detail?.id == group.id) error = _describe(failure);
    } finally {
      if (_current && detail?.id == group.id) {
        loadingDetails = false;
        _notify();
      }
    }
  }

  Future<bool> create(String name, Iterable<int> memberIds) async {
    if (!_current) return false;
    if (creating) return _reject('群聊正在创建，请勿重复提交');
    final clean = name.trim();
    if (clean.length < 2 || clean.length > 20) {
      return _reject('群名称长度需为2-20字符');
    }
    final ids = memberIds.toSet();
    final allowed = friends.map((friend) => friend.userId).toSet();
    if (ids.length > 199 ||
        ids.contains(currentUserId) ||
        !allowed.containsAll(ids)) {
      return _reject('只能选择当前好友作为初始成员');
    }
    creating = true;
    error = null;
    notice = null;
    _notify();
    try {
      final group = await api.createGroup(clean, ids);
      if (!_current) return false;
      if (group.ownerId != currentUserId) {
        throw const FormatException('新群组的群主信息不匹配');
      }
      groups = [group, ...groups.where((item) => item.id != group.id)];
      await chat.openGroupConversation(group);
      if (!_current) return false;
      notice = '群聊已创建';
      return true;
    } catch (failure) {
      if (_current) error = _describe(failure);
      return false;
    } finally {
      if (_current) {
        creating = false;
        _notify();
      }
    }
  }

  Future<bool> open(MeshXGroup group) async {
    if (!_current) return false;
    await chat.openGroupConversation(group);
    return _current;
  }

  Future<bool> leave(MeshXGroup group) async {
    if (!_current) return false;
    if (!_leaving.add(group.id)) return _reject('退群操作正在进行，请勿重复提交');
    error = null;
    notice = null;
    _notify();
    var serverConfirmed = false;
    try {
      await api.leaveGroup(group.id);
      serverConfirmed = true;
      if (!_current) return false;
      groups = groups.where((item) => item.id != group.id).toList();
      if (detail?.id == group.id) {
        detail = null;
        members = const [];
      }
      try {
        await chat.confirmOwnGroupLeave(group.id);
        notice = '已退出群聊';
      } catch (failure) {
        error = '已退出群聊，但本地状态保存失败：${_describe(failure)}';
      }
      return true;
    } catch (failure) {
      if (_current) {
        error = serverConfirmed
            ? '已退出群聊，但本地状态更新失败：${_describe(failure)}'
            : _describe(failure);
      }
      return serverConfirmed;
    } finally {
      _leaving.remove(group.id);
      _notify();
    }
  }

  bool _reject(String message) {
    error = message;
    notice = null;
    _notify();
    return false;
  }

  String _describe(Object failure) => failure is ApiException
      ? failure.message
      : failure is FormatException
      ? failure.message
      : chat.describe(failure);

  void clearFeedback() {
    error = null;
    notice = null;
    _notify();
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
