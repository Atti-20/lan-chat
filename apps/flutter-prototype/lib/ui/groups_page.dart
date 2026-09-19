import 'dart:async';
import 'package:flutter/material.dart';
import '../application/groups_controller.dart';
import '../chat_controller.dart';
import '../data/groups_models.dart';
import 'theme.dart';

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

  @override
  void initState() {
    super.initState();
    controller = GroupsController(chat: widget.chat);
    unawaited(controller.start());
  }

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: controller,
    builder: (context, _) => Scaffold(
      appBar: AppBar(
        title: const Text('群聊'),
        actions: [
          if (widget.onOpenProfile != null)
            IconButton(
              key: const Key('groups-open-profile'),
              tooltip: '打开个人资料与设置',
              onPressed: widget.onOpenProfile,
              icon: const Icon(Icons.account_circle_outlined),
            ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        key: const Key('create-group'),
        onPressed: controller.busy ? null : _create,
        icon: const Icon(Icons.group_add_outlined),
        label: const Text('创建群聊'),
      ),
      body: SafeArea(
        top: false,
        child: Column(
          children: [
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
            if (controller.loading) const LinearProgressIndicator(),
            Expanded(
              child: RefreshIndicator(
                onRefresh: controller.refresh,
                child: ListView(
                  key: const Key('groups-list'),
                  physics: const AlwaysScrollableScrollPhysics(),
                  padding: const EdgeInsets.only(bottom: 96),
                  children: controller.groups.isEmpty
                      ? const [
                          SizedBox(height: 140),
                          Center(child: Text('暂无群聊，可从好友创建一个群聊')),
                        ]
                      : controller.groups
                            .map(
                              (group) => ListTile(
                                key: ValueKey('group-${group.id}'),
                                leading: const CircleAvatar(
                                  child: Icon(Icons.group_outlined),
                                ),
                                title: Text(group.name),
                                subtitle: Text(
                                  group.announcement.isEmpty
                                      ? (group.lastMessage.isEmpty
                                            ? '查看成员与进入群聊'
                                            : group.lastMessage)
                                      : group.announcement,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                                trailing: const Icon(Icons.chevron_right),
                                onTap: () => _details(group),
                              ),
                            )
                            .toList(),
                ),
              ),
            ),
          ],
        ),
      ),
    ),
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
                  leading: CircleAvatar(
                    child: Text(member.displayName.characters.first),
                  ),
                  title: Text(member.displayName),
                  subtitle: Text(member.online ? '在线' : '离线'),
                  trailing: Text(member.roleLabel),
                ),
              const SizedBox(height: 24),
              if (owner)
                const Text('群主不能直接退出群聊；转让群主与解散属于后续管理功能。')
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
