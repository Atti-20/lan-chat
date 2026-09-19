import 'dart:async';
import 'package:flutter/material.dart';
import '../application/broadcasts_controller.dart';
import '../application/platform_coordinator.dart';
import '../chat_controller.dart';
import '../data/broadcast_models.dart';
import 'theme.dart';
import 'broadcast_labels.dart';
import 'broadcast_image_page.dart';

class BroadcastsPage extends StatefulWidget {
  const BroadcastsPage({
    super.key,
    required this.chat,
    this.platform,
    this.onOpenProfile,
  });
  final ChatController chat;
  final PlatformCoordinator? platform;
  final VoidCallback? onOpenProfile;

  @override
  State<BroadcastsPage> createState() => _BroadcastsPageState();
}

class BroadcastDetailLoaderPage extends StatefulWidget {
  const BroadcastDetailLoaderPage({
    super.key,
    required this.chat,
    required this.broadcastId,
    this.platform,
  });
  final ChatController chat;
  final int broadcastId;
  final PlatformCoordinator? platform;
  @override
  State<BroadcastDetailLoaderPage> createState() =>
      _BroadcastDetailLoaderPageState();
}

class _BroadcastDetailLoaderPageState extends State<BroadcastDetailLoaderPage> {
  late final BroadcastsController controller;
  @override
  void initState() {
    super.initState();
    controller = BroadcastsController(
      chat: widget.chat,
      platform: widget.platform,
    );
    unawaited(controller.open(widget.broadcastId));
  }

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: controller,
    builder: (context, _) {
      if (controller.busy && controller.detail == null) {
        return Scaffold(
          appBar: AppBar(title: const Text('广播详情')),
          body: const Center(child: CircularProgressIndicator()),
        );
      }
      return BroadcastDetailPage(controller: controller);
    },
  );
}

class _BroadcastsPageState extends State<BroadcastsPage> {
  late final BroadcastsController controller;
  @override
  void initState() {
    super.initState();
    controller = BroadcastsController(
      chat: widget.chat,
      platform: widget.platform,
    );
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
        title: const Text('广播待办'),
        actions: [
          if (widget.onOpenProfile != null)
            IconButton(
              key: const Key('broadcasts-open-profile'),
              tooltip: '打开个人资料与设置',
              onPressed: widget.onOpenProfile,
              icon: const Icon(Icons.account_circle_outlined),
            ),
        ],
      ),
      body: SafeArea(
        top: false,
        child: Column(
          children: [
            if (controller.error != null)
              _Notice(
                message: controller.error!,
                error: true,
                onDismiss: controller.clearFeedback,
              ),
            if (controller.loading) const LinearProgressIndicator(),
            Expanded(
              child: RefreshIndicator(
                onRefresh: controller.refresh,
                child: ListView(
                  key: const Key('broadcast-list'),
                  physics: const AlwaysScrollableScrollPhysics(),
                  padding: const EdgeInsets.fromLTRB(12, 8, 12, 24),
                  children: [
                    if (controller.pending.isNotEmpty) ...[
                      const _SectionTitle('待办'),
                      ...controller.pending.map(
                        (item) => _tile(item, pending: true),
                      ),
                      const SizedBox(height: 12),
                    ],
                    const _SectionTitle('全部广播'),
                    if (controller.items.isEmpty)
                      const Padding(
                        padding: EdgeInsets.only(top: 96),
                        child: Center(child: Text('暂无可查看的广播')),
                      )
                    else
                      ...controller.items.map(_tile),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    ),
  );

  Widget _tile(BroadcastSummary item, {bool pending = false}) => Card(
    child: ListTile(
      key: ValueKey('broadcast-${item.id}${pending ? '-pending' : ''}'),
      leading: Icon(
        item.priority == 'EMERGENCY'
            ? Icons.warning_amber_rounded
            : Icons.campaign_outlined,
        color: item.priority == 'EMERGENCY' ? Colors.red : null,
      ),
      title: Text(item.title, maxLines: 1, overflow: TextOverflow.ellipsis),
      subtitle: Text(
        item.status == 'CANCELLED'
            ? '已取消'
            : item.expired
            ? '已过期'
            : (item.currentUserConfirmStatus.isEmpty
                  ? item.content
                  : '回执：${broadcastConfirmationLabel(item.currentUserConfirmStatus)}'),
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
      ),
      trailing: const Icon(Icons.chevron_right),
      onTap: () async {
        final opened = await controller.open(item.id);
        if (opened && mounted) {
          await Navigator.of(context).push(
            MaterialPageRoute<void>(
              builder: (_) => BroadcastDetailPage(controller: controller),
            ),
          );
        }
      },
    ),
  );
}

class BroadcastDetailPage extends StatelessWidget {
  const BroadcastDetailPage({super.key, required this.controller});
  final BroadcastsController controller;

  Future<void> _runAction(
    BuildContext context,
    Future<bool> Function() action,
  ) async {
    final succeeded = await action();
    if (!context.mounted || succeeded || controller.error == null) return;
    // Keep the persistent detail notice, but surface errors at the user's
    // current scroll position too. Cancellation intentionally stays quiet.
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(controller.error!)));
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: controller,
    builder: (context, _) {
      final detail = controller.detail;
      if (detail == null) {
        return Scaffold(
          appBar: AppBar(title: const Text('广播详情')),
          body: const Center(child: Text('已无权查看或广播已不存在')),
        );
      }
      final broadcast = detail.broadcast;
      return Scaffold(
        appBar: AppBar(title: const Text('广播详情')),
        body: SafeArea(
          top: false,
          child: ListView(
            key: const Key('broadcast-detail'),
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
            children: [
              if (controller.error != null)
                _Notice(
                  message: controller.error!,
                  error: true,
                  onDismiss: controller.clearFeedback,
                ),
              if (controller.notice != null)
                _Notice(
                  message: controller.notice!,
                  onDismiss: controller.clearFeedback,
                ),
              Text(
                broadcast.title,
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              const SizedBox(height: 8),
              Text(
                '${detail.senderName.isEmpty ? '未知发布者' : detail.senderName} · ${broadcastPriorityLabel(broadcast.priority)}',
                style: TextStyle(color: palette(context)['ink-soft']),
              ),
              const SizedBox(height: 20),
              SelectableText(broadcast.content),
              if (detail.contentImageUrls.isNotEmpty) ...[
                const SizedBox(height: 16),
                for (
                  var index = 0;
                  index < detail.contentImageUrls.length;
                  index++
                )
                  OutlinedButton.icon(
                    key: ValueKey('broadcast-content-image-$index'),
                    onPressed: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) => BroadcastImagePage(
                          controller: controller,
                          broadcastId: broadcast.id,
                          url: detail.contentImageUrls[index],
                          index: index,
                        ),
                      ),
                    ),
                    icon: const Icon(Icons.image_outlined),
                    label: Text('查看图片 ${index + 1}'),
                  ),
              ],
              const SizedBox(height: 24),
              _StatusCard(detail: detail),
              if (detail.locationReadOnly) ...[
                const SizedBox(height: 12),
                const _Notice(message: '该广播要求定位凭证。请使用 Web 端办理，移动端目前仅支持查看。'),
              ],
              const SizedBox(height: 20),
              if (detail.canSubmit &&
                  !detail.locationReadOnly &&
                  broadcast.confirmationRequired) ...[
                for (final option in detail.confirmationOptions)
                  if (option != 'EXECUTED' &&
                      detail.receiver?.confirmedAt == null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: OutlinedButton(
                        key: ValueKey('confirm-$option'),
                        onPressed: controller.busy
                            ? null
                            : () => _runAction(
                                context,
                                () => controller.confirm(option),
                              ),
                        child: Text(broadcastConfirmationLabel(option)),
                      ),
                    ),
                if (broadcast.confirmationRequired &&
                    detail.receiver?.confirmedAt == null &&
                    detail.confirmationOptions.contains('EXECUTED'))
                  FilledButton.icon(
                    key: const Key('complete-broadcast'),
                    onPressed: controller.busy
                        ? null
                        : () => _runAction(context, controller.complete),
                    icon: Icon(
                      broadcast.requireImageProof
                          ? Icons.add_a_photo_outlined
                          : Icons.task_alt,
                    ),
                    label: Text(
                      controller.busy
                          ? '正在办理…'
                          : broadcast.requireImageProof
                          ? '上传图片并完成'
                          : '完成广播任务',
                    ),
                  ),
              ],
              if (detail.receiver?.confirmedAt != null)
                const Text('已提交回执，这条广播已标记为完成。')
              else if (!broadcast.confirmationRequired)
                const Text('这条广播不要求确认，打开详情即表示已查看。'),
              if (!detail.canSubmit) const Text('当前广播已结束、过期或你已不在目标范围内。'),
            ],
          ),
        ),
      );
    },
  );
}

class _StatusCard extends StatelessWidget {
  const _StatusCard({required this.detail});
  final BroadcastDetail detail;
  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(14),
      child: Text(
        '广播状态：${broadcastStatusLabel(detail.broadcast.status)}\n'
        '接收状态：${broadcastTargetLabel(detail.receiver?.targetStatus)}\n'
        '当前回执：${broadcastConfirmationLabel(detail.receiver?.confirmStatus)}',
      ),
    ),
  );
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(4, 12, 4, 8),
    child: Text(text, style: Theme.of(context).textTheme.titleMedium),
  );
}

class _Notice extends StatelessWidget {
  const _Notice({required this.message, this.error = false, this.onDismiss});
  final String message;
  final bool error;
  final VoidCallback? onDismiss;
  @override
  Widget build(BuildContext context) => Card(
    color: error
        ? Theme.of(context).colorScheme.errorContainer
        : Theme.of(context).colorScheme.secondaryContainer,
    child: ListTile(
      title: Text(message),
      trailing: onDismiss == null
          ? null
          : IconButton(
              tooltip: '关闭提示',
              onPressed: onDismiss,
              icon: const Icon(Icons.close),
            ),
    ),
  );
}
