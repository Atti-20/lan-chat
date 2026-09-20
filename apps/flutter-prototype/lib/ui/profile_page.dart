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
import 'components/meshx_avatar.dart';
import 'glass_chrome.dart';
import 'theme.dart';
import 'tokens.g.dart';

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
  final _nicknameFocus = FocusNode();
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
    _nicknameFocus.dispose();
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

  void _focusProfileEditor() {
    FocusScope.of(context).requestFocus(_nicknameFocus);
  }

  @override
  Widget build(BuildContext context) => Scaffold(
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
            padding: EdgeInsets.fromLTRB(
              meshXSizes['spacing.5']!,
              meshXSizes['spacing.2']!,
              meshXSizes['spacing.5']!,
              meshXSizes['spacing.8']!,
            ),
            children: [
              _ProfileHeader(
                spaceName: widget.chat.node?.name ?? 'MeshX',
                online: widget.chat.online,
                onBack: () => Navigator.maybePop(context),
                onEdit: profile == null ? null : _focusProfileEditor,
              ),
              SizedBox(height: meshXSizes['spacing.5']!),
              if (controller.loading && profile == null)
                const Padding(
                  padding: EdgeInsets.all(32),
                  child: Center(child: CircularProgressIndicator()),
                )
              else if (profile != null) ...[
                _ProfileIdentityCard(
                  api: widget.chat.api!,
                  nickname: nickname.text.isEmpty
                      ? profile.nickname
                      : nickname.text,
                  username: profile.username,
                  signature: profile.signature,
                  avatar: controller.draftAvatar,
                ),
                SizedBox(height: meshXSizes['spacing.6']!),
                const _ProfileSectionTitle('编辑资料'),
                SizedBox(height: meshXSizes['spacing.3']!),
                _ProfileCard(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      TextField(
                        key: const Key('profile-nickname'),
                        controller: nickname,
                        focusNode: _nicknameFocus,
                        maxLength: 16,
                        textInputAction: TextInputAction.done,
                        onChanged: (_) => setState(() {}),
                        decoration: const InputDecoration(labelText: '昵称'),
                      ),
                      SizedBox(height: meshXSizes['spacing.2']!),
                      Text(
                        '文字头像',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      SizedBox(height: meshXSizes['spacing.2']!),
                      Wrap(
                        spacing: meshXSizes['spacing.2']!,
                        runSpacing: meshXSizes['spacing.2']!,
                        children: [
                          for (final color in textAvatarColors)
                            Semantics(
                              label: '选择文字头像颜色 $color',
                              button: true,
                              child: InkWell(
                                key: ValueKey('avatar-color-$color'),
                                borderRadius: BorderRadius.circular(
                                  meshXSizes['size.control.default']! / 2,
                                ),
                                onTap: controller.busy
                                    ? null
                                    : () => controller.chooseTextAvatar(
                                        nickname.text,
                                        color,
                                      ),
                                child: Container(
                                  width: meshXSizes['size.control.default']!,
                                  height: meshXSizes['size.control.default']!,
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
                      SizedBox(height: meshXSizes['spacing.3']!),
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
                  ),
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
              SizedBox(height: meshXSizes['spacing.6']!),
              const _ProfileSectionTitle('外观'),
              SizedBox(height: meshXSizes['spacing.3']!),
              _ProfileCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    SegmentedButton<ThemeMode>(
                      key: const Key('theme-mode'),
                      segments: const [
                        ButtonSegment(
                          value: ThemeMode.system,
                          label: Text('跟随系统'),
                        ),
                        ButtonSegment(
                          value: ThemeMode.light,
                          label: Text('浅色'),
                        ),
                        ButtonSegment(value: ThemeMode.dark, label: Text('深色')),
                      ],
                      selected: {_selectedTheme},
                      onSelectionChanged: (value) => _setTheme(value.single),
                    ),
                    if (_themeError != null) ...[
                      SizedBox(height: meshXSizes['spacing.2']!),
                      Text(
                        _themeError!,
                        style: TextStyle(
                          color: palette(context)['color.status.danger'],
                        ),
                      ),
                    ],
                  ],
                ),
              ),
              SizedBox(height: meshXSizes['spacing.6']!),
              const _ProfileSectionTitle('节点与通知'),
              SizedBox(height: meshXSizes['spacing.3']!),
              _ProfileCard(
                child: _ProfileSettingsRow(
                  icon: Icons.dns_outlined,
                  title: '节点与连接',
                  subtitle:
                      '${widget.chat.api?.origin.origin ?? '未连接'} · ${widget.chat.online ? '已连接' : '离线'}',
                ),
              ),
              if (widget.platform case final platform?) ...[
                SizedBox(height: meshXSizes['spacing.2']!),
                _ProfileCard(
                  child: _ProfileSettingsRow(
                    key: const Key('profile-capabilities'),
                    icon: Icons.notifications_outlined,
                    title: '通知与系统权限',
                    subtitle: capabilityMessage(
                      platform.notificationStatus.status,
                    ),
                    subtitleKey: const Key('profile-notification-status'),
                    onTap: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) => CapabilitiesPage(platform: platform),
                      ),
                    ),
                  ),
                ),
                SizedBox(height: meshXSizes['spacing.2']!),
                _ProfileCard(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '后台推送',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      SizedBox(height: meshXSizes['spacing.1']!),
                      Text(
                        capabilityMessage(platform.pushStatus.status),
                        style: TextStyle(
                          color: palette(context)['color.text.secondary'],
                          fontSize: meshXSizes['typography.caption.size']!,
                        ),
                      ),
                      if (platform.pushStatus.reason.isNotEmpty) ...[
                        SizedBox(height: meshXSizes['spacing.2']!),
                        const Text('需要节点配置 FCM/APNs 和有效设备权限；未配置时不会启用。'),
                      ],
                      SizedBox(height: meshXSizes['spacing.3']!),
                      Wrap(
                        spacing: meshXSizes['spacing.2']!,
                        runSpacing: meshXSizes['spacing.2']!,
                        children: [
                          OutlinedButton(
                            onPressed: platform.pushBusy
                                ? null
                                : () => platform.configurePush(),
                            child: const Text('启用后台推送'),
                          ),
                          TextButton(
                            onPressed: platform.pushBusy
                                ? null
                                : platform.disablePush,
                            child: const Text('关闭后台推送'),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
                SizedBox(height: meshXSizes['spacing.2']!),
                _ProfileCard(
                  child: _ProfileSettingsRow(
                    key: const Key('profile-support-info'),
                    icon: Icons.support_agent_outlined,
                    title: '设备与支持信息',
                    subtitle: '当前设备 / 脱敏诊断',
                    onTap: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) => SupportInfoPage(
                          chat: widget.chat,
                          platform: platform,
                        ),
                      ),
                    ),
                  ),
                ),
              ],
              SizedBox(height: meshXSizes['spacing.6']!),
              const _ProfileSectionTitle('安全'),
              SizedBox(height: meshXSizes['spacing.3']!),
              _ProfileCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Text('修改密码后，服务端会撤销所有设备会话并要求重新登录。'),
                    SizedBox(height: meshXSizes['spacing.3']!),
                    TextField(
                      key: const Key('old-password'),
                      controller: oldPassword,
                      obscureText: true,
                      enableSuggestions: false,
                      autocorrect: false,
                      decoration: const InputDecoration(labelText: '当前密码'),
                    ),
                    SizedBox(height: meshXSizes['spacing.3']!),
                    TextField(
                      key: const Key('new-password'),
                      controller: newPassword,
                      obscureText: true,
                      enableSuggestions: false,
                      autocorrect: false,
                      maxLength: 20,
                      decoration: const InputDecoration(labelText: '新密码'),
                    ),
                    SizedBox(height: meshXSizes['spacing.3']!),
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
                      child: Text(
                        controller.changingPassword ? '正在修改…' : '修改密码',
                      ),
                    ),
                  ],
                ),
              ),
              SizedBox(height: meshXSizes['spacing.3']!),
              _ProfileCard(
                child: OutlinedButton.icon(
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
              ),
            ],
          );
        },
      ),
    ),
  );
}

class _ProfileHeader extends StatelessWidget {
  const _ProfileHeader({
    required this.spaceName,
    required this.online,
    required this.onBack,
    this.onEdit,
  });

  final String spaceName;
  final bool online;
  final VoidCallback onBack;
  final VoidCallback? onEdit;

  @override
  Widget build(BuildContext context) {
    final colors = palette(context);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        MeshXGlassButton(
          tooltip: '返回',
          nativeSymbol: 'chevron.left',
          onPressed: onBack,
          icon: const Icon(Icons.chevron_left),
        ),
        SizedBox(width: meshXSizes['spacing.3']!),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Semantics(
                label: online ? '$spaceName，已连接' : '$spaceName，离线',
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        spaceName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: colors['color.text.secondary'],
                          fontSize: meshXSizes['typography.caption.size']!,
                        ),
                      ),
                    ),
                    Container(
                      width: meshXSizes['spacing.2']!,
                      height: meshXSizes['spacing.2']!,
                      margin: EdgeInsets.only(left: meshXSizes['spacing.2']!),
                      decoration: BoxDecoration(
                        color:
                            colors[online
                                ? 'color.presence.online'
                                : 'color.status.warning'],
                        shape: BoxShape.circle,
                      ),
                    ),
                  ],
                ),
              ),
              SizedBox(height: meshXSizes['spacing.1']!),
              Text('我的', style: Theme.of(context).textTheme.headlineMedium),
            ],
          ),
        ),
        if (onEdit != null)
          MeshXGlassButton(
            key: const Key('profile-focus-editor'),
            tooltip: '编辑个人资料',
            nativeSymbol: 'pencil',
            onPressed: onEdit,
            icon: const Icon(Icons.edit_outlined),
          ),
      ],
    );
  }
}

class _ProfileIdentityCard extends StatelessWidget {
  const _ProfileIdentityCard({
    required this.api,
    required this.nickname,
    required this.username,
    required this.signature,
    required this.avatar,
  });

  final MeshXApi api;
  final String nickname;
  final String username;
  final String signature;
  final String avatar;

  @override
  Widget build(BuildContext context) => _ProfileCard(
    child: Row(
      children: [
        ProfileAvatar(
          api: api,
          nickname: nickname,
          avatar: avatar,
          size: meshXSizes['size.control.default']! * 1.5,
        ),
        SizedBox(width: meshXSizes['spacing.4']!),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(nickname, style: Theme.of(context).textTheme.titleLarge),
              SizedBox(height: meshXSizes['spacing.1']!),
              Text(
                '@$username',
                key: const Key('profile-username'),
                style: TextStyle(
                  color: palette(context)['color.text.secondary'],
                  fontSize: meshXSizes['typography.body.size']!,
                ),
              ),
              if (signature.isNotEmpty) ...[
                SizedBox(height: meshXSizes['spacing.1']!),
                Text(
                  signature,
                  key: const Key('profile-signature'),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: palette(context)['color.text.secondary'],
                    fontSize: meshXSizes['typography.caption.size']!,
                  ),
                ),
              ],
            ],
          ),
        ),
      ],
    ),
  );
}

class _ProfileSectionTitle extends StatelessWidget {
  const _ProfileSectionTitle(this.text);
  final String text;

  @override
  Widget build(BuildContext context) =>
      Text(text, style: Theme.of(context).textTheme.titleLarge);
}

class _ProfileCard extends StatelessWidget {
  const _ProfileCard({required this.child});
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final colors = palette(context);
    final radius = BorderRadius.circular(meshXSizes['shape.radius.large']!);
    return Material(
      color: colors['color.background.muted'],
      borderRadius: radius,
      child: Ink(
        padding: EdgeInsets.all(meshXSizes['spacing.4']!),
        decoration: BoxDecoration(
          borderRadius: radius,
          border: Border.all(color: colors['color.border.default']!),
        ),
        child: child,
      ),
    );
  }
}

class _ProfileSettingsRow extends StatelessWidget {
  const _ProfileSettingsRow({
    super.key,
    required this.icon,
    required this.title,
    required this.subtitle,
    this.subtitleKey,
    this.onTap,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final Key? subtitleKey;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final colors = palette(context);
    final row = Row(
      children: [
        Icon(icon, color: colors['color.text.secondary']),
        SizedBox(width: meshXSizes['spacing.3']!),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: Theme.of(context).textTheme.titleMedium),
              SizedBox(height: meshXSizes['spacing.1']!),
              Text(
                subtitle,
                key: subtitleKey,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: colors['color.text.tertiary'],
                  fontSize: meshXSizes['typography.caption.size']!,
                ),
              ),
            ],
          ),
        ),
        if (onTap != null)
          Icon(
            Icons.chevron_right,
            color: colors['color.text.tertiary'],
            size: meshXSizes['size.icon.medium']!,
          ),
      ],
    );
    if (onTap == null) return row;
    return Semantics(
      button: true,
      label: title,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(meshXSizes['shape.radius.medium']!),
        child: row,
      ),
    );
  }
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
    if (isTextAvatar(avatar)) {
      final parts = avatar.split(':');
      final color = parts.length >= 3 ? _hex(parts[2]) : _hex('#5856D6');
      return MeshXAvatar(
        label: widget.nickname,
        size: widget.size,
        backgroundColor: color,
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
    }
    return MeshXAvatar(
      label: widget.nickname,
      size: widget.size,
      child: FutureBuilder<Uint8List>(
        key: ValueKey((widget.api, widget.api.session?.userId, avatar)),
        future: bytes,
        builder: (context, snapshot) => snapshot.hasData
            ? Image.memory(
                snapshot.data!,
                fit: BoxFit.cover,
                errorBuilder: (_, _, _) =>
                    const Center(child: Icon(Icons.person_outline)),
              )
            : const Center(child: Icon(Icons.person_outline)),
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
