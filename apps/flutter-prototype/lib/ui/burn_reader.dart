import 'dart:async';
import 'package:flutter/material.dart';
import '../chat_controller.dart';
import '../core/models.dart';

class BurnReader extends StatefulWidget {
  const BurnReader({super.key, required this.chat, required this.message});
  final ChatController chat;
  final ChatMessage message;
  @override
  State<BurnReader> createState() => _BurnReaderState();
}

class _BurnReaderState extends State<BurnReader> with WidgetsBindingObserver {
  String? _body, _error;
  Timer? _timer;
  late final String _scope = widget.chat.connectionScope;
  DateTime? _deadline;
  bool _closed = false;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    widget.chat.addListener(_changed);
    _read();
  }

  Future<void> _read() async {
    try {
      final body = await widget.chat.consumeBurn(widget.message);
      if (!mounted || _closed) return;
      if (body == null) {
        setState(() => _error = '消息已失效或连接发生变化');
        return;
      }
      setState(() {
        _body = body;
        _deadline = DateTime.now().add(
          Duration(seconds: (widget.message.burnDuration ?? 5).clamp(1, 60)),
        );
      });
      _timer = Timer.periodic(const Duration(milliseconds: 200), (_) {
        if (!DateTime.now().isBefore(_deadline!)) {
          _close();
        } else if (mounted) {
          setState(() {});
        }
      });
    } catch (e) {
      if (mounted && !_closed) setState(() => _error = widget.chat.describe(e));
    }
  }

  void _changed() {
    if ((!widget.chat.online &&
            (!widget.chat.usesMutationRecovery || _body != null)) ||
        widget.chat.connectionScope != _scope ||
        widget.chat.active?.id != widget.message.conversationId ||
        !widget.chat.conversationAccessible(widget.message.conversationId)) {
      _close();
    }
  }

  void _close() {
    if (_closed) return;
    _closed = true;
    _body = null;
    _timer?.cancel();
    if (mounted) {
      setState(() {});
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) Navigator.of(context).pop();
      });
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) _close();
  }

  @override
  void dispose() {
    _closed = true;
    _body = null;
    _timer?.cancel();
    widget.chat.removeListener(_changed);
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(
      _deadline == null
          ? '阅后即焚'
          : '${_deadline!.difference(DateTime.now()).inMilliseconds.clamp(0, 60000) ~/ 1000 + 1}秒后关闭',
    ),
    content: SingleChildScrollView(
      child: Text(_body ?? _error ?? (_closed ? '已关闭' : '正在确认销毁，请稍候…')),
    ),
    actions: [TextButton(onPressed: _close, child: const Text('关闭'))],
  );
}
