import 'dart:async';
import 'package:flutter/material.dart';
import '../application/broadcasts_controller.dart';

class BroadcastImagePage extends StatefulWidget {
  const BroadcastImagePage({
    super.key,
    required this.controller,
    required this.broadcastId,
    required this.url,
    required this.index,
  });
  final BroadcastsController controller;
  final int broadcastId, index;
  final String url;
  @override
  State<BroadcastImagePage> createState() => _BroadcastImagePageState();
}

class _BroadcastImagePageState extends State<BroadcastImagePage> {
  MemoryImage? _image;
  String? _error;
  bool _loading = false;
  int _generation = 0;
  late final int? _userId;

  bool get _authorized {
    final controller = widget.controller;
    final detail = controller.detail;
    return identical(controller.chat.api, controller.api) &&
        controller.chat.session?.userId == _userId &&
        _userId != null &&
        detail?.broadcast.id == widget.broadcastId &&
        detail?.receiver?.targetStatus == 'ACTIVE' &&
        detail!.contentImageUrls.contains(widget.url);
  }

  @override
  void initState() {
    super.initState();
    _userId = widget.controller.chat.session?.userId;
    widget.controller.addListener(_checkAccess);
    widget.controller.chat.addListener(_checkAccess);
    unawaited(_load());
  }

  void _clearImage() {
    final previous = _image;
    _image = null;
    if (previous != null) unawaited(previous.evict());
  }

  void _checkAccess() {
    if (mounted && !_authorized) {
      ++_generation;
      setState(() {
        _clearImage();
        _loading = false;
        _error = '广播图片已不可用，请返回广播列表';
      });
    }
  }

  Future<void> _load() async {
    if (_loading) return;
    if (!_authorized) {
      _checkAccess();
      return;
    }
    final generation = ++_generation;
    setState(() {
      _loading = true;
      _error = null;
      _clearImage();
    });
    try {
      final bytes = await widget.controller.api.broadcastImageBytes(
        widget.broadcastId,
        widget.url,
      );
      if (!mounted || generation != _generation || !_authorized) return;
      setState(() => _image = MemoryImage(bytes));
    } catch (failure) {
      if (mounted && generation == _generation) {
        setState(() => _error = widget.controller.chat.describe(failure));
      }
    } finally {
      if (mounted && generation == _generation) {
        setState(() => _loading = false);
      }
    }
  }

  @override
  void dispose() {
    ++_generation;
    widget.controller.removeListener(_checkAccess);
    widget.controller.chat.removeListener(_checkAccess);
    _clearImage();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text('广播图片 ${widget.index + 1}')),
    body: SafeArea(
      child: Center(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: _loading
              ? const CircularProgressIndicator(semanticsLabel: '正在加载广播图片')
              : _image != null
              ? InteractiveViewer(
                  child: Image(
                    image: _image!,
                    semanticLabel: '广播正文图片 ${widget.index + 1}',
                    fit: BoxFit.contain,
                    errorBuilder: (_, _, _) => const Text('图片内容无法显示，请返回后重试'),
                  ),
                )
              : SingleChildScrollView(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(_error ?? '图片暂不可用', textAlign: TextAlign.center),
                      if (_authorized) ...[
                        const SizedBox(height: 16),
                        OutlinedButton.icon(
                          onPressed: _load,
                          icon: const Icon(Icons.refresh),
                          label: const Text('重新加载'),
                        ),
                      ],
                    ],
                  ),
                ),
        ),
      ),
    ),
  );
}
