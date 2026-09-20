import 'dart:async';
import 'package:flutter/material.dart';
import '../application/broadcasts_controller.dart';
import '../application/platform_coordinator.dart';
import '../chat_controller.dart';
import '../data/broadcast_models.dart';
import 'theme.dart';
import 'broadcast_labels.dart';
import 'broadcast_image_page.dart';
import 'broadcast_management.dart';
import 'glass_chrome.dart';
import 'tokens.g.dart';

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
          body: SafeArea(
            child: Padding(
              padding: EdgeInsets.all(meshXSizes['spacing.5']!),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _BroadcastDetailHeader(
                    onBack: () => Navigator.maybePop(context),
                  ),
                  const Expanded(
                    child: Center(child: CircularProgressIndicator()),
                  ),
                ],
              ),
            ),
          ),
        );
      }
      return BroadcastDetailPage(controller: controller);
    },
  );
}

enum _BroadcastListMode { pending, completed, all }

class _BroadcastsPageState extends State<BroadcastsPage> {
  late final BroadcastsController controller;
  _BroadcastListMode _mode = _BroadcastListMode.pending;

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

  double get _bottomReservation =>
      meshXSizes['component.glass.navigation-min-height']! +
      meshXSizes['component.glass.navigation-inset']! * 2;

  List<BroadcastSummary> get _completed => controller.items
      .where((item) => item.currentUserConfirmStatus == 'EXECUTED')
      .toList(growable: false);

  List<BroadcastSummary> get _visibleItems => switch (_mode) {
    _BroadcastListMode.pending => controller.pending,
    _BroadcastListMode.completed => _completed,
    _BroadcastListMode.all => controller.items,
  };

  String get _sectionTitle => switch (_mode) {
    _BroadcastListMode.pending => '需要你关注',
    _BroadcastListMode.completed => '已完成',
    _BroadcastListMode.all => '全部广播',
  };

  String get _emptyMessage => switch (_mode) {
    _BroadcastListMode.pending => '暂无待处理广播',
    _BroadcastListMode.completed => '暂无已完成广播',
    _BroadcastListMode.all => '暂无可查看的广播',
  };

  Future<void> _open(BroadcastSummary item) async {
    final opened = await controller.open(item.id);
    if (!opened || !mounted) return;
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => BroadcastDetailPage(controller: controller),
      ),
    );
  }

  void _openManagement() {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => BroadcastManagementPage(
          chat: widget.chat,
          platform: widget.platform,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: controller,
    builder: (context, _) {
      final horizontal = meshXSizes['spacing.5']!;
      final items = _visibleItems;
      return Scaffold(
        body: SafeArea(
          bottom: false,
          child: RefreshIndicator(
            onRefresh: controller.refresh,
            child: ListView(
              key: const Key('broadcast-list'),
              physics: const AlwaysScrollableScrollPhysics(),
              padding: EdgeInsets.fromLTRB(
                horizontal,
                meshXSizes['spacing.2']!,
                horizontal,
                _bottomReservation,
              ),
              children: [
                _BroadcastHeader(
                  spaceName: widget.chat.node?.name ?? 'MeshX',
                  online: widget.chat.online,
                  onOpenManagement: _openManagement,
                  onOpenProfile: widget.onOpenProfile,
                ),
                SizedBox(height: meshXSizes['spacing.5']!),
                _BroadcastFilterRow(
                  selected: _mode,
                  pendingCount: controller.pending.length,
                  onChanged: (value) => setState(() => _mode = value),
                ),
                SizedBox(height: meshXSizes['spacing.5']!),
                if (controller.error != null) ...[
                  _Notice(
                    message: controller.error!,
                    error: true,
                    onDismiss: controller.clearFeedback,
                  ),
                  SizedBox(height: meshXSizes['spacing.3']!),
                ],
                if (controller.notice != null) ...[
                  _Notice(
                    message: controller.notice!,
                    onDismiss: controller.clearFeedback,
                  ),
                  SizedBox(height: meshXSizes['spacing.3']!),
                ],
                if (controller.loading)
                  const Padding(
                    padding: EdgeInsets.only(bottom: 12),
                    child: LinearProgressIndicator(),
                  ),
                Text(
                  _sectionTitle,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                SizedBox(height: meshXSizes['spacing.3']!),
                if (items.isEmpty)
                  _BroadcastEmptyState(message: _emptyMessage)
                else
                  ...items.map(
                    (item) => Padding(
                      padding: EdgeInsets.only(
                        bottom: meshXSizes['spacing.3']!,
                      ),
                      child: _BroadcastTaskCard(
                        key: ValueKey(
                          'broadcast-${item.id}${_mode == _BroadcastListMode.pending ? '-pending' : ''}',
                        ),
                        item: item,
                        pending: _mode == _BroadcastListMode.pending,
                        onTap: () => unawaited(_open(item)),
                      ),
                    ),
                  ),
                SizedBox(height: meshXSizes['spacing.4']!),
                Text(
                  '任务状态以节点确认结果为准',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: palette(context)['color.text.tertiary'],
                    fontSize: meshXSizes['typography.caption.size']!,
                  ),
                ),
              ],
            ),
          ),
        ),
      );
    },
  );
}

class _BroadcastHeader extends StatelessWidget {
  const _BroadcastHeader({
    required this.spaceName,
    required this.online,
    required this.onOpenManagement,
    this.onOpenProfile,
  });

  final String spaceName;
  final bool online;
  final VoidCallback onOpenManagement;
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
              Text('广播', style: Theme.of(context).textTheme.headlineMedium),
            ],
          ),
        ),
        MeshXGlassButton(
          key: const Key('broadcasts-open-management'),
          tooltip: '广播发布与管理',
          nativeSymbol: 'megaphone',
          onPressed: onOpenManagement,
          icon: const Icon(Icons.campaign_outlined),
        ),
        if (onOpenProfile != null) ...[
          SizedBox(width: meshXSizes['spacing.2']!),
          MeshXGlassButton(
            key: const Key('broadcasts-open-profile'),
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

class _BroadcastFilterRow extends StatelessWidget {
  const _BroadcastFilterRow({
    required this.selected,
    required this.pendingCount,
    required this.onChanged,
  });

  final _BroadcastListMode selected;
  final int pendingCount;
  final ValueChanged<_BroadcastListMode> onChanged;

  @override
  Widget build(BuildContext context) => Wrap(
    spacing: meshXSizes['spacing.2']!,
    runSpacing: meshXSizes['spacing.2']!,
    children: [
      _BroadcastFilterChip(
        key: const Key('broadcast-filter-pending'),
        label: '待处理 $pendingCount',
        selected: selected == _BroadcastListMode.pending,
        onTap: () => onChanged(_BroadcastListMode.pending),
      ),
      _BroadcastFilterChip(
        key: const Key('broadcast-filter-completed'),
        label: '已完成',
        selected: selected == _BroadcastListMode.completed,
        onTap: () => onChanged(_BroadcastListMode.completed),
      ),
      _BroadcastFilterChip(
        key: const Key('broadcast-filter-all'),
        label: '全部',
        selected: selected == _BroadcastListMode.all,
        onTap: () => onChanged(_BroadcastListMode.all),
      ),
    ],
  );
}

class _BroadcastFilterChip extends StatelessWidget {
  const _BroadcastFilterChip({
    super.key,
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = palette(context);
    final radius = BorderRadius.circular(meshXSizes['shape.radius.pill']!);
    return Semantics(
      button: true,
      selected: selected,
      label: label,
      child: Material(
        color:
            colors[selected
                ? 'color.interaction.selected'
                : 'color.background.muted'],
        borderRadius: radius,
        child: InkWell(
          onTap: onTap,
          borderRadius: radius,
          child: Padding(
            padding: EdgeInsets.symmetric(
              horizontal: meshXSizes['spacing.3']!,
              vertical: meshXSizes['spacing.1']!,
            ),
            child: Text(
              label,
              style: TextStyle(
                color:
                    colors[selected
                        ? 'color.action.text'
                        : 'color.text.secondary'],
                fontSize: meshXSizes['typography.caption.size']!,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _BroadcastEmptyState extends StatelessWidget {
  const _BroadcastEmptyState({required this.message});
  final String message;

  @override
  Widget build(BuildContext context) => Padding(
    padding: EdgeInsets.symmetric(vertical: meshXSizes['spacing.10']!),
    child: Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            Icons.campaign_outlined,
            size: meshXSizes['size.icon.large']!,
            color: palette(context)['color.text.secondary'],
          ),
          SizedBox(height: meshXSizes['spacing.3']!),
          Text(message, textAlign: TextAlign.center),
        ],
      ),
    ),
  );
}

class _BroadcastTaskCard extends StatelessWidget {
  const _BroadcastTaskCard({
    super.key,
    required this.item,
    required this.pending,
    required this.onTap,
  });

  final BroadcastSummary item;
  final bool pending;
  final VoidCallback onTap;

  String get _stateLabel {
    if (pending) return '待处理';
    if (item.status == 'CANCELLED') return '已取消';
    if (item.expired) return '已过期';
    final confirmation = item.currentUserConfirmStatus;
    if (confirmation.isNotEmpty) {
      return '回执：${broadcastConfirmationLabel(confirmation)}';
    }
    return broadcastStatusLabel(item.status);
  }

  Color _priorityColor(Map<String, Color> colors) => switch (item.priority) {
    'EMERGENCY' => colors['color.status.danger']!,
    'IMPORTANT' => colors['color.status.warning']!,
    _ => colors['color.action.text']!,
  };

  String _deadlineLabel() {
    final deadline = item.deadlineAt;
    if (deadline == null) return '未设截止时间';
    final local = deadline.toLocal();
    final formatted =
        '${local.year.toString().padLeft(4, '0')}-'
        '${local.month.toString().padLeft(2, '0')}-'
        '${local.day.toString().padLeft(2, '0')} '
        '${local.hour.toString().padLeft(2, '0')}:'
        '${local.minute.toString().padLeft(2, '0')}';
    return item.expired ? '已于 $formatted 截止' : '截止 $formatted';
  }

  @override
  Widget build(BuildContext context) {
    final colors = palette(context);
    final radius = BorderRadius.circular(meshXSizes['shape.radius.large']!);
    final priorityColor = _priorityColor(colors);
    return Semantics(
      button: true,
      label: '打开广播 ${item.title}，$_stateLabel',
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
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Wrap(
                  spacing: meshXSizes['spacing.2']!,
                  runSpacing: meshXSizes['spacing.2']!,
                  children: [
                    _BroadcastPill(
                      label: broadcastPriorityLabel(item.priority),
                      color: priorityColor,
                    ),
                    _BroadcastPill(
                      label: _stateLabel,
                      color: colors['color.action.text']!,
                    ),
                    if (item.requireImageProof)
                      _BroadcastPill(
                        label: '需要图片凭证',
                        color: colors['color.action.text']!,
                      ),
                    if (item.requireLocationProof)
                      _BroadcastPill(
                        label: '需要定位凭证',
                        color: colors['color.status.warning']!,
                      ),
                  ],
                ),
                SizedBox(height: meshXSizes['spacing.3']!),
                Text(
                  item.title,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                if (item.content.isNotEmpty) ...[
                  SizedBox(height: meshXSizes['spacing.2']!),
                  Text(
                    item.content,
                    maxLines: 3,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: colors['color.text.secondary'],
                      fontSize: meshXSizes['typography.body.size']!,
                    ),
                  ),
                ],
                SizedBox(height: meshXSizes['spacing.4']!),
                Row(
                  children: [
                    Icon(
                      Icons.schedule_outlined,
                      size: meshXSizes['size.icon.small']!,
                      color: colors['color.text.secondary'],
                    ),
                    SizedBox(width: meshXSizes['spacing.1']!),
                    Expanded(
                      child: Text(
                        _deadlineLabel(),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: colors['color.text.secondary'],
                          fontSize: meshXSizes['typography.caption.size']!,
                        ),
                      ),
                    ),
                    SizedBox(width: meshXSizes['spacing.2']!),
                    Text(
                      pending ? '办理任务' : '查看任务',
                      style: TextStyle(
                        color: colors['color.action.text'],
                        fontSize: meshXSizes['typography.body.size']!,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
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

class _BroadcastPill extends StatelessWidget {
  const _BroadcastPill({required this.label, required this.color});
  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
    padding: EdgeInsets.symmetric(
      horizontal: meshXSizes['spacing.2']!,
      vertical: meshXSizes['spacing.1']!,
    ),
    decoration: BoxDecoration(
      border: Border.all(color: color),
      borderRadius: BorderRadius.circular(meshXSizes['shape.radius.pill']!),
    ),
    child: Text(
      label,
      style: TextStyle(
        color: color,
        fontSize: meshXSizes['typography.caption.size']!,
        fontWeight: FontWeight.w600,
      ),
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
          body: SafeArea(
            child: Padding(
              padding: EdgeInsets.all(meshXSizes['spacing.5']!),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _BroadcastDetailHeader(
                    onBack: () => Navigator.maybePop(context),
                  ),
                  const Spacer(),
                  const Center(child: Text('已无权查看或广播已不存在')),
                  const Spacer(),
                ],
              ),
            ),
          ),
        );
      }
      final broadcast = detail.broadcast;
      return Scaffold(
        body: SafeArea(
          child: ListView(
            key: const Key('broadcast-detail'),
            padding: EdgeInsets.fromLTRB(
              meshXSizes['spacing.5']!,
              meshXSizes['spacing.2']!,
              meshXSizes['spacing.5']!,
              meshXSizes['spacing.8']!,
            ),
            children: [
              _BroadcastDetailHeader(onBack: () => Navigator.maybePop(context)),
              SizedBox(height: meshXSizes['spacing.5']!),
              if (controller.error != null) ...[
                _Notice(
                  message: controller.error!,
                  error: true,
                  onDismiss: controller.clearFeedback,
                ),
                SizedBox(height: meshXSizes['spacing.3']!),
              ],
              if (controller.notice != null) ...[
                _Notice(
                  message: controller.notice!,
                  onDismiss: controller.clearFeedback,
                ),
                SizedBox(height: meshXSizes['spacing.3']!),
              ],
              _BroadcastContentCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Wrap(
                      spacing: meshXSizes['spacing.2']!,
                      runSpacing: meshXSizes['spacing.2']!,
                      children: [
                        _BroadcastPill(
                          label: broadcastPriorityLabel(broadcast.priority),
                          color: _detailPriorityColor(context, broadcast),
                        ),
                        _BroadcastPill(
                          label: broadcastStatusLabel(broadcast.status),
                          color: palette(context)['color.action.text']!,
                        ),
                      ],
                    ),
                    SizedBox(height: meshXSizes['spacing.3']!),
                    Text(
                      broadcast.title,
                      style: Theme.of(context).textTheme.headlineSmall,
                    ),
                    SizedBox(height: meshXSizes['spacing.2']!),
                    Text(
                      '${detail.senderName.isEmpty ? '未知发布者' : detail.senderName} · ${broadcastPriorityLabel(broadcast.priority)}',
                      style: TextStyle(
                        color: palette(context)['color.text.secondary'],
                        fontSize: meshXSizes['typography.body.size']!,
                      ),
                    ),
                    if (broadcast.content.isNotEmpty) ...[
                      SizedBox(height: meshXSizes['spacing.4']!),
                      SelectableText(broadcast.content),
                    ],
                  ],
                ),
              ),
              if (detail.contentImageUrls.isNotEmpty) ...[
                SizedBox(height: meshXSizes['spacing.3']!),
                for (
                  var index = 0;
                  index < detail.contentImageUrls.length;
                  index++
                )
                  Padding(
                    padding: EdgeInsets.only(bottom: meshXSizes['spacing.2']!),
                    child: OutlinedButton.icon(
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
                  ),
              ],
              SizedBox(height: meshXSizes['spacing.3']!),
              _BroadcastDeadlineCard(broadcast: broadcast),
              SizedBox(height: meshXSizes['spacing.3']!),
              _StatusCard(detail: detail),
              if (broadcast.requireImageProof) ...[
                SizedBox(height: meshXSizes['spacing.3']!),
                const _BroadcastRequirementCard(
                  icon: Icons.add_a_photo_outlined,
                  title: '需要图片凭证',
                  message: '完成时会调用现有安全图片选择与上传流程。',
                ),
              ],
              if (detail.locationReadOnly) ...[
                SizedBox(height: meshXSizes['spacing.3']!),
                const _BroadcastRequirementCard(
                  icon: Icons.location_on_outlined,
                  title: '需要定位凭证',
                  message: '点击完成时将请求一次当前位置并提交给节点。',
                ),
              ],
              SizedBox(height: meshXSizes['spacing.4']!),
              if (detail.canSubmit && broadcast.confirmationRequired)
                _BroadcastContentCard(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        '提交回执',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      SizedBox(height: meshXSizes['spacing.3']!),
                      for (final option in detail.confirmationOptions)
                        if (option != 'EXECUTED' &&
                            detail.receiver?.confirmedAt == null)
                          Padding(
                            padding: EdgeInsets.only(
                              bottom: meshXSizes['spacing.2']!,
                            ),
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
                      if (detail.receiver?.confirmedAt == null &&
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
                  ),
                ),
              if (detail.receiver?.confirmedAt != null ||
                  detail.receiver?.completedAt != null) ...[
                SizedBox(height: meshXSizes['spacing.3']!),
                _BroadcastResultCard(detail: detail),
              ] else if (!broadcast.confirmationRequired) ...[
                SizedBox(height: meshXSizes['spacing.3']!),
                const _BroadcastRequirementCard(
                  icon: Icons.visibility_outlined,
                  title: '无需确认',
                  message: '这条广播不要求确认，打开详情即表示已查看。',
                ),
              ],
              if (!detail.canSubmit) ...[
                SizedBox(height: meshXSizes['spacing.3']!),
                const _BroadcastRequirementCard(
                  icon: Icons.info_outline,
                  title: '当前不能办理',
                  message: '当前广播已结束、过期或你已不在目标范围内。',
                ),
              ],
            ],
          ),
        ),
      );
    },
  );
}

Color _detailPriorityColor(BuildContext context, BroadcastSummary broadcast) {
  final colors = palette(context);
  return switch (broadcast.priority) {
    'EMERGENCY' => colors['color.status.danger']!,
    'IMPORTANT' => colors['color.status.warning']!,
    _ => colors['color.action.text']!,
  };
}

class _BroadcastDetailHeader extends StatelessWidget {
  const _BroadcastDetailHeader({required this.onBack});
  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) => Row(
    children: [
      MeshXGlassButton(
        tooltip: '返回广播列表',
        nativeSymbol: 'chevron.left',
        onPressed: onBack,
        icon: const Icon(Icons.chevron_left),
      ),
      SizedBox(width: meshXSizes['spacing.3']!),
      Expanded(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '广播',
              style: TextStyle(
                color: palette(context)['color.text.secondary'],
                fontSize: meshXSizes['typography.caption.size']!,
              ),
            ),
            SizedBox(height: meshXSizes['spacing.1']!),
            Text('广播详情', style: Theme.of(context).textTheme.titleLarge),
          ],
        ),
      ),
    ],
  );
}

class _BroadcastContentCard extends StatelessWidget {
  const _BroadcastContentCard({required this.child});
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

class _BroadcastDeadlineCard extends StatelessWidget {
  const _BroadcastDeadlineCard({required this.broadcast});
  final BroadcastSummary broadcast;

  String get _label {
    final deadline = broadcast.deadlineAt;
    if (deadline == null) return '未设截止时间';
    final local = deadline.toLocal();
    final formatted =
        '${local.year.toString().padLeft(4, '0')}-'
        '${local.month.toString().padLeft(2, '0')}-'
        '${local.day.toString().padLeft(2, '0')} '
        '${local.hour.toString().padLeft(2, '0')}:'
        '${local.minute.toString().padLeft(2, '0')}';
    return broadcast.expired ? '已于 $formatted 截止' : '截止 $formatted';
  }

  @override
  Widget build(BuildContext context) => _BroadcastContentCard(
    child: Row(
      children: [
        Icon(
          Icons.schedule_outlined,
          color: palette(context)['color.text.secondary'],
        ),
        SizedBox(width: meshXSizes['spacing.3']!),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('截止时间', style: Theme.of(context).textTheme.titleSmall),
              SizedBox(height: meshXSizes['spacing.1']!),
              Text(
                _label,
                style: TextStyle(
                  color: palette(context)['color.text.secondary'],
                  fontSize: meshXSizes['typography.caption.size']!,
                ),
              ),
            ],
          ),
        ),
      ],
    ),
  );
}

class _StatusCard extends StatelessWidget {
  const _StatusCard({required this.detail});
  final BroadcastDetail detail;

  String get _confirmation =>
      broadcastConfirmationLabel(detail.receiver?.confirmStatus);

  @override
  Widget build(BuildContext context) => _BroadcastContentCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('任务状态', style: Theme.of(context).textTheme.titleMedium),
        SizedBox(height: meshXSizes['spacing.3']!),
        _StatusLine(
          label: '广播状态',
          value: broadcastStatusLabel(detail.broadcast.status),
        ),
        SizedBox(height: meshXSizes['spacing.2']!),
        _StatusLine(
          label: '接收状态',
          value: broadcastTargetLabel(detail.receiver?.targetStatus),
        ),
        SizedBox(height: meshXSizes['spacing.2']!),
        _StatusLine(label: '当前回执', value: _confirmation),
      ],
    ),
  );
}

class _StatusLine extends StatelessWidget {
  const _StatusLine({required this.label, required this.value});
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Row(
    children: [
      Expanded(
        child: Text(
          label,
          style: TextStyle(
            color: palette(context)['color.text.secondary'],
            fontSize: meshXSizes['typography.body.size']!,
          ),
        ),
      ),
      Flexible(
        child: Text(
          value,
          textAlign: TextAlign.end,
          style: TextStyle(
            color: palette(context)['color.text.primary'],
            fontSize: meshXSizes['typography.body.size']!,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
    ],
  );
}

class _BroadcastRequirementCard extends StatelessWidget {
  const _BroadcastRequirementCard({
    required this.icon,
    required this.title,
    required this.message,
  });
  final IconData icon;
  final String title;
  final String message;

  @override
  Widget build(BuildContext context) => _BroadcastContentCard(
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, color: palette(context)['color.action.text']),
        SizedBox(width: meshXSizes['spacing.3']!),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: Theme.of(context).textTheme.titleSmall),
              SizedBox(height: meshXSizes['spacing.1']!),
              Text(
                message,
                style: TextStyle(
                  color: palette(context)['color.text.secondary'],
                  fontSize: meshXSizes['typography.body.size']!,
                ),
              ),
            ],
          ),
        ),
      ],
    ),
  );
}

class _BroadcastResultCard extends StatelessWidget {
  const _BroadcastResultCard({required this.detail});
  final BroadcastDetail detail;

  @override
  Widget build(BuildContext context) => _BroadcastContentCard(
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(Icons.task_alt, color: palette(context)['color.status.success']),
        SizedBox(width: meshXSizes['spacing.3']!),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '已提交回执（节点已确认）',
                style: Theme.of(context).textTheme.titleSmall,
              ),
              SizedBox(height: meshXSizes['spacing.1']!),
              Text(
                '当前回执：${broadcastConfirmationLabel(detail.receiver?.confirmStatus)}',
                style: TextStyle(
                  color: palette(context)['color.text.secondary'],
                  fontSize: meshXSizes['typography.body.size']!,
                ),
              ),
            ],
          ),
        ),
      ],
    ),
  );
}

class _Notice extends StatelessWidget {
  const _Notice({required this.message, this.error = false, this.onDismiss});
  final String message;
  final bool error;
  final VoidCallback? onDismiss;

  @override
  Widget build(BuildContext context) {
    final colors = palette(context);
    final foreground =
        colors[error ? 'color.status.danger' : 'color.text.secondary']!;
    final radius = BorderRadius.circular(meshXSizes['shape.radius.medium']!);
    return Material(
      color: colors['color.background.muted'],
      borderRadius: radius,
      child: Ink(
        padding: EdgeInsets.all(meshXSizes['spacing.3']!),
        decoration: BoxDecoration(
          borderRadius: radius,
          border: Border.all(color: foreground),
        ),
        child: Row(
          children: [
            Icon(
              error ? Icons.error_outline : Icons.info_outline,
              color: foreground,
            ),
            SizedBox(width: meshXSizes['spacing.2']!),
            Expanded(
              child: Text(message, style: TextStyle(color: foreground)),
            ),
            if (onDismiss != null)
              IconButton(
                key: const Key('broadcast-notice-dismiss'),
                tooltip: '关闭提示',
                onPressed: onDismiss,
                icon: const Icon(Icons.close),
              ),
          ],
        ),
      ),
    );
  }
}
