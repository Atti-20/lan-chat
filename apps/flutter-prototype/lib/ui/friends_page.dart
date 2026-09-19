import 'dart:async';
import 'package:flutter/material.dart';
import '../application/friends_controller.dart';
import '../chat_controller.dart';
import '../data/friends_models.dart';
import 'theme.dart';

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

class _FriendsPageState extends State<FriendsPage> {
  late final FriendsController controller;
  final searchInput = TextEditingController();

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

  @override
  Widget build(BuildContext context) => DefaultTabController(
    length: 3,
    child: AnimatedBuilder(
      animation: controller,
      builder: (context, _) => Scaffold(
        appBar: AppBar(
          title: const Text('联系人'),
          actions: [
            if (widget.onOpenProfile != null)
              IconButton(
                key: const Key('friends-open-profile'),
                tooltip: '打开个人资料与设置',
                onPressed: widget.onOpenProfile,
                icon: const Icon(Icons.account_circle_outlined),
              ),
          ],
          bottom: TabBar(
            tabs: [
              const Tab(text: '好友'),
              Tab(
                child: Badge(
                  isLabelVisible: controller.requests.isNotEmpty,
                  label: Text('${controller.requests.length}'),
                  child: const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 8),
                    child: Text('申请'),
                  ),
                ),
              ),
              const Tab(text: '搜索'),
            ],
          ),
        ),
        body: SafeArea(
          top: false,
          child: Column(
            children: [
              if (controller.error != null)
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 10, 16, 0),
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
                child: TabBarView(
                  children: [_contacts(), _requests(), _search()],
                ),
              ),
            ],
          ),
        ),
      ),
    ),
  );

  Widget _contacts() => RefreshIndicator(
    onRefresh: controller.refresh,
    child: ListView(
      key: const Key('friends-list'),
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.symmetric(vertical: 8),
      children: controller.friends.isEmpty
          ? const [
              SizedBox(height: 120),
              Center(child: Text('暂无好友，可在“搜索”中发送申请')),
            ]
          : controller.friends
                .map(
                  (friend) => ListTile(
                    key: ValueKey('friend-${friend.userId}'),
                    leading: _FriendAvatar(name: friend.displayName),
                    title: Text(friend.displayName),
                    subtitle: Text(
                      friend.signature.isEmpty
                          ? (friend.online ? '在线' : '离线')
                          : friend.signature,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    onTap: () async {
                      await widget.chat.openPrivateConversation(friend);
                      if (!mounted) return;
                      if (widget.onConversationOpened != null) {
                        widget.onConversationOpened!();
                      } else {
                        Navigator.of(context).pop();
                      }
                    },
                    trailing: PopupMenuButton<String>(
                      tooltip: '好友操作',
                      onSelected: (value) {
                        if (value == 'remark') unawaited(_editRemark(friend));
                        if (value == 'delete') unawaited(_delete(friend));
                      },
                      itemBuilder: (_) => const [
                        PopupMenuItem(value: 'remark', child: Text('修改备注')),
                        PopupMenuItem(value: 'delete', child: Text('删除好友')),
                      ],
                    ),
                  ),
                )
                .toList(),
    ),
  );

  Widget _requests() => RefreshIndicator(
    onRefresh: controller.refresh,
    child: ListView(
      key: const Key('friend-requests-list'),
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.all(12),
      children: controller.requests.isEmpty
          ? const [SizedBox(height: 120), Center(child: Text('暂无待处理申请'))]
          : controller.requests
                .map(
                  (request) => Card(
                    child: ListTile(
                      key: ValueKey('friend-request-${request.id}'),
                      leading: _FriendAvatar(
                        name: request.senderName.isEmpty
                            ? '${request.fromUserId}'
                            : request.senderName,
                      ),
                      title: Text(
                        request.senderName.isEmpty
                            ? '用户 ${request.fromUserId}'
                            : request.senderName,
                      ),
                      subtitle: Text(
                        request.message.isEmpty ? '请求添加你为好友' : request.message,
                      ),
                      trailing: Wrap(
                        spacing: 4,
                        children: [
                          IconButton(
                            key: ValueKey('reject-request-${request.id}'),
                            tooltip: '拒绝',
                            onPressed:
                                controller.operationRunning(
                                  'handle:${request.id}',
                                )
                                ? null
                                : () =>
                                      controller.handleRequest(request, false),
                            icon: const Icon(Icons.close),
                          ),
                          IconButton.filled(
                            key: ValueKey('accept-request-${request.id}'),
                            tooltip: '同意',
                            onPressed:
                                controller.operationRunning(
                                  'handle:${request.id}',
                                )
                                ? null
                                : () => controller.handleRequest(request, true),
                            icon: const Icon(Icons.check),
                          ),
                        ],
                      ),
                    ),
                  ),
                )
                .toList(),
    ),
  );

  Widget _search() {
    final friendIds = controller.friends.map((item) => item.userId).toSet();
    return ListView(
      key: const Key('friend-search-list'),
      padding: const EdgeInsets.all(16),
      children: [
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
        const SizedBox(height: 12),
        for (final user in controller.results)
          ListTile(
            key: ValueKey('search-user-${user.userId}'),
            leading: _FriendAvatar(name: user.displayName),
            title: Text(user.displayName),
            subtitle: Text(
              user.signature.isEmpty ? '@${user.username}' : user.signature,
            ),
            trailing: friendIds.contains(user.userId)
                ? const Text('已是好友')
                : FilledButton(
                    onPressed:
                        controller.operationRunning('request:${user.userId}')
                        ? null
                        : () => _sendRequest(user),
                    child: const Text('添加'),
                  ),
          ),
      ],
    );
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

class _FriendAvatar extends StatelessWidget {
  const _FriendAvatar({required this.name});
  final String name;

  @override
  Widget build(BuildContext context) => Container(
    width: 48,
    height: 48,
    alignment: Alignment.center,
    decoration: BoxDecoration(
      color: palette(context)['blue']!.withValues(alpha: .10),
      borderRadius: BorderRadius.circular(15),
    ),
    child: Text(
      name.isEmpty ? 'M' : name.characters.first,
      style: TextStyle(
        fontSize: 20,
        fontWeight: FontWeight.w600,
        color: palette(context)['accent-text'],
      ),
    ),
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
