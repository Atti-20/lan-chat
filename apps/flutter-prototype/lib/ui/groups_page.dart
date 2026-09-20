import 'dart:async';
import 'package:flutter/material.dart';
import '../application/groups_controller.dart';
import '../chat_controller.dart';
import '../data/groups_models.dart';
import '../data/meshx_api.dart';
import 'glass_chrome.dart';
import 'theme.dart';
import 'tokens.g.dart';
import 'profile_page.dart';
import 'components/meshx_avatar.dart';

class GroupsPage extends StatefulWidget {
  const GroupsPage({
    super.key,
    required this.chat,
    this.onConversationOpened,
    this.onOpenProfile,
  });
  final ChatController chat;
  final VoidCallback? onConversationOpened;
  final VoidCallback? onOpenProfile;

  @override
  State<GroupsPage> createState() => _GroupsPageState();
}

class _GroupsPageState extends State<GroupsPage> {
  late final GroupsController controller;
  final _search = TextEditingController();
  String _query = '';

  @override
  void initState() {
    super.initState();
    controller = GroupsController(chat: widget.chat);
    unawaited(controller.start());
  }

  @override
  void dispose() {
    _search.dispose();
    controller.dispose();
    super.dispose();
  }

  List<MeshXGroup> get _filteredGroups {
    final needle = _query.trim().toLowerCase();
    if (needle.isEmpty) return controller.groups;
    return controller.groups
        .where((group) => group.name.toLowerCase().contains(needle))
        .toList();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: controller,
    builder: (context, _) {
      final colors = palette(context);
      final groups = _filteredGroups;
      final horizontal = meshXSizes['spacing.5']!;
      final bottomReservation =
          meshXSizes['component.glass.navigation-min-height']! +
          meshXSizes['component.glass.navigation-inset']! * 2;
      return Scaffold(
        body: SafeArea(
          bottom: false,
          child: RefreshIndicator(
            onRefresh: controller.refresh,
            child: ListView(
              key: const Key('groups-list'),
              physics: const AlwaysScrollableScrollPhysics(),
              padding: EdgeInsets.fromLTRB(
                horizontal,
                meshXSizes['spacing.2']!,
                horizontal,
                bottomReservation,
              ),
              children: [
                _GroupsHeader(
                  spaceName: controller.chat.node?.name ?? 'MeshX',
                  online: controller.chat.online,
                  onOpenProfile: widget.onOpenProfile,
                ),
                SizedBox(height: meshXSizes['spacing.5']!),
                TextField(
                  key: const Key('group-search'),
                  controller: _search,
                  onChanged: (value) => setState(() => _query = value),
                  textInputAction: TextInputAction.search,
                  decoration: const InputDecoration(
                    hintText: '搜索群名称',
                    prefixIcon: Icon(Icons.search),
                  ),
                ),
                SizedBox(height: meshXSizes['spacing.3']!),
                SizedBox(
                  width: double.infinity,
                  child: OutlinedButton.icon(
                    key: const Key('create-group'),
                    onPressed: controller.busy ? null : _create,
                    icon: const Icon(Icons.group_add_outlined),
                    label: const Text('创建群聊'),
                  ),
                ),
                SizedBox(height: meshXSizes['spacing.6']!),
                if (controller.error != null)
                  _GroupNotice(
                    message: controller.error!,
                    error: true,
                    onDismiss: controller.clearFeedback,
                  ),
                if (controller.notice != null)
                  _GroupNotice(
                    message: controller.notice!,
                    onDismiss: controller.clearFeedback,
                  ),
                if (controller.loading)
                  const Padding(
                    padding: EdgeInsets.only(bottom: 12),
                    child: LinearProgressIndicator(),
                  ),
                Text(
                  _query.trim().isEmpty ? '我的群聊' : '搜索结果',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                SizedBox(height: meshXSizes['spacing.3']!),
                if (controller.groups.isEmpty)
                  _GroupsEmptyState(
                    message: '暂无群聊，可从好友创建一个群聊',
                    color: colors['ink-soft']!,
                  )
                else if (groups.isEmpty)
                  _GroupsEmptyState(
                    key: const Key('group-search-empty'),
                    message: '没有匹配的群聊',
                    color: colors['ink-soft']!,
                  )
                else
                  ...groups.map(
                    (group) => Padding(
                      padding: EdgeInsets.only(
                        bottom: meshXSizes['spacing.3']!,
                      ),
                      child: _GroupDirectoryCard(
                        key: ValueKey('group-${group.id}'),
                        group: group,
                        api: controller.api,
                        colors: colors,
                        onTap: () => _details(group),
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ),
      );
    },
  );

  Future<void> _create() async {
    var name = '';
    final selected = <int>{};
    final submitted = await showDialog<({String name, Set<int> members})>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: const Text('创建群聊'),
          content: SizedBox(
            width: 420,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  TextFormField(
                    key: const Key('group-name'),
                    autofocus: true,
                    maxLength: 20,
                    onChanged: (value) => name = value,
                    decoration: const InputDecoration(
                      labelText: '群聊名称（2-20字符）',
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text('邀请好友 · 已选 ${selected.length} 人'),
                  if (controller.friends.isEmpty)
                    const Padding(
                      padding: EdgeInsets.only(top: 12),
                      child: Text('暂无好友，也可以先创建只有自己的群聊。'),
                    ),
                  for (final friend in controller.friends)
                    CheckboxListTile(
                      key: ValueKey('group-friend-${friend.userId}'),
                      contentPadding: EdgeInsets.zero,
                      value: selected.contains(friend.userId),
                      title: Text(friend.displayName),
                      onChanged: (checked) => setDialogState(() {
                        checked == true
                            ? selected.add(friend.userId)
                            : selected.remove(friend.userId);
                      }),
                    ),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: const Text('取消'),
            ),
            FilledButton(
              key: const Key('submit-group'),
              onPressed: () => Navigator.pop(dialogContext, (
                name: name,
                members: Set<int>.from(selected),
              )),
              child: const Text('创建'),
            ),
          ],
        ),
      ),
    );
    if (submitted == null) return;
    final ok = await controller.create(submitted.name, submitted.members);
    if (!ok || !mounted) return;
    if (widget.onConversationOpened != null) {
      widget.onConversationOpened!();
    } else {
      Navigator.of(context).pop();
    }
  }

  Future<void> _details(MeshXGroup group) async {
    unawaited(controller.loadDetails(group));
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => GroupDetailsPage(
          controller: controller,
          group: group,
          onConversationOpened: widget.onConversationOpened,
        ),
      ),
    );
  }
}

class _GroupsHeader extends StatelessWidget {
  const _GroupsHeader({
    required this.spaceName,
    required this.online,
    this.onOpenProfile,
  });

  final String spaceName;
  final bool online;
  final VoidCallback? onOpenProfile;

  @override
  Widget build(BuildContext context) {
    final colors = palette(context);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
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
                          fontSize: meshXSizes['typography.caption.size']!,
                          color: colors['color.text.secondary'],
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
              Text('群聊', style: Theme.of(context).textTheme.headlineMedium),
            ],
          ),
        ),
        if (onOpenProfile != null)
          MeshXGlassButton(
            key: const Key('groups-open-profile'),
            tooltip: '打开个人资料与设置',
            nativeSymbol: 'person.crop.circle',
            onPressed: onOpenProfile,
            icon: const Icon(Icons.account_circle_outlined),
          ),
      ],
    );
  }
}

class _GroupsEmptyState extends StatelessWidget {
  const _GroupsEmptyState({
    super.key,
    required this.message,
    required this.color,
  });

  final String message;
  final Color color;

  @override
  Widget build(BuildContext context) => Padding(
    padding: EdgeInsets.symmetric(vertical: meshXSizes['spacing.10']!),
    child: Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            Icons.groups_outlined,
            size: meshXSizes['size.icon.large']!,
            color: color,
          ),
          SizedBox(height: meshXSizes['spacing.3']!),
          Text(message, textAlign: TextAlign.center),
        ],
      ),
    ),
  );
}

class _GroupDirectoryCard extends StatelessWidget {
  const _GroupDirectoryCard({
    super.key,
    required this.group,
    required this.api,
    required this.colors,
    required this.onTap,
  });

  final MeshXGroup group;
  final MeshXApi api;
  final Map<String, Color> colors;
  final VoidCallback onTap;

  String get _summary {
    if (group.announcement.isNotEmpty) return group.announcement;
    if (group.lastMessage.isNotEmpty) return group.lastMessage;
    return '查看成员与进入群聊';
  }

  @override
  Widget build(BuildContext context) {
    final radius = BorderRadius.circular(meshXSizes['shape.radius.large']!);
    return Semantics(
      button: true,
      label: '打开群聊 ${group.name}',
      child: Material(
        color: colors['color.background.muted'],
        borderRadius: radius,
        child: InkWell(
          onTap: onTap,
          borderRadius: radius,
          child: Ink(
            padding: EdgeInsets.all(meshXSizes['spacing.4']!),
            decoration: BoxDecoration(
              borderRadius: radius,
              border: Border.all(color: colors['color.border.default']!),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                group.avatar.isEmpty
                    ? MeshXAvatar(
                        label: group.name,
                        size: meshXSizes['size.control.default'],
                        icon: Icons.group_outlined,
                      )
                    : ProfileAvatar(
                        api: api,
                        nickname: group.name,
                        avatar: group.avatar,
                        size: meshXSizes['size.control.default']!,
                      ),
                SizedBox(width: meshXSizes['spacing.3']!),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        group.name,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      SizedBox(height: meshXSizes['spacing.1']!),
                      Text(
                        _summary,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: colors['color.text.secondary'],
                          fontSize: meshXSizes['typography.caption.size']!,
                        ),
                      ),
                      SizedBox(height: meshXSizes['spacing.3']!),
                      Text(
                        '成员上限 ${group.maxMembers} 人 · 查看详情',
                        style: TextStyle(
                          color: colors['color.action.text'],
                          fontSize: meshXSizes['typography.body-small.size']!,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ],
                  ),
                ),
                Padding(
                  padding: EdgeInsets.only(top: meshXSizes['spacing.5']!),
                  child: Icon(
                    Icons.arrow_forward,
                    color: colors['color.action.text'],
                    size: meshXSizes['size.icon.medium']!,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class GroupDetailsPage extends StatelessWidget {
  const GroupDetailsPage({
    super.key,
    required this.controller,
    required this.group,
    this.onConversationOpened,
  });
  final GroupsController controller;
  final MeshXGroup group;
  final VoidCallback? onConversationOpened;

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: controller,
    builder: (context, _) {
      final current = controller.detail?.id == group.id
          ? controller.detail!
          : group;
      final owner = current.ownerId == controller.currentUserId;
      final blocked = controller.busy || controller.managing;
      return Scaffold(
        appBar: AppBar(title: Text(current.name)),
        body: SafeArea(
          top: false,
          child: ListView(
            key: const Key('group-details'),
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 28),
            children: [
              if (controller.error != null)
                _GroupNotice(
                  message: controller.error!,
                  error: true,
                  onDismiss: controller.clearFeedback,
                ),
              if (controller.loadingDetails) const LinearProgressIndicator(),
              Text(
                current.announcement.isEmpty ? '暂无群公告' : current.announcement,
                style: TextStyle(color: palette(context)['ink-soft']),
              ),
              const SizedBox(height: 16),
              FilledButton.icon(
                key: const Key('open-group-chat'),
                onPressed: controller.busy
                    ? null
                    : () async {
                        if (await controller.open(current) && context.mounted) {
                          if (onConversationOpened != null) {
                            Navigator.of(context).pop();
                            onConversationOpened!();
                          } else {
                            Navigator.of(
                              context,
                            ).popUntil((route) => route.isFirst);
                          }
                        }
                      },
                icon: const Icon(Icons.chat_bubble_outline),
                label: const Text('进入群聊'),
              ),
              if (controller.canManage)
                Wrap(
                  spacing: 8,
                  children: [
                    OutlinedButton(
                      onPressed: blocked ? null : () => _edit(context, current),
                      child: const Text('编辑群资料'),
                    ),
                    OutlinedButton(
                      onPressed: blocked ? null : () => _invite(context),
                      child: const Text('邀请好友'),
                    ),
                    if (controller.isOwner)
                      OutlinedButton(
                        onPressed: blocked
                            ? null
                            : () => _act(context, 'dissolve', '解散群聊'),
                        child: const Text('解散群聊'),
                      ),
                  ],
                ),
              const SizedBox(height: 24),
              Text(
                '群成员 · ${controller.members.length}',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              if (!controller.loadingDetails && controller.members.isEmpty)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 20),
                  child: Text('未能读取群成员，可下拉返回后重试。'),
                ),
              for (final member in controller.members)
                ListTile(
                  key: ValueKey('group-member-${member.userId}'),
                  contentPadding: EdgeInsets.zero,
                  leading: ProfileAvatar(
                    api: controller.api,
                    nickname: member.displayName,
                    avatar: member.avatar,
                    size: 40,
                  ),
                  title: Text(member.displayName),
                  subtitle: Text(member.online ? '在线' : '离线'),
                  trailing:
                      !controller.canManage ||
                          member.userId == controller.currentUserId ||
                          member.role >= controller.role
                      ? Text(member.roleLabel)
                      : PopupMenuButton<String>(
                          enabled: !blocked,
                          tooltip: '管理${member.displayName}',
                          onSelected: (value) =>
                              _act(context, value, switch (value) {
                                'remove' => '移出群聊',
                                'admin' => member.role == 1 ? '取消管理员' : '设为管理员',
                                'transfer' => '转让群主',
                                'unmute' => '解除禁言',
                                _ => '禁言1小时',
                              }, member: member),
                          itemBuilder: (_) => [
                            const PopupMenuItem(
                              value: 'remove',
                              child: Text('移出群聊'),
                            ),
                            const PopupMenuItem(
                              value: 'mute',
                              child: Text('禁言1小时'),
                            ),
                            const PopupMenuItem(
                              value: 'unmute',
                              child: Text('解除禁言'),
                            ),
                            if (controller.isOwner)
                              PopupMenuItem(
                                value: 'admin',
                                child: Text(
                                  member.role == 1 ? '取消管理员' : '设为管理员',
                                ),
                              ),
                            if (controller.isOwner)
                              const PopupMenuItem(
                                value: 'transfer',
                                child: Text('转让群主'),
                              ),
                          ],
                        ),
                ),
              const SizedBox(height: 24),
              if (owner)
                const Text('群主可通过成员菜单转让群主后退出，也可解散群聊。')
              else
                OutlinedButton.icon(
                  key: const Key('leave-group'),
                  onPressed: controller.leaving(group.id)
                      ? null
                      : () => _confirmLeave(context, current),
                  icon: const Icon(Icons.exit_to_app),
                  label: Text(controller.leaving(group.id) ? '正在退出…' : '退出群聊'),
                ),
            ],
          ),
        ),
      );
    },
  );

  Future<void> _edit(BuildContext context, MeshXGroup group) async {
    final name = TextEditingController(text: group.name),
        announcement = TextEditingController(text: group.announcement);
    final values = await showDialog<(String, String)>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('编辑群资料'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: name,
                maxLength: 20,
                decoration: const InputDecoration(labelText: '群名称'),
              ),
              TextField(
                controller: announcement,
                maxLength: 500,
                maxLines: 4,
                decoration: const InputDecoration(labelText: '群公告'),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, (name.text, announcement.text)),
            child: const Text('保存'),
          ),
        ],
      ),
    );
    name.dispose();
    announcement.dispose();
    if (values != null && context.mounted) {
      await controller.manage(
        'update',
        name: values.$1,
        announcement: values.$2,
      );
    }
  }

  Future<void> _invite(BuildContext context) async {
    final selected = <int>{},
        existing = controller.members.map((m) => m.userId).toSet();
    final result = await showDialog<List<int>>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: const Text('邀请好友'),
          content: SizedBox(
            width: 360,
            height: 320,
            child: ListView(
              children: [
                for (final f in controller.friends.where(
                  (f) => !existing.contains(f.userId),
                ))
                  CheckboxListTile(
                    title: Text(f.displayName),
                    value: selected.contains(f.userId),
                    onChanged: (yes) => setState(() {
                      if (yes == true) {
                        selected.add(f.userId);
                      } else {
                        selected.remove(f.userId);
                      }
                    }),
                  ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: selected.isEmpty
                  ? null
                  : () => Navigator.pop(ctx, selected.toList()),
              child: const Text('邀请'),
            ),
          ],
        ),
      ),
    );
    if (result != null && context.mounted) {
      await controller.manage('invite', invite: result);
    }
  }

  Future<void> _act(
    BuildContext context,
    String action,
    String label, {
    GroupMemberInfo? member,
  }) async {
    final yes = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('$label？'),
        content: Text(member?.displayName ?? group.name),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('确定'),
          ),
        ],
      ),
    );
    if (yes != true || !context.mounted) return;
    final ok = await controller.manage(action, member: member);
    if (ok && action == 'dissolve' && context.mounted) Navigator.pop(context);
  }

  Future<void> _confirmLeave(BuildContext context, MeshXGroup value) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text('退出“${value.name}”？'),
        content: const Text('退出成功后，本机将清理该群的会话缓存和待发消息。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('取消'),
          ),
          FilledButton(
            key: const Key('confirm-leave-group'),
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('退出'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    final ok = await controller.leave(value);
    if (ok && context.mounted) Navigator.of(context).pop();
  }
}

class _GroupNotice extends StatelessWidget {
  const _GroupNotice({
    required this.message,
    required this.onDismiss,
    this.error = false,
  });
  final String message;
  final VoidCallback onDismiss;
  final bool error;

  @override
  Widget build(BuildContext context) => Semantics(
    liveRegion: true,
    child: Padding(
      padding: const EdgeInsets.fromLTRB(16, 10, 8, 2),
      child: Row(
        children: [
          Expanded(
            child: Text(
              message,
              style: TextStyle(
                color: error ? palette(context)['danger'] : null,
              ),
            ),
          ),
          IconButton(
            tooltip: '关闭提示',
            onPressed: onDismiss,
            icon: const Icon(Icons.close),
          ),
        ],
      ),
    ),
  );
}
