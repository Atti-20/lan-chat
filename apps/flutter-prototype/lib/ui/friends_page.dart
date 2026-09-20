import 'dart:async';
import 'package:flutter/material.dart';
import '../application/friends_controller.dart';
import '../chat_controller.dart';
import '../data/friends_models.dart';
import '../data/meshx_api.dart';
import 'glass_chrome.dart';
import 'theme.dart';
import 'tokens.g.dart';
import 'profile_page.dart';
import 'components/meshx_avatar.dart';
import 'components/meshx_badge.dart';

class FriendsPage extends StatefulWidget {
  const FriendsPage({
    super.key,
    required this.chat,
    this.onConversationOpened,
    this.onOpenProfile,
  });
  final ChatController chat;
  final VoidCallback? onConversationOpened;
  final VoidCallback? onOpenProfile;

  @override
  State<FriendsPage> createState() => _FriendsPageState();
}

enum _ContactsMode { directory, requests, search }

class _FriendsPageState extends State<FriendsPage> {
  late final FriendsController controller;
  final searchInput = TextEditingController();
  _ContactsMode _mode = _ContactsMode.directory;

  @override
  void initState() {
    super.initState();
    controller = FriendsController(
      api: widget.chat.api!,
      currentUserId: widget.chat.session!.userId,
      changes: widget.chat.friendChanges,
      onRelationshipsChanged: widget.chat.relationshipsChanged,
      onRequestsChanged: widget.chat.notifyFriendRequestsChanged,
    );
    unawaited(controller.start());
  }

  @override
  void dispose() {
    controller.dispose();
    searchInput.dispose();
    super.dispose();
  }

  double get _bottomReservation =>
      meshXSizes['component.glass.navigation-min-height']! +
      meshXSizes['component.glass.navigation-inset']! * 2;

  void _selectMode(_ContactsMode value) {
    if (_mode != value) setState(() => _mode = value);
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: controller,
    builder: (context, _) {
      final horizontal = meshXSizes['spacing.5']!;
      return PopScope(
        canPop: _mode == _ContactsMode.directory,
        onPopInvokedWithResult: (didPop, _) {
          if (!didPop && _mode != _ContactsMode.directory) {
            _selectMode(_ContactsMode.directory);
          }
        },
        child: Scaffold(
          body: SafeArea(
            bottom: false,
            child: Column(
              children: [
                Padding(
                  padding: EdgeInsets.fromLTRB(
                    horizontal,
                    meshXSizes['spacing.2']!,
                    horizontal,
                    0,
                  ),
                  child: _FriendsHeader(
                    spaceName: widget.chat.node?.name ?? 'MeshX',
                    online: widget.chat.online,
                    onBack: _mode == _ContactsMode.directory
                        ? null
                        : () => _selectMode(_ContactsMode.directory),
                    onOpenSearch: () => _selectMode(_ContactsMode.search),
                    onOpenProfile: widget.onOpenProfile,
                  ),
                ),
                if (controller.error != null)
                  Padding(
                    padding: EdgeInsets.fromLTRB(
                      horizontal,
                      meshXSizes['spacing.2']!,
                      horizontal,
                      0,
                    ),
                    child: _FriendErrorNotice(
                      message: controller.error!,
                      onDismiss: controller.clearFeedback,
                    ),
                  ),
                if (controller.notice != null)
                  _Notice(
                    message: controller.notice!,
                    onDismiss: controller.clearFeedback,
                  ),
                if (controller.loading) const LinearProgressIndicator(),
                Expanded(
                  child: switch (_mode) {
                    _ContactsMode.directory => _contacts(),
                    _ContactsMode.requests => _requests(),
                    _ContactsMode.search => _search(),
                  },
                ),
              ],
            ),
          ),
        ),
      );
    },
  );

  Widget _contacts() {
    final horizontal = meshXSizes['spacing.5']!;
    final colors = palette(context);
    return RefreshIndicator(
      onRefresh: controller.refresh,
      child: ListView(
        key: const Key('friends-list'),
        physics: const AlwaysScrollableScrollPhysics(),
        padding: EdgeInsets.fromLTRB(
          horizontal,
          meshXSizes['spacing.4']!,
          horizontal,
          _bottomReservation,
        ),
        children: [
          if (controller.requests.isNotEmpty) ...[
            _FriendRequestsShortcut(
              cardKey: const Key('friend-requests-shortcut'),
              count: controller.requests.length,
              colors: colors,
              onTap: () => _selectMode(_ContactsMode.requests),
            ),
            SizedBox(height: meshXSizes['spacing.4']!),
          ],
          if (controller.friends.isEmpty)
            _FriendEmptyState(
              message: '暂无好友，可在“搜索”中发送申请',
              color: colors['color.text.secondary']!,
            )
          else
            ...controller.friends.map(
              (friend) => Padding(
                padding: EdgeInsets.only(bottom: meshXSizes['spacing.2']!),
                child: _FriendDirectoryCard(
                  key: ValueKey('friend-${friend.userId}'),
                  friend: friend,
                  api: controller.api,
                  colors: colors,
                  onOpen: () => unawaited(_openFriend(friend)),
                  onEdit: () => unawaited(_editRemark(friend)),
                  onDelete: () => unawaited(_delete(friend)),
                ),
              ),
            ),
          SizedBox(height: meshXSizes['spacing.3']!),
          OutlinedButton.icon(
            key: const Key('open-friend-search'),
            onPressed: () => _selectMode(_ContactsMode.search),
            icon: const Icon(Icons.person_add_alt_1_outlined),
            label: const Text('添加联系人'),
          ),
        ],
      ),
    );
  }

  Widget _requests() {
    final horizontal = meshXSizes['spacing.5']!;
    final colors = palette(context);
    return RefreshIndicator(
      onRefresh: controller.refresh,
      child: ListView(
        key: const Key('friend-requests-list'),
        physics: const AlwaysScrollableScrollPhysics(),
        padding: EdgeInsets.fromLTRB(
          horizontal,
          meshXSizes['spacing.4']!,
          horizontal,
          _bottomReservation,
        ),
        children: [
          Text('新的朋友', style: Theme.of(context).textTheme.titleMedium),
          SizedBox(height: meshXSizes['spacing.3']!),
          if (controller.requests.isEmpty)
            _FriendEmptyState(
              message: '暂无待处理申请',
              color: colors['color.text.secondary']!,
            )
          else
            ...controller.requests.map(
              (request) => Padding(
                padding: EdgeInsets.only(bottom: meshXSizes['spacing.3']!),
                child: _FriendRequestCard(
                  key: ValueKey('friend-request-${request.id}'),
                  request: request,
                  colors: colors,
                  busy: controller.operationRunning('handle:${request.id}'),
                  onAccept: () => controller.handleRequest(request, true),
                  onReject: () => controller.handleRequest(request, false),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _search() {
    final friendIds = controller.friends.map((item) => item.userId).toSet();
    final horizontal = meshXSizes['spacing.5']!;
    final colors = palette(context);
    return ListView(
      key: const Key('friend-search-list'),
      physics: const AlwaysScrollableScrollPhysics(),
      padding: EdgeInsets.fromLTRB(
        horizontal,
        meshXSizes['spacing.4']!,
        horizontal,
        _bottomReservation,
      ),
      children: [
        Text('搜索联系人', style: Theme.of(context).textTheme.titleMedium),
        SizedBox(height: meshXSizes['spacing.3']!),
        TextField(
          key: const Key('friend-search-input'),
          controller: searchInput,
          textInputAction: TextInputAction.search,
          onSubmitted: controller.search,
          decoration: InputDecoration(
            labelText: '用户名或昵称',
            prefixIcon: const Icon(Icons.search),
            suffixIcon: IconButton(
              key: const Key('friend-search-submit'),
              tooltip: '搜索',
              onPressed: controller.searching
                  ? null
                  : () => controller.search(searchInput.text),
              icon: controller.searching
                  ? const SizedBox.square(
                      dimension: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.arrow_forward),
            ),
          ),
        ),
        SizedBox(height: meshXSizes['spacing.3']!),
        for (final user in controller.results)
          Padding(
            padding: EdgeInsets.only(bottom: meshXSizes['spacing.2']!),
            child: _SearchResultCard(
              key: ValueKey('search-user-${user.userId}'),
              user: user,
              api: controller.api,
              colors: colors,
              isFriend: friendIds.contains(user.userId),
              busy: controller.operationRunning('request:${user.userId}'),
              onAdd: () => _sendRequest(user),
            ),
          ),
      ],
    );
  }

  Future<void> _openFriend(FriendContact friend) async {
    await widget.chat.openPrivateConversation(friend);
    if (!mounted) return;
    if (widget.onConversationOpened != null) {
      widget.onConversationOpened!();
    } else {
      Navigator.of(context).pop();
    }
  }

  Future<void> _sendRequest(UserSearchResult user) async {
    final input = TextEditingController(
      text: '你好，我是 ${widget.chat.session!.nickname}',
    );
    final message = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('添加 ${user.displayName}'),
        content: TextField(
          key: const Key('friend-request-message'),
          controller: input,
          maxLength: 200,
          maxLines: 3,
          decoration: const InputDecoration(labelText: '验证消息'),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          FilledButton(
            key: const Key('send-friend-request'),
            onPressed: () => Navigator.pop(context, input.text),
            child: const Text('发送'),
          ),
        ],
      ),
    );
    input.dispose();
    if (message != null) await controller.sendRequest(user, message);
  }

  Future<void> _editRemark(FriendContact friend) async {
    final input = TextEditingController(text: friend.remark);
    final value = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('修改备注'),
        content: TextField(
          key: const Key('friend-remark-input'),
          controller: input,
          maxLength: 40,
          autofocus: true,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          FilledButton(
            key: const Key('save-friend-remark'),
            onPressed: () => Navigator.pop(context, input.text),
            child: const Text('保存'),
          ),
        ],
      ),
    );
    input.dispose();
    if (value != null) await controller.setRemark(friend, value);
  }

  Future<void> _delete(FriendContact friend) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('删除 ${friend.displayName}？'),
        content: const Text('历史记录会保留，但待发消息将停止自动重试。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            key: const Key('confirm-delete-friend'),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (confirmed == true) await controller.deleteFriend(friend);
  }
}

class _FriendsHeader extends StatelessWidget {
  const _FriendsHeader({
    required this.spaceName,
    required this.online,
    required this.onOpenSearch,
    this.onBack,
    this.onOpenProfile,
  });

  final String spaceName;
  final bool online;
  final VoidCallback onOpenSearch;
  final VoidCallback? onBack;
  final VoidCallback? onOpenProfile;

  @override
  Widget build(BuildContext context) {
    final colors = palette(context);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (onBack != null) ...[
          MeshXGlassButton(
            key: const Key('friends-back-to-directory'),
            tooltip: '返回联系人目录',
            nativeSymbol: 'chevron.left',
            onPressed: onBack,
            icon: const Icon(Icons.chevron_left),
          ),
          SizedBox(width: meshXSizes['spacing.2']!),
        ],
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
              Text('联系人', style: Theme.of(context).textTheme.headlineMedium),
            ],
          ),
        ),
        MeshXGlassButton(
          key: const Key('friends-open-search'),
          tooltip: '搜索联系人',
          nativeSymbol: 'magnifyingglass',
          onPressed: onOpenSearch,
          icon: const Icon(Icons.search),
        ),
        if (onOpenProfile != null) ...[
          SizedBox(width: meshXSizes['spacing.2']!),
          MeshXGlassButton(
            key: const Key('friends-open-profile'),
            tooltip: '打开个人资料与设置',
            nativeSymbol: 'person.crop.circle',
            onPressed: onOpenProfile,
            icon: const Icon(Icons.account_circle_outlined),
          ),
        ],
      ],
    );
  }
}

class _FriendEmptyState extends StatelessWidget {
  const _FriendEmptyState({required this.message, required this.color});
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
            Icons.people_outline,
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

class _FriendRequestsShortcut extends StatelessWidget {
  const _FriendRequestsShortcut({
    this.cardKey,
    required this.count,
    required this.colors,
    required this.onTap,
  });
  final Key? cardKey;
  final int count;
  final Map<String, Color> colors;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final radius = BorderRadius.circular(meshXSizes['shape.radius.large']!);
    return Semantics(
      key: cardKey,
      button: true,
      label: '$count 条好友申请等待处理',
      child: Material(
        color: colors['color.interaction.selected'],
        borderRadius: radius,
        child: InkWell(
          onTap: onTap,
          borderRadius: radius,
          child: Padding(
            padding: EdgeInsets.all(meshXSizes['spacing.3']!),
            child: Row(
              children: [
                MeshXBadge(
                  count: count,
                  child: MeshXAvatar(
                    label: '新的朋友',
                    size: meshXSizes['size.control.default'],
                    icon: Icons.person_add_alt_1_outlined,
                  ),
                ),
                SizedBox(width: meshXSizes['spacing.3']!),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '新的朋友',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      SizedBox(height: meshXSizes['spacing.1']!),
                      Text(
                        '$count 条申请等待处理',
                        style: TextStyle(
                          color: colors['color.text.secondary'],
                          fontSize: meshXSizes['typography.caption.size']!,
                        ),
                      ),
                    ],
                  ),
                ),
                Icon(
                  Icons.arrow_forward,
                  color: colors['color.action.text'],
                  size: meshXSizes['size.icon.medium']!,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _FriendDirectoryCard extends StatelessWidget {
  const _FriendDirectoryCard({
    super.key,
    required this.friend,
    required this.api,
    required this.colors,
    required this.onOpen,
    required this.onEdit,
    required this.onDelete,
  });
  final FriendContact friend;
  final MeshXApi api;
  final Map<String, Color> colors;
  final VoidCallback onOpen;
  final VoidCallback onEdit;
  final VoidCallback onDelete;

  String get _subtitle {
    final availability = friend.online ? '在线' : '离线';
    return friend.signature.isEmpty
        ? availability
        : '${friend.signature} · $availability';
  }

  @override
  Widget build(BuildContext context) {
    final radius = BorderRadius.circular(meshXSizes['shape.radius.medium']!);
    return Semantics(
      button: true,
      label: '打开与 ${friend.displayName} 的私聊',
      child: Material(
        color: colors['color.background.muted'],
        borderRadius: radius,
        child: InkWell(
          onTap: onOpen,
          borderRadius: radius,
          child: Ink(
            padding: EdgeInsets.all(meshXSizes['spacing.3']!),
            decoration: BoxDecoration(
              borderRadius: radius,
              border: Border.all(color: colors['color.border.default']!),
            ),
            child: Row(
              children: [
                ProfileAvatar(
                  api: api,
                  nickname: friend.displayName,
                  avatar: friend.avatar,
                  size: meshXSizes['size.control.default']!,
                ),
                SizedBox(width: meshXSizes['spacing.3']!),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        friend.displayName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      SizedBox(height: meshXSizes['spacing.1']!),
                      Text(
                        _subtitle,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: colors['color.text.secondary'],
                          fontSize: meshXSizes['typography.caption.size']!,
                        ),
                      ),
                    ],
                  ),
                ),
                PopupMenuButton<String>(
                  tooltip: '好友操作',
                  onSelected: (value) {
                    if (value == 'remark') onEdit();
                    if (value == 'delete') onDelete();
                  },
                  itemBuilder: (_) => const [
                    PopupMenuItem(value: 'remark', child: Text('修改备注')),
                    PopupMenuItem(value: 'delete', child: Text('删除好友')),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _FriendRequestCard extends StatelessWidget {
  const _FriendRequestCard({
    super.key,
    required this.request,
    required this.colors,
    required this.busy,
    required this.onAccept,
    required this.onReject,
  });
  final FriendRequestItem request;
  final Map<String, Color> colors;
  final bool busy;
  final VoidCallback onAccept;
  final VoidCallback onReject;

  String get _name => request.senderName.isEmpty
      ? '用户 ${request.fromUserId}'
      : request.senderName;

  @override
  Widget build(BuildContext context) {
    final radius = BorderRadius.circular(meshXSizes['shape.radius.large']!);
    return Material(
      color: colors['color.background.muted'],
      borderRadius: radius,
      child: Padding(
        padding: EdgeInsets.all(meshXSizes['spacing.4']!),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                MeshXAvatar(
                  label: _name,
                  size: meshXSizes['size.control.default'],
                ),
                SizedBox(width: meshXSizes['spacing.3']!),
                Expanded(
                  child: Text(
                    _name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            SizedBox(height: meshXSizes['spacing.3']!),
            Text(request.message.isEmpty ? '请求添加你为好友' : request.message),
            SizedBox(height: meshXSizes['spacing.4']!),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    key: ValueKey('reject-request-${request.id}'),
                    onPressed: busy ? null : onReject,
                    icon: const Icon(Icons.close),
                    label: const Text('拒绝'),
                  ),
                ),
                SizedBox(width: meshXSizes['spacing.2']!),
                Expanded(
                  child: FilledButton.icon(
                    key: ValueKey('accept-request-${request.id}'),
                    onPressed: busy ? null : onAccept,
                    icon: const Icon(Icons.check),
                    label: const Text('同意'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _SearchResultCard extends StatelessWidget {
  const _SearchResultCard({
    super.key,
    required this.user,
    required this.api,
    required this.colors,
    required this.isFriend,
    required this.busy,
    required this.onAdd,
  });
  final UserSearchResult user;
  final MeshXApi api;
  final Map<String, Color> colors;
  final bool isFriend, busy;
  final VoidCallback onAdd;

  @override
  Widget build(BuildContext context) {
    final radius = BorderRadius.circular(meshXSizes['shape.radius.medium']!);
    return Material(
      color: colors['color.background.muted'],
      borderRadius: radius,
      child: Padding(
        padding: EdgeInsets.all(meshXSizes['spacing.3']!),
        child: Row(
          children: [
            ProfileAvatar(
              api: api,
              nickname: user.displayName,
              avatar: user.avatar,
              size: meshXSizes['size.control.default']!,
            ),
            SizedBox(width: meshXSizes['spacing.3']!),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    user.displayName,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  SizedBox(height: meshXSizes['spacing.1']!),
                  Text(
                    user.signature.isEmpty
                        ? '@${user.username}'
                        : user.signature,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: colors['color.text.secondary'],
                      fontSize: meshXSizes['typography.caption.size']!,
                    ),
                  ),
                ],
              ),
            ),
            SizedBox(width: meshXSizes['spacing.2']!),
            isFriend
                ? Text(
                    '已是好友',
                    style: TextStyle(color: colors['color.text.secondary']),
                  )
                : FilledButton(
                    onPressed: busy ? null : onAdd,
                    child: const Text('添加'),
                  ),
          ],
        ),
      ),
    );
  }
}

class _Notice extends StatelessWidget {
  const _Notice({required this.message, required this.onDismiss});
  final String message;
  final VoidCallback onDismiss;

  @override
  Widget build(BuildContext context) => MaterialBanner(
    content: Text(message),
    backgroundColor: palette(context)['success']!.withValues(alpha: .10),
    actions: [TextButton(onPressed: onDismiss, child: const Text('知道了'))],
  );
}

class _FriendErrorNotice extends StatelessWidget {
  const _FriendErrorNotice({required this.message, required this.onDismiss});
  final String message;
  final VoidCallback onDismiss;

  @override
  Widget build(BuildContext context) => MaterialBanner(
    content: Text(message),
    backgroundColor: palette(context)['danger']!.withValues(alpha: .10),
    actions: [TextButton(onPressed: onDismiss, child: const Text('关闭'))],
  );
}
