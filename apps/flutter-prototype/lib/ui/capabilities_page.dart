import 'dart:async';
import 'package:flutter/material.dart';
import '../application/platform_coordinator.dart';
import '../core/platform_ports.dart';

class CapabilitiesPage extends StatefulWidget {
  const CapabilitiesPage({super.key, required this.platform});
  final PlatformCoordinator platform;
  @override
  State<CapabilitiesPage> createState() => _CapabilitiesPageState();
}

class _CapabilitiesPageState extends State<CapabilitiesPage> {
  @override
  void initState() {
    super.initState();
    widget.platform.chatViewVisible = false;
    unawaited(widget.platform.requestNotifications(request: false));
  }

  @override
  void dispose() {
    widget.platform.chatViewVisible = true;
    unawaited(widget.platform.clearFile());
    super.dispose();
  }

  String resultText(CapabilityResult<void> result) => switch (result.reason) {
    'fileTooLarge' => '文件超过大小限制，请选择 25 MB 以内的文件',
    'fileMissing' => '所选文件已不存在，请重新选择',
    'fileUnreadable' || 'fileUnavailable' => '文件暂不可读取，请重试或重新选择',
    'shareSheetPresented' => '已打开系统分享，发送结果请在目标应用确认',
    'systemShareCompleted' => '系统分享操作完成',
    'settingsOpenAccepted' => '系统已接受打开设置请求；请确认已实际到达 MeshX 设置页',
    'settingsOpenRejected' ||
    'settingsUrlUnavailable' => '系统未打开应用设置，请按下方路径手动恢复',
    'background' => '请返回 MeshX 前台后再打开系统设置',
    _ => capabilityMessage(result.status),
  };
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('设备权限与文件')),
    body: SafeArea(
      child: AnimatedBuilder(
        animation: widget.platform,
        builder: (context, _) {
          final p = widget.platform;
          return ListView(
            padding: const EdgeInsets.all(20),
            children: [
              Text('消息提醒', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 8),
              const Text('系统提醒仅显示有新消息。正在查看的会话和恢复连接时的历史消息保持安静。'),
              const SizedBox(height: 12),
              Text(
                resultText(p.notificationStatus),
                key: const Key('notification-status'),
              ),
              const SizedBox(height: 12),
              FilledButton.icon(
                key: const Key('notification-permission'),
                onPressed: () => p.requestNotifications(),
                icon: const Icon(Icons.notifications_outlined),
                label: const Text('开启消息提醒'),
              ),
              OutlinedButton.icon(
                key: const Key('open-system-settings'),
                onPressed: p.busy ? null : p.openSettings,
                icon: const Icon(Icons.settings_outlined),
                label: const Text('打开系统设置'),
              ),
              if (p.settingsStatus case final result?)
                Text(resultText(result), key: const Key('settings-status')),
              if (!p.notificationStatus.ok ||
                  p.settingsStatus?.ok == false) ...[
                const SizedBox(height: 12),
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(14),
                    child: Text(
                      Theme.of(context).platform == TargetPlatform.iOS
                          ? '手动恢复：设置 → App → MeshX 体验版 → 通知/本地网络。返回后点击“重新检查”。'
                          : '手动恢复：设置 → 应用 → MeshX 体验版 → 通知/权限。返回后点击“重新检查”。',
                      key: const Key('settings-fallback'),
                    ),
                  ),
                ),
                TextButton.icon(
                  key: const Key('recheck-permissions'),
                  onPressed: p.busy
                      ? null
                      : () => p.requestNotifications(request: false),
                  icon: const Icon(Icons.refresh),
                  label: const Text('重新检查'),
                ),
              ],
              const SizedBox(height: 28),
              Text('文件与分享', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 8),
              const Text('选择的文件用于系统分享，最大 25 MB。退出此页面会移除临时副本。'),
              const SizedBox(height: 12),
              Text(resultText(p.fileStatus), key: const Key('file-status')),
              if (p.selectedFile case final file?) ...[
                const SizedBox(height: 12),
                Text(file.name, key: const Key('selected-file-name')),
                Text('${file.size} 字节 · ${file.mime}'),
              ],
              const SizedBox(height: 12),
              FilledButton.icon(
                key: const Key('pick-file'),
                onPressed: p.busy ? null : () => p.pickFile(),
                icon: const Icon(Icons.attach_file),
                label: const Text('选择文件'),
              ),
              OutlinedButton.icon(
                key: const Key('share-file'),
                onPressed: p.busy || p.selectedFile == null
                    ? null
                    : p.shareFile,
                icon: const Icon(Icons.share_outlined),
                label: const Text('分享所选文件'),
              ),
              if (p.selectedFile != null)
                TextButton(
                  key: const Key('clear-file'),
                  onPressed: p.busy ? null : p.clearFile,
                  child: const Text('移除临时副本'),
                ),
              if (p.busy)
                const Padding(
                  padding: EdgeInsets.all(12),
                  child: Center(child: CircularProgressIndicator()),
                ),
              const SizedBox(height: 24),
              const Text('应用恢复前台后会重新确认连接并补齐消息。系统挂起期间的局域网消息会在恢复后同步。'),
            ],
          );
        },
      ),
    ),
  );
}
