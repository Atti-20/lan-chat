import 'package:flutter/material.dart';
import '../chat_controller.dart';
import '../core/models.dart';
import '../application/platform_coordinator.dart';
import '../data/broadcast_models.dart';
import '../data/friends_models.dart';

class BroadcastManagementPage extends StatefulWidget {
  const BroadcastManagementPage({super.key, required this.chat, this.platform});
  final ChatController chat;
  final PlatformCoordinator? platform;
  @override
  State<BroadcastManagementPage> createState() =>
      _BroadcastManagementPageState();
}

class _BroadcastManagementPageState extends State<BroadcastManagementPage> {
  late final api = widget.chat.api!;
  List<BroadcastSummary> items = [];
  List<FriendContact> friends = [];
  List<Json> recipients = [];
  Json? stats;
  BroadcastSummary? selected;
  String? error;
  bool busy = false;
  bool get current => mounted && widget.chat.api == api;
  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> run(Future<void> Function() action) async {
    if (busy || !current) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await action();
    } catch (e) {
      if (current) error = widget.chat.describe(e);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> load() => run(() async {
    final values = await api.broadcasts();
    final contacts = await api.friends();
    if (!current) return;
    items = values;
    friends = contacts;
    if (selected != null) await refreshDetail();
  });
  Future<void> refreshDetail() async {
    final target = selected;
    if (target == null) return;
    stats = null;
    recipients = [];
    final summary = await api.broadcastStats(target.id);
    final rows = await api.broadcastRecipients(target.id);
    if (current && selected?.id == target.id) {
      stats = summary;
      recipients = rows;
    }
  }

  Future<bool> confirm(String message) async =>
      await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('确认操作'),
          content: Text(message),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('确认'),
            ),
          ],
        ),
      ) ==
      true;
  Future<List<int>?> chooseUsers() async {
    final selected = <int>{};
    return showDialog<List<int>>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, update) => AlertDialog(
          title: const Text('选择联系人'),
          content: SizedBox(
            width: 360,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  for (final f in friends)
                    CheckboxListTile(
                      value: selected.contains(f.userId),
                      title: Text(f.displayName),
                      onChanged: (value) => update(() {
                        if (value == true) {
                          selected.add(f.userId);
                        } else {
                          selected.remove(f.userId);
                        }
                      }),
                    ),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(ctx, selected.toList()),
              child: const Text('确定'),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> publish() async {
    final created = await Navigator.push<BroadcastSummary>(
      context,
      MaterialPageRoute(
        builder: (_) => BroadcastComposePage(
          chat: widget.chat,
          platform: widget.platform,
          friends: friends,
        ),
      ),
    );
    if (created == null || !current) return;
    await run(() async {
      if (!current) return;
      items = [created, ...items];
      selected = created;
      await refreshDetail();
    });
  }

  Future<void> export() => run(() async {
    final item = selected, platform = widget.platform;
    if (item == null || platform == null) return;
    final bytes = await api.exportBroadcast(item.id);
    if (!current || selected?.id != item.id) return;
    final result = await platform.shareDownloadedFile(
      name: 'broadcast-${item.id}.xlsx',
      mime: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      bytes: bytes,
    );
    if (!result.ok) throw const FormatException('导出文件分享未完成');
  });
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('广播发布与管理'),
      actions: [
        IconButton(
          tooltip: '发布广播',
          onPressed: busy ? null : publish,
          icon: const Icon(Icons.add),
        ),
      ],
    ),
    body: !current
        ? const Center(child: Text('账号已切换'))
        : SafeArea(
            child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                if (busy) const LinearProgressIndicator(),
                if (error != null)
                  Text(
                    error!,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                OutlinedButton(
                  onPressed: busy ? null : load,
                  child: const Text('刷新'),
                ),
                for (final item in items)
                  ListTile(
                    title: Text(item.title),
                    subtitle: Text(
                      item.senderId == widget.chat.session?.userId
                          ? '我发布的广播'
                          : '接收到的广播 · 管理需授权',
                    ),
                    selected: selected?.id == item.id,
                    onTap: busy
                        ? null
                        : () => run(() async {
                            selected = item;
                            await refreshDetail();
                          }),
                  ),
                if (selected != null && stats != null) ...[
                  if (widget.platform != null)
                    OutlinedButton(
                      onPressed: busy ? null : export,
                      child: const Text('导出 Excel'),
                    ),
                  const Divider(),
                  Text(
                    selected!.title,
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  Wrap(
                    spacing: 16,
                    children: [
                      for (final entry in {
                        '目标人数': 'targetCount',
                        '已送达': 'deliveredCount',
                        '已查看': 'viewedCount',
                        '已确认': 'confirmedCount',
                        '已执行': 'executedCount',
                        '需要支援': 'needSupportCount',
                      }.entries)
                        Text('${entry.key}：${stats![entry.value] ?? 0}'),
                    ],
                  ),
                  Wrap(
                    spacing: 8,
                    children: [
                      OutlinedButton(
                        onPressed: busy
                            ? null
                            : () async {
                                final ids = await chooseUsers();
                                if (ids == null || ids.isEmpty || !current) {
                                  return;
                                }
                                await run(() async {
                                  await api.updateBroadcastTargets(
                                    selected!.id,
                                    add: ids,
                                  );
                                  await refreshDetail();
                                });
                              },
                        child: const Text('添加接收者'),
                      ),
                      OutlinedButton(
                        onPressed: busy
                            ? null
                            : () async {
                                if (!await confirm('取消此广播并通知接收者？') ||
                                    !current) {
                                  return;
                                }
                                await run(() async {
                                  await api.cancelBroadcast(selected!.id);
                                  await refreshDetail();
                                });
                              },
                        child: const Text('取消广播'),
                      ),
                      TextButton(
                        onPressed: busy
                            ? null
                            : () async {
                                if (!await confirm('删除此广播？此操作无法撤销。') ||
                                    !current) {
                                  return;
                                }
                                await run(() async {
                                  final id = selected!.id;
                                  await api.deleteBroadcast(id);
                                  if (!current) return;
                                  selected = null;
                                  stats = null;
                                  recipients = [];
                                  items.removeWhere((b) => b.id == id);
                                });
                              },
                        child: const Text('删除广播'),
                      ),
                    ],
                  ),
                  for (final row in recipients)
                    ListTile(
                      title: Text('${row['nickname'] ?? row['username']}'),
                      subtitle: Text(
                        '${row['confirmStatus']} · ${row['viewedAt'] == null ? '未查看' : '已查看'}',
                      ),
                      trailing: PopupMenuButton<String>(
                        enabled: !busy,
                        onSelected: (action) async {
                          if (action == 'remove' && !await confirm('移除此接收者？')) {
                            return;
                          }
                          if (!current) return;
                          await run(() async {
                            final id = integer(row['userId']);
                            if (action == 'remove') {
                              await api.updateBroadcastTargets(
                                selected!.id,
                                remove: [id],
                              );
                            } else {
                              await api.remindBroadcast(selected!.id, id);
                            }
                            await refreshDetail();
                          });
                        },
                        itemBuilder: (_) => [
                          const PopupMenuItem(
                            value: 'remind',
                            child: Text('提醒'),
                          ),
                          const PopupMenuItem(
                            value: 'remove',
                            child: Text('移除'),
                          ),
                        ],
                      ),
                    ),
                ],
              ],
            ),
          ),
  );
}

class BroadcastComposePage extends StatefulWidget {
  const BroadcastComposePage({
    super.key,
    required this.chat,
    required this.friends,
    this.platform,
  });
  final ChatController chat;
  final List<FriendContact> friends;
  final PlatformCoordinator? platform;
  @override
  State<BroadcastComposePage> createState() => _BroadcastComposePageState();
}

class _BroadcastComposePageState extends State<BroadcastComposePage> {
  final title = TextEditingController(), content = TextEditingController();
  final receivers = <int>{};
  String priority = 'NORMAL';
  bool all = false,
      confirmation = true,
      imageProof = false,
      locationProof = false,
      repeat = false,
      bypass = false;
  int hours = 24;
  String? error;
  final imageIds = <int>[];
  Json? contentLocation;
  Future<void> addImage() async {
    final platform = widget.platform;
    if (platform == null || busy || platform.busy || imageIds.length >= 4) {
      return;
    }
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await platform.pickFile(maxBytes: 5 * 1024 * 1024, photos: true);
      final file = platform.selectedFile;
      if (!platform.fileStatus.ok || file == null) return;
      if (!file.mime.startsWith('image/')) throw const FormatException('请选择图片');
      final bytes = await platform.readSelectedFile(maxBytes: 5 * 1024 * 1024);
      if (!bytes.ok || bytes.value == null) {
        throw const FormatException('图片读取失败');
      }
      if (!mounted || widget.chat.api != api) return;
      final image = await api.uploadBroadcastImage(
        name: file.name,
        mime: file.mime,
        bytes: bytes.value!,
      );
      if (mounted && widget.chat.api == api) {
        setState(() => imageIds.add(image.id));
      }
    } catch (failure) {
      if (mounted) setState(() => error = widget.chat.describe(failure));
    } finally {
      await platform.clearFile();
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> addLocation() async {
    final port = widget.platform?.location;
    if (port == null || busy) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final proof = await port.currentLocation();
      if (!proof.ok || proof.value?.fresh != true) {
        throw const FormatException('未获得有效位置，请检查定位权限');
      }
      if (mounted && widget.chat.api == api) {
        setState(() => contentLocation = proof.value!.toJson());
      }
    } catch (failure) {
      if (mounted) setState(() => error = widget.chat.describe(failure));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  void dispose() {
    title.dispose();
    content.dispose();
    super.dispose();
  }

  bool busy = false;
  late final api = widget.chat.api!;
  Future<void> submit() async {
    if (busy || widget.chat.api != api) return;
    if (title.text.trim().isEmpty ||
        title.text.trim().length > 100 ||
        content.text.trim().isEmpty ||
        content.text.trim().length > 10000 ||
        (!all && receivers.isEmpty)) {
      setState(() => error = '请填写标题、正文并选择接收者');
      return;
    }
    final draft = <String, dynamic>{
      'contentImageFileIds': imageIds.toList(),
      if (contentLocation != null) 'contentLocation': contentLocation,
      'title': title.text.trim(),
      'content': content.text.trim(),
      'priority': priority,
      'scopeType': all ? 'ALL' : 'USERS',
      'receiverIds': receivers.toList(),
      'confirmationRequired': confirmation,
      'confirmationOptions': confirmation
          ? ['RECEIVED', 'EXECUTED', 'NEED_SUPPORT']
          : [],
      'requireImageProof': imageProof,
      'requireLocationProof': locationProof,
      'repeatReminder': repeat,
      'bypassMute': bypass,
      'deadlineAt': DateTime.now()
          .add(Duration(hours: hours))
          .toIso8601String(),
    };
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final created = await api.publishBroadcast(draft);
      if (mounted && widget.chat.api == api) Navigator.pop(context, created);
    } catch (failure) {
      if (mounted) setState(() => error = widget.chat.describe(failure));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('发布广播')),
    body: SafeArea(
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (error != null) Text(error!),
          TextField(
            controller: title,
            maxLength: 100,
            decoration: const InputDecoration(labelText: '标题'),
          ),
          TextField(
            controller: content,
            minLines: 4,
            maxLines: 10,
            maxLength: 10000,
            decoration: const InputDecoration(labelText: '正文'),
          ),
          Wrap(
            spacing: 8,
            children: [
              if (widget.platform != null)
                OutlinedButton(
                  onPressed: busy || imageIds.length >= 4 ? null : addImage,
                  child: const Text('添加正文图片'),
                ),
              for (final id in imageIds)
                InputChip(
                  label: Text('图片 ${imageIds.indexOf(id) + 1}'),
                  onDeleted: busy
                      ? null
                      : () => setState(() => imageIds.remove(id)),
                ),
              if (widget.platform?.location != null)
                OutlinedButton(
                  onPressed: busy ? null : addLocation,
                  child: const Text('附上当前位置'),
                ),
              if (contentLocation != null)
                InputChip(
                  label: const Text('已附位置'),
                  onDeleted: busy
                      ? null
                      : () => setState(() => contentLocation = null),
                ),
            ],
          ),
          DropdownButtonFormField<String>(
            initialValue: priority,
            decoration: const InputDecoration(labelText: '优先级'),
            items: [
              for (final e in {
                'NORMAL': '普通',
                'IMPORTANT': '重要',
                'EMERGENCY': '紧急',
              }.entries)
                DropdownMenuItem(value: e.key, child: Text(e.value)),
            ],
            onChanged: (v) => setState(() => priority = v!),
          ),
          DropdownButtonFormField<int>(
            initialValue: hours,
            decoration: const InputDecoration(labelText: '截止时间'),
            items: [
              for (final h in [1, 8, 24, 72, 168])
                DropdownMenuItem(value: h, child: Text('$h小时后')),
            ],
            onChanged: (v) => setState(() => hours = v!),
          ),
          SwitchListTile(
            title: const Text('全节点范围（需要相应权限）'),
            value: all,
            onChanged: (v) => setState(() => all = v),
          ),
          if (!all)
            for (final f in widget.friends)
              CheckboxListTile(
                title: Text(f.displayName),
                value: receivers.contains(f.userId),
                onChanged: (v) => setState(() {
                  if (v == true) {
                    receivers.add(f.userId);
                  } else {
                    receivers.remove(f.userId);
                  }
                }),
              ),
          SwitchListTile(
            title: const Text('要求确认'),
            value: confirmation,
            onChanged: (v) => setState(() {
              confirmation = v;
              if (!v) {
                imageProof = false;
                locationProof = false;
              }
            }),
          ),
          if (confirmation) ...[
            SwitchListTile(
              title: const Text('执行时要求图片凭证'),
              value: imageProof,
              onChanged: (v) => setState(() => imageProof = v),
            ),
            SwitchListTile(
              title: const Text('执行时要求定位凭证'),
              value: locationProof,
              onChanged: (v) => setState(() => locationProof = v),
            ),
          ],
          SwitchListTile(
            title: const Text('重复提醒'),
            value: repeat,
            onChanged: (v) => setState(() => repeat = v),
          ),
          SwitchListTile(
            title: const Text('绕过免打扰'),
            value: bypass,
            onChanged: (v) => setState(() => bypass = v),
          ),
          FilledButton(
            onPressed: busy ? null : submit,
            child: const Text('发布广播'),
          ),
        ],
      ),
    ),
  );
}
