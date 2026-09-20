import 'dart:async';
import 'package:flutter/foundation.dart';
import '../chat_controller.dart';
import '../core/platform_ports.dart';
import '../data/meshx_api.dart';
import '../data/profile_models.dart';
import 'platform_coordinator.dart';

class ProfileController extends ChangeNotifier {
  ProfileController({required this.chat, this.platform});

  final ChatController chat;
  final PlatformCoordinator? platform;
  UserProfile? profile;
  String draftAvatar = '';
  bool loading = false,
      saving = false,
      uploading = false,
      changingPassword = false;
  String? error, notice;
  int _generation = 0;
  bool _disposed = false;

  bool get busy => loading || saving || uploading || changingPassword;

  MeshXApi? get _api => chat.api;
  bool _current(MeshXApi api, int generation) =>
      !_disposed &&
      generation == _generation &&
      identical(_api, api) &&
      api.session?.userId == chat.session?.userId;

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  Future<void> load() async {
    final api = _api;
    if (api == null || busy) return;
    final generation = ++_generation;
    loading = true;
    error = null;
    notice = null;
    _notify();
    try {
      final value = await api.currentUser();
      if (!_current(api, generation)) return;
      profile = value;
      draftAvatar = value.avatar;
      await chat.applyProfile(value);
    } catch (failure) {
      if (_current(api, generation)) error = chat.describe(failure);
    } finally {
      if (_current(api, generation)) {
        loading = false;
        _notify();
      }
    }
  }

  void chooseTextAvatar(String nickname, String color) {
    if (busy) return;
    draftAvatar = textAvatar(nickname, color);
    error = null;
    notice = null;
    _notify();
  }

  Future<void> chooseImageAvatar() async {
    final api = _api, bridge = platform;
    if (api == null || bridge == null || busy) return;
    final generation = ++_generation;
    uploading = true;
    error = null;
    notice = null;
    _notify();
    try {
      await bridge.pickFile(maxBytes: 5 * 1024 * 1024);
      if (!_current(api, generation)) return;
      final file = bridge.selectedFile;
      if (file == null) {
        if (bridge.fileStatus.status != CapabilityStatus.cancelled) {
          error = capabilityMessage(bridge.fileStatus.status);
        }
        return;
      }
      if (!file.mime.toLowerCase().startsWith('image/')) {
        error = '头像必须是图片文件';
        return;
      }
      final content = await bridge.readSelectedFile(maxBytes: 5 * 1024 * 1024);
      if (!_current(api, generation)) return;
      if (!content.ok || content.value == null) {
        error = switch (content.reason) {
          'fileTooLarge' => '头像图片不能超过 5MB',
          'fileMissing' ||
          'fileUnavailable' ||
          'fileChanged' => '所选头像已不可读取，请重新选择',
          _ => capabilityMessage(content.status),
        };
        return;
      }
      final uploaded = await api.uploadAvatar(
        name: file.name,
        mime: file.mime,
        bytes: content.value!,
      );
      if (!_current(api, generation)) return;
      draftAvatar = uploaded.profileValue;
      notice = '头像已上传，保存资料后生效';
    } catch (failure) {
      if (_current(api, generation)) error = chat.describe(failure);
    } finally {
      await bridge.clearFile();
      if (_current(api, generation)) {
        uploading = false;
        _notify();
      }
    }
  }

  Future<bool> save(String nickname) async {
    final api = _api, current = profile;
    if (api == null || current == null || busy) return false;
    final clean = nickname.trim();
    if (clean.isEmpty || clean.length > 16) {
      error = '昵称长度需为1-16字符';
      notice = null;
      _notify();
      return false;
    }
    var avatar = draftAvatar;
    if (isTextAvatar(avatar)) {
      final parts = avatar.split(':');
      avatar = textAvatar(clean, parts.length >= 3 ? parts[2] : '#5856D6');
    }
    final generation = ++_generation;
    saving = true;
    error = null;
    notice = null;
    _notify();
    try {
      final updated = await api.updateProfile(nickname: clean, avatar: avatar);
      if (!_current(api, generation)) return false;
      await chat.applyProfile(updated);
      if (!_current(api, generation)) return false;
      profile = updated;
      draftAvatar = updated.avatar;
      notice = '个人资料已保存';
      return true;
    } catch (failure) {
      if (_current(api, generation)) error = chat.describe(failure);
      return false;
    } finally {
      if (_current(api, generation)) {
        saving = false;
        _notify();
      }
    }
  }

  Future<bool> changePassword({
    required String oldPassword,
    required String newPassword,
    required String confirmation,
  }) async {
    final api = _api;
    if (api == null || busy) return false;
    if (oldPassword.isEmpty) return _passwordError('请输入当前密码');
    if (newPassword.length < 8 ||
        newPassword.length > 20 ||
        !RegExp('[A-Za-z]').hasMatch(newPassword) ||
        !RegExp(r'\d').hasMatch(newPassword)) {
      return _passwordError('新密码需为8-20位，并同时包含字母和数字');
    }
    if (newPassword != confirmation) return _passwordError('两次输入的新密码不一致');
    if (newPassword == oldPassword) return _passwordError('新密码不能与当前密码相同');
    final generation = ++_generation;
    changingPassword = true;
    error = null;
    notice = null;
    _notify();
    try {
      await api.changePassword(oldPassword, newPassword);
      if (!_current(api, generation)) return false;
      await chat.logout(revokeRemote: false, reason: '密码已修改，请重新登录');
      return true;
    } catch (failure) {
      if (_current(api, generation)) error = chat.describe(failure);
      return false;
    } finally {
      if (_current(api, generation)) {
        changingPassword = false;
        _notify();
      }
    }
  }

  bool _passwordError(String message) {
    error = message;
    notice = null;
    _notify();
    return false;
  }

  void clearMessage() {
    error = null;
    notice = null;
    _notify();
  }

  @override
  void dispose() {
    _disposed = true;
    ++_generation;
    super.dispose();
  }
}
