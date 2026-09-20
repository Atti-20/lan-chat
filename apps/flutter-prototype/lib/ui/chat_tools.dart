import 'dart:async';
import 'package:flutter/material.dart';
import '../chat_controller.dart';
import '../data/models.dart';
import '../data/temporary_rooms.dart';

class MessageSearchPage extends StatefulWidget {
  const MessageSearchPage({super.key, required this.chat});
  final ChatController chat;
  @override
  State<MessageSearchPage> createState() => _MessageSearchPageState();
}

class _MessageSearchPageState extends State<MessageSearchPage> {
  final input = TextEditingController();
  List<ChatMessage> results = [];
  String? error;
  bool busy = false;
  int request = 0;
  String? resultScope;
  late final api = widget.chat.api!;
  late final StreamSubscription<Object?> invalidations;
  @override
  void initState() {
    super.initState();
    widget.chat.addListener(changed);
    invalidations = widget.chat.invalidations.listen((_) => changed());
  }

  void changed() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    request++;
    input.dispose();
    widget.chat.removeListener(changed);
    unawaited(invalidations.cancel());
    super.dispose();
  }

  Future<void> search() async {
    final id = ++request, scope = widget.chat.connectionScope;
    setState(() {
      busy = true;
      error = null;
      results = [];
    });
    try {
      final values = await api.searchMessages(input.text);
      if (!mounted ||
          id != request ||
          widget.chat.api != api ||
          scope != widget.chat.connectionScope) {
        return;
      }
      setState(() {
        results = values;
        resultScope = scope;
      });
    } catch (e) {
      if (mounted && id == request) {
        setState(() => error = widget.chat.describe(e));
      }
    } finally {
      if (mounted && id == request) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final visible =
        widget.chat.api == api &&
            widget.chat.online &&
            resultScope == widget.chat.connectionScope
        ? results.where(widget.chat.allowsMessage).toList()
        : <ChatMessage>[];
    return Scaffold(
      appBar: AppBar(title: const Text('搜索消息')),
      body: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.all(16),
              child: TextField(
                controller: input,
                autofocus: true,
                onSubmitted: (_) => search(),
                decoration: InputDecoration(
                  labelText: '至少输入2个字符',
                  suffixIcon: IconButton(
                    tooltip: '搜索',
                    onPressed: busy ? null : search,
                    icon: const Icon(Icons.search),
                  ),
                ),
              ),
            ),
            if (busy) const LinearProgressIndicator(),
            if (error != null)
              Padding(padding: const EdgeInsets.all(16), child: Text(error!)),
            Expanded(
              child: ListView.builder(
                itemCount: visible.length,
                itemBuilder: (context, index) {
                  final m = visible[index];
                  return ListTile(
                    title: Text(
                      m.displayContent,
                      maxLines: 4,
                      overflow: TextOverflow.ellipsis,
                    ),
                    subtitle: Text(
                      '${widget.chat.senderName(m.fromUserId)} · ${m.createdAt.toLocal()}',
                    ),
                    onTap: () async {
                      final matches = widget.chat.conversations.where(
                        (c) => c.id == m.conversationId,
                      );
                      if (matches.isEmpty) return;
                      await widget.chat.select(matches.first);
                      final focused = await widget.chat.focusMessage(m);
                      if (!focused && mounted) {
                        setState(() => error = '消息已失效，请重新搜索');
                        return;
                      }
                      if (context.mounted && widget.chat.api == api) {
                        Navigator.pop(context);
                      }
                    },
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class TemporaryRoomsPage extends StatefulWidget {
  const TemporaryRoomsPage({super.key, required this.chat});
  final ChatController chat;
  @override
  State<TemporaryRoomsPage> createState() => _TemporaryRoomsPageState();
}

class _TemporaryRoomsPageState extends State<TemporaryRoomsPage> {
  late final api = widget.chat.api!;
  List<TemporaryRoom> rooms = [];
  bool busy = false;
  String? error;
  @override
  void initState() {
    super.initState();
    unawaited(refresh());
  }

  bool get current => mounted && widget.chat.api == api;
  Future<void> refresh() async {
    if (!current || busy) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final values = await api.temporaryRooms();
      if (current) setState(() => rooms = values);
    } catch (e) {
      if (current) setState(() => error = widget.chat.describe(e));
    } finally {
      if (current) setState(() => busy = false);
    }
  }

  Future<void> add(bool join) async {
    final input = TextEditingController();
    bool files = false;
    final text = await showDialog<String>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, update) => AlertDialog(
          title: Text(join ? '加入临时房间' : '新建临时房间'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: input,
                maxLength: join ? 32 : 50,
                decoration: InputDecoration(
                  labelText: join ? '房间码' : '名称（8小时后冻结，最多20人）',
                ),
              ),
              if (!join)
                CheckboxListTile(
                  title: const Text('允许上传与下载文件'),
                  value: files,
                  onChanged: (v) => update(() => files = v ?? false),
                ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(ctx, input.text.trim()),
              child: const Text('确定'),
            ),
          ],
        ),
      ),
    );
    input.dispose();
    if (!current || text == null || text.isEmpty || busy) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final room = join
          ? await api.joinTemporaryRoom(text)
          : await api.createTemporaryRoom(text, files: files);
      if (!current) return;
      await widget.chat.openTemporaryRoom(room);
      if (mounted && current) Navigator.pop(context);
    } catch (e) {
      if (current) setState(() => error = widget.chat.describe(e));
    } finally {
      if (current) setState(() => busy = false);
    }
  }

  Future<void> leave(TemporaryRoom room) async {
    final yes = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('退出 ${room.name}？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('退出'),
          ),
        ],
      ),
    );
    if (!current || yes != true || busy) return;
    setState(() => busy = true);
    try {
      await api.leaveTemporaryRoom(room.id);
      if (!current) return;
      await widget.chat.refreshConversations();
      rooms.removeWhere((r) => r.id == room.id);
    } catch (e) {
      if (current) error = widget.chat.describe(e);
    } finally {
      if (current) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('临时房间')),
    body: SafeArea(
      child: Column(
        children: [
          Wrap(
            spacing: 12,
            children: [
              FilledButton(
                onPressed: busy ? null : () => add(false),
                child: const Text('新建'),
              ),
              OutlinedButton(
                onPressed: busy ? null : () => add(true),
                child: const Text('输入房间码加入'),
              ),
            ],
          ),
          if (busy) const LinearProgressIndicator(),
          if (error != null)
            Padding(padding: const EdgeInsets.all(16), child: Text(error!)),
          Expanded(
            child: RefreshIndicator(
              onRefresh: refresh,
              child: ListView(
                children: [
                  for (final room in rooms)
                    ListTile(
                      title: Text(room.name),
                      subtitle: Text(
                        '${room.available ? '房间码 ${room.code}' : '已到期或只读'}\n到期：${room.expiresAt.toLocal()}',
                      ),
                      isThreeLine: true,
                      onTap: busy
                          ? null
                          : () async {
                              await widget.chat.openTemporaryRoom(room);
                              if (context.mounted && current) {
                                Navigator.pop(context);
                              }
                            },
                      trailing: IconButton(
                        tooltip: '退出房间',
                        onPressed: busy ? null : () => leave(room),
                        icon: const Icon(Icons.exit_to_app),
                      ),
                    ),
                ],
              ),
            ),
          ),
        ],
      ),
    ),
  );
}
