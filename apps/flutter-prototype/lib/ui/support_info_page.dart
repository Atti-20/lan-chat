import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../application/platform_coordinator.dart';
import '../chat_controller.dart';
import '../core/platform_ports.dart';

class SupportInfoPage extends StatefulWidget {
  const SupportInfoPage({
    super.key,
    required this.chat,
    required this.platform,
  });

  final ChatController chat;
  final PlatformCoordinator platform;

  @override
  State<SupportInfoPage> createState() => _SupportInfoPageState();
}

class _SupportInfoPageState extends State<SupportInfoPage> {
  CapabilityResult<RuntimeInfo>? _runtime;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    widget.chat.addListener(_chatChanged);
    unawaited(_load());
  }

  void _chatChanged() {
    if (mounted) {
      setState(() {});
    }
  }

  @override
  void dispose() {
    widget.chat.removeListener(_chatChanged);
    super.dispose();
  }

  Future<void> _load() async {
    if (mounted) {
      setState(() => _loading = true);
    }
    final result = await widget.platform.readRuntimeInfo();
    if (mounted) {
      setState(() {
        _runtime = result;
        _loading = false;
      });
    }
  }

  String get _nodeIdentifier {
    final uri = widget.chat.api?.origin;
    final name = _clean(widget.chat.node?.name ?? '未知节点');
    if (uri == null) return '未连接';
    final host = uri.host.contains(':') ? '[${uri.host}]' : uri.host;
    return '$name ($host${uri.hasPort ? ':${uri.port}' : ''})';
  }

  String get _phase => switch (widget.chat.connectionPhase) {
    'online' => 'ONLINE',
    'connecting' => 'CONNECTING',
    'background' => 'BACKGROUND',
    'offline' => 'OFFLINE',
    _ => 'SIGNED_OUT',
  };

  String _clean(String value) {
    final cleaned = value.replaceAll(RegExp(r'[\x00-\x1f\x7f]'), ' ').trim();
    return cleaned.length <= 80 ? cleaned : cleaned.substring(0, 80);
  }

  String? get _preview {
    final info = _runtime?.value;
    if (info == null) return null;
    return [
      'MeshX ${info.appVersion} (${info.buildNumber})',
      '${info.osName} ${info.osVersion}',
      '节点：$_nodeIdentifier',
      '连接：$_phase',
      '错误码：${widget.chat.lastErrorCode ?? 'NONE'}',
    ].join('\n');
  }

  Future<void> _copy() async {
    final preview = _preview;
    if (preview == null) return;
    await Clipboard.setData(ClipboardData(text: preview));
    if (mounted) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('支持信息已复制')));
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('支持信息')),
    body: SafeArea(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 36),
        children: [
          const Text('请先预览，再复制给可信的支持人员。此处不包含聊天内容、令牌、Cookie、凭据或文件路径。'),
          const SizedBox(height: 16),
          if (_loading)
            const Center(child: CircularProgressIndicator())
          else if (_preview case final preview?)
            Semantics(
              label: '脱敏支持信息预览',
              readOnly: true,
              child: Container(
                key: const Key('support-preview'),
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(14),
                ),
                child: SelectableText(preview),
              ),
            )
          else
            Semantics(
              liveRegion: true,
              child: Text(
                '无法读取应用与系统版本（${_runtime?.reason.isNotEmpty == true ? _runtime!.reason : 'runtimeInfoUnavailable'}）',
                key: const Key('support-error'),
              ),
            ),
          const SizedBox(height: 16),
          FilledButton.icon(
            key: const Key('copy-support-info'),
            onPressed: _preview == null ? null : _copy,
            icon: const Icon(Icons.copy_outlined),
            label: const Text('复制以上支持信息'),
          ),
          if (!_loading && _preview == null)
            OutlinedButton(
              key: const Key('retry-support-info'),
              onPressed: _load,
              child: const Text('重新读取'),
            ),
        ],
      ),
    ),
  );
}
