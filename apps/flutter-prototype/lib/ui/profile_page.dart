import 'dart:async';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import '../application/platform_coordinator.dart';
import '../application/profile_controller.dart';
import '../chat_controller.dart';
import '../core/platform_ports.dart';
import '../data/meshx_api.dart';
import '../data/profile_models.dart';
import 'capabilities_page.dart';
import 'support_info_page.dart';

class ProfilePage extends StatefulWidget {
  const ProfilePage({
    super.key,
    required this.chat,
    required this.themeMode,
    required this.onThemeModeChanged,
    this.platform,
  });

  final ChatController chat;
  final PlatformCoordinator? platform;
  final ThemeMode themeMode;
  final Future<void> Function(ThemeMode) onThemeModeChanged;

  @override
  State<ProfilePage> createState() => _ProfilePageState();
}

class _ProfilePageState extends State<ProfilePage> {
  late final ProfileController controller;
  final nickname = TextEditingController();
  final oldPassword = TextEditingController();
  final newPassword = TextEditingController();
  final confirmation = TextEditingController();
  bool _profileSeeded = false;
  String? _themeError;
  late ThemeMode _selectedTheme;

  @override
  void initState() {
    super.initState();
    _selectedTheme = widget.themeMode;
    controller = ProfileController(
      chat: widget.chat,
      platform: widget.platform,
    );
    unawaited(controller.load());
  }

  @override
  void dispose() {
    nickname.dispose();
    oldPassword.dispose();
    newPassword.dispose();
    confirmation.dispose();
    controller.dispose();
    super.dispose();
  }

  Future<void> _changePassword() async {
    final oldValue = oldPassword.text;
    final newValue = newPassword.text;
    final confirmationValue = confirmation.text;
    oldPassword.clear();
    newPassword.clear();
    confirmation.clear();
    final changed = await controller.changePassword(
      oldPassword: oldValue,
      newPassword: newValue,
      confirmation: confirmationValue,
    );
    if (changed && mounted) {
      Navigator.of(context).popUntil((route) => route.isFirst);
    }
  }

  Future<void> _setTheme(ThemeMode mode) async {
    setState(() => _themeError = null);
    try {
      await widget.onThemeModeChanged(mode);
      if (mounted) setState(() => _selectedTheme = mode);
    } catch (_) {
      if (mounted) setState(() => _themeError = '主题偏好保存失败，请重试');
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('个人资料与设置')),
    body: SafeArea(
      child: AnimatedBuilder(
        animation: Listenable.merge([
          controller,
          if (widget.platform != null) widget.platform!,
        ]),
        builder: (context, _) {
          final profile = controller.profile;
          if (profile != null && !_profileSeeded) {
            nickname.text = profile.nickname;
            _profileSeeded = true;
          }
          return ListView(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 36),
            children: [
              if (controller.loading && profile == null)
                const Center(child: CircularProgressIndicator())
              else if (profile != null) ...[
                Center(
                  child: ProfileAvatar(
                    api: widget.chat.api!,
                    nickname: nickname.text.isEmpty
                        ? profile.nickname
                        : nickname.text,
                    avatar: controller.draftAvatar,
                    size: 88,
                  ),
                ),
                const SizedBox(height: 12),
                Text(
                  '@${profile.username}',
                  textAlign: TextAlign.center,
                  key: const Key('profile-username'),
                ),
                if (profile.signature.isNotEmpty)
                  Text(
                    profile.signature,
                    textAlign: TextAlign.center,
                    key: const Key('profile-signature'),
                  ),
                const SizedBox(height: 20),
                TextField(
                  key: const Key('profile-nickname'),
                  controller: nickname,
                  maxLength: 16,
                  textInputAction: TextInputAction.done,
                  onChanged: (_) => setState(() {}),
                  decoration: const InputDecoration(labelText: '昵称'),
                ),
                const SizedBox(height: 8),
                Text('文字头像', style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    for (final color in textAvatarColors)
                      Semantics(
                        label: '选择文字头像颜色 $color',
                        button: true,
                        child: InkWell(
                          key: ValueKey('avatar-color-$color'),
                          borderRadius: BorderRadius.circular(24),
                          onTap: controller.busy
                              ? null
                              : () => controller.chooseTextAvatar(
                                  nickname.text,
                                  color,
                                ),
                          child: Container(
                            width: 44,
                            height: 44,
                            decoration: BoxDecoration(
                              color: _hex(color),
                              shape: BoxShape.circle,
                            ),
                            alignment: Alignment.center,
                            child: Text(
                              _initial(nickname.text),
                              style: const TextStyle(
                                color: Colors.white,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
                const SizedBox(height: 12),
                OutlinedButton.icon(
                  key: const Key('upload-avatar'),
                  onPressed: widget.platform == null || controller.busy
                      ? null
                      : controller.chooseImageAvatar,
                  icon: const Icon(Icons.photo_outlined),
                  label: Text(
                    controller.uploading ? '正在上传…' : '选择图片头像（最大 5MB）',
                  ),
                ),
                FilledButton(
                  key: const Key('save-profile'),
                  onPressed: controller.busy
                      ? null
                      : () => controller.save(nickname.text),
                  child: Text(controller.saving ? '正在保存…' : '保存个人资料'),
                ),
              ],
              if (controller.error != null)
                _ProfileErrorNotice(
                  message: controller.error!,
                  onDismiss: controller.clearMessage,
                ),
              if (controller.notice != null)
                Semantics(
                  liveRegion: true,
                  child: Text(
                    controller.notice!,
                    key: const Key('profile-notice'),
                  ),
                ),
              const SizedBox(height: 28),
              Text('外观', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 8),
              SegmentedButton<ThemeMode>(
                key: const Key('theme-mode'),
                segments: const [
                  ButtonSegment(value: ThemeMode.system, label: Text('跟随系统')),
                  ButtonSegment(value: ThemeMode.light, label: Text('浅色')),
                  ButtonSegment(value: ThemeMode.dark, label: Text('深色')),
                ],
                selected: {_selectedTheme},
                onSelectionChanged: (value) => _setTheme(value.single),
              ),
              if (_themeError != null) Text(_themeError!),
              const SizedBox(height: 28),
              Text('节点与通知', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 8),
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.dns_outlined),
                title: Text(widget.chat.node?.name ?? '未知节点'),
                subtitle: Text(widget.chat.api?.origin.origin ?? '未连接'),
              ),
              if (widget.platform case final platform?) ...[
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(Icons.notifications_outlined),
                  title: const Text('消息提醒'),
                  subtitle: Text(
                    capabilityMessage(platform.notificationStatus.status),
                    key: const Key('profile-notification-status'),
                  ),
                ),
                OutlinedButton(
                  key: const Key('profile-capabilities'),
                  onPressed: () => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) => CapabilitiesPage(platform: platform),
                    ),
                  ),
                  child: const Text('管理设备权限'),
                ),
                OutlinedButton.icon(
                  key: const Key('profile-support-info'),
                  onPressed: () => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) => SupportInfoPage(
                        chat: widget.chat,
                        platform: platform,
                      ),
                    ),
                  ),
                  icon: const Icon(Icons.support_agent_outlined),
                  label: const Text('查看支持信息'),
                ),
              ],
              const SizedBox(height: 28),
              Text('安全', style: Theme.of(context).textTheme.titleLarge),
              const Text('修改密码后，服务端会撤销所有设备会话并要求重新登录。'),
              const SizedBox(height: 12),
              TextField(
                key: const Key('old-password'),
                controller: oldPassword,
                obscureText: true,
                enableSuggestions: false,
                autocorrect: false,
                decoration: const InputDecoration(labelText: '当前密码'),
              ),
              const SizedBox(height: 12),
              TextField(
                key: const Key('new-password'),
                controller: newPassword,
                obscureText: true,
                enableSuggestions: false,
                autocorrect: false,
                maxLength: 20,
                decoration: const InputDecoration(labelText: '新密码'),
              ),
              const SizedBox(height: 12),
              TextField(
                key: const Key('confirm-password'),
                controller: confirmation,
                obscureText: true,
                enableSuggestions: false,
                autocorrect: false,
                maxLength: 20,
                decoration: const InputDecoration(labelText: '确认新密码'),
              ),
              FilledButton(
                key: const Key('change-password'),
                onPressed: controller.busy ? null : _changePassword,
                child: Text(controller.changingPassword ? '正在修改…' : '修改密码'),
              ),
              const SizedBox(height: 20),
              OutlinedButton.icon(
                key: const Key('profile-logout'),
                onPressed: controller.busy
                    ? null
                    : () async {
                        await widget.chat.logout();
                        if (context.mounted) {
                          Navigator.of(
                            context,
                          ).popUntil((route) => route.isFirst);
                        }
                      },
                icon: const Icon(Icons.logout),
                label: const Text('退出登录'),
              ),
            ],
          );
        },
      ),
    ),
  );
}

class ProfileAvatar extends StatefulWidget {
  const ProfileAvatar({
    super.key,
    required this.api,
    required this.nickname,
    required this.avatar,
    this.size = 72,
  });

  final MeshXApi api;
  final String nickname, avatar;
  final double size;

  @override
  State<ProfileAvatar> createState() => _ProfileAvatarState();
}

class _ProfileAvatarState extends State<ProfileAvatar> {
  Future<Uint8List>? bytes;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  @override
  void didUpdateWidget(ProfileAvatar oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.avatar != widget.avatar || oldWidget.api != widget.api) {
      _refresh();
    }
  }

  void _refresh() {
    bytes = isTextAvatar(widget.avatar)
        ? null
        : widget.api.avatarBytes(widget.avatar);
  }

  @override
  Widget build(BuildContext context) {
    final avatar = widget.avatar;
    Widget child;
    if (isTextAvatar(avatar)) {
      final parts = avatar.split(':');
      final color = parts.length >= 3 ? _hex(parts[2]) : _hex('#5856D6');
      child = ColoredBox(
        color: color,
        child: Center(
          child: Text(
            _initial(widget.nickname),
            style: TextStyle(
              color: Colors.white,
              fontSize: widget.size * .38,
              fontWeight: FontWeight.w700,
            ),
          ),
        ),
      );
    } else {
      child = FutureBuilder<Uint8List>(
        future: bytes,
        builder: (context, snapshot) => snapshot.hasData
            ? Image.memory(
                snapshot.data!,
                fit: BoxFit.cover,
                errorBuilder: (_, _, _) => const Icon(Icons.person_outline),
              )
            : const Center(child: Icon(Icons.person_outline)),
      );
    }
    return Semantics(
      image: true,
      label: '${widget.nickname}的头像',
      child: ClipOval(
        child: SizedBox(width: widget.size, height: widget.size, child: child),
      ),
    );
  }
}

Color _hex(String value) {
  final clean = value.replaceFirst('#', '');
  final parsed = int.tryParse(clean, radix: 16);
  return parsed == null || clean.length != 6
      ? const Color(0xff5856d6)
      : Color(0xff000000 | parsed);
}

String _initial(String value) {
  final clean = value.trim();
  return clean.isEmpty
      ? '?'
      : String.fromCharCode(clean.runes.first).toUpperCase();
}

class _ProfileErrorNotice extends StatelessWidget {
  const _ProfileErrorNotice({required this.message, required this.onDismiss});
  final String message;
  final VoidCallback onDismiss;

  @override
  Widget build(BuildContext context) => Semantics(
    liveRegion: true,
    child: Row(
      children: [
        Icon(Icons.error_outline, color: Theme.of(context).colorScheme.error),
        const SizedBox(width: 8),
        Expanded(child: Text(message)),
        IconButton(
          tooltip: '关闭提示',
          onPressed: onDismiss,
          icon: const Icon(Icons.close),
        ),
      ],
    ),
  );
}
