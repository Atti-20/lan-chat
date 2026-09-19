import 'dart:async';
import 'dart:convert';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../chat_controller.dart';
import '../application/platform_coordinator.dart';
import '../application/attachment_controller.dart';
import '../application/navigation_counts_controller.dart';
import 'capabilities_page.dart';
import 'friends_page.dart';
import 'groups_page.dart';
import '../data/models.dart';
import '../core/store.dart';
import 'theme.dart';
import 'tokens.g.dart';
import 'profile_page.dart';
import 'attachments/attachment_message.dart';
import 'broadcasts_page.dart';

final meshXRouteObserver = RouteObserver<ModalRoute<dynamic>>();

class MeshXApp extends StatefulWidget {
  const MeshXApp({
    super.key,
    required this.controller,
    this.initialThemeMode = ThemeMode.system,
    this.platform,
    this.preferences,
  });
  final ChatController controller;
  final ThemeMode initialThemeMode;
  final PlatformCoordinator? platform;
  final PreferenceStore? preferences;
  @override
  State<MeshXApp> createState() => _MeshXAppState();
}

class _MeshXAppState extends State<MeshXApp> {
  late ThemeMode _mode;
  int _themeGeneration = 0;
  @override
  void initState() {
    super.initState();
    _mode = widget.initialThemeMode;
    widget.platform?.start();
    unawaited(_loadTheme());
  }

  @override
  void dispose() {
    widget.platform?.dispose();
    widget.controller.dispose();
    super.dispose();
  }

  Future<void> _loadTheme() async {
    final generation = _themeGeneration;
    String? value;
    try {
      value = await widget.preferences?.read('themeMode');
    } catch (_) {
      return;
    }
    if (!mounted || generation != _themeGeneration || value == null) return;
    final mode = switch (value) {
      'system' => ThemeMode.system,
      'light' => ThemeMode.light,
      'dark' => ThemeMode.dark,
      _ => null,
    };
    if (mode != null) setState(() => _mode = mode);
  }

  Future<void> _setTheme(ThemeMode mode) async {
    final generation = ++_themeGeneration;
    await widget.preferences?.write('themeMode', mode.name);
    if (mounted && generation == _themeGeneration) {
      setState(() => _mode = mode);
    }
  }

  void _toggleTheme() {
    final isDark =
        _mode == ThemeMode.dark ||
        (_mode == ThemeMode.system &&
            WidgetsBinding.instance.platformDispatcher.platformBrightness ==
                Brightness.dark);
    unawaited(
      _setTheme(
        isDark ? ThemeMode.light : ThemeMode.dark,
      ).catchError((Object _) {}),
    );
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    navigatorObservers: [meshXRouteObserver],
    title: 'MeshX 体验版',
    debugShowCheckedModeBanner: false,
    theme: meshXTheme(Brightness.light),
    darkTheme: meshXTheme(Brightness.dark),
    themeMode: _mode,
    builder: (context, child) => AnnotatedRegion<SystemUiOverlayStyle>(
      value:
          (Theme.of(context).brightness == Brightness.dark
                  ? SystemUiOverlayStyle.light
                  : SystemUiOverlayStyle.dark)
              .copyWith(
                statusBarColor: Colors.transparent,
                systemNavigationBarColor: Colors.transparent,
              ),
      child: child ?? const SizedBox.shrink(),
    ),
    home: AnimatedBuilder(
      animation: widget.controller,
      builder: (context, _) {
        final c = widget.controller;
        return c.session == null
            ? LoginPage(
                controller: c,
                onToggleTheme: _toggleTheme,
                platform: widget.platform,
              )
            : ChatPage(
                controller: c,
                onToggleTheme: _toggleTheme,
                platform: widget.platform,
                themeMode: _mode,
                onThemeModeChanged: _setTheme,
              );
      },
    ),
  );
}

class LoginPage extends StatefulWidget {
  const LoginPage({
    super.key,
    required this.controller,
    required this.onToggleTheme,
    this.platform,
  });
  final ChatController controller;
  final VoidCallback onToggleTheme;
  final PlatformCoordinator? platform;
  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final _origin = TextEditingController(
    text: const String.fromEnvironment('MESHX_NODE'),
  );
  final _username = TextEditingController();
  final _password = TextEditingController();
  final _form = GlobalKey<FormState>();
  @override
  void dispose() {
    unawaited(widget.controller.stopScan());
    _origin.dispose();
    _username.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _login() async {
    if (!_form.currentState!.validate()) return;
    FocusScope.of(context).unfocus();
    final password = _password.text;
    _password.clear();
    await widget.controller.login(_origin.text, _username.text, password);
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.controller, colors = palette(context);
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        actions: [
          IconButton(
            key: const Key('theme-toggle'),
            tooltip: '切换明暗主题',
            onPressed: widget.onToggleTheme,
            icon: const Icon(CupertinoIcons.moon),
          ),
        ],
      ),
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 440),
            child: ListView(
              padding: const EdgeInsets.fromLTRB(24, 12, 24, 32),
              children: [
                Align(
                  alignment: Alignment.centerLeft,
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(16),
                    child: Image.asset(
                      'assets/meshx.png',
                      width: 64,
                      height: 64,
                    ),
                  ),
                ),
                const SizedBox(height: 24),
                Text(
                  '连接你的空间',
                  style: Theme.of(context).textTheme.headlineMedium,
                ),
                const SizedBox(height: 8),
                Text(
                  '发现身边的 MeshX 节点，\n让消息与协作留在你的网络中。',
                  style: TextStyle(
                    fontSize: 16,
                    height: 1.6,
                    color: colors['ink-soft'],
                  ),
                ),
                const SizedBox(height: 28),
                OutlinedButton.icon(
                  key: const Key('discover'),
                  onPressed: c.scanning ? () => c.stopScan() : () => c.scan(),
                  icon: c.scanning
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(
                          CupertinoIcons.antenna_radiowaves_left_right,
                        ),
                  label: Text(c.scanning ? '停止发现' : '发现局域网节点'),
                  style: OutlinedButton.styleFrom(
                    minimumSize: const Size.fromHeight(48),
                  ),
                ),
                const SizedBox(height: 8),
                const Text('发现结果是候选节点，请确认地址与空间来源后再登录。'),
                if (widget.platform != null)
                  TextButton.icon(
                    key: const Key('system-capabilities'),
                    onPressed: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) =>
                            CapabilitiesPage(platform: widget.platform!),
                      ),
                    ),
                    icon: const Icon(Icons.settings_outlined),
                    label: const Text('设备权限与文件'),
                  ),
                for (final address in c.candidates)
                  ListTile(
                    key: ValueKey('node-$address'),
                    contentPadding: EdgeInsets.zero,
                    leading: Icon(
                      Icons.dns_outlined,
                      color: colors['accent-text'],
                    ),
                    title: Text(address, style: const TextStyle(fontSize: 14)),
                    subtitle: const Text('选择后登录时验证节点'),
                    trailing: const Icon(
                      CupertinoIcons.chevron_right,
                      size: 16,
                    ),
                    onTap: () {
                      _origin.text = address;
                      c.clearError();
                    },
                  ),
                const SizedBox(height: 24),
                Form(
                  key: _form,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      _fieldLabel('节点地址'),
                      TextFormField(
                        key: const Key('node-origin'),
                        controller: _origin,
                        keyboardType: TextInputType.url,
                        autocorrect: false,
                        decoration: const InputDecoration(
                          hintText: 'https://chat.example.com',
                        ),
                        validator: (value) =>
                            value?.trim().isEmpty != false ? '请输入节点地址' : null,
                      ),
                      const SizedBox(height: 20),
                      _fieldLabel('账号'),
                      TextFormField(
                        key: const Key('username'),
                        controller: _username,
                        autocorrect: false,
                        textInputAction: TextInputAction.next,
                        decoration: const InputDecoration(hintText: '输入账号'),
                        validator: (value) =>
                            value?.trim().isEmpty != false ? '请输入账号' : null,
                      ),
                      const SizedBox(height: 20),
                      _fieldLabel('密码'),
                      TextFormField(
                        key: const Key('password'),
                        controller: _password,
                        obscureText: true,
                        enableSuggestions: false,
                        autocorrect: false,
                        textInputAction: TextInputAction.done,
                        onFieldSubmitted: (_) => _login(),
                        decoration: const InputDecoration(hintText: '输入密码'),
                        validator: (value) =>
                            value?.isEmpty != false ? '请输入密码' : null,
                      ),
                      const SizedBox(height: 20),
                      if (c.error != null)
                        ErrorNotice(message: c.error!, onDismiss: c.clearError),
                      FilledButton(
                        key: const Key('login'),
                        onPressed: c.busy ? null : _login,
                        child: Text(c.busy ? '正在连接…' : '连接并登录'),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 24),
                Text(
                  'MeshX · 移动体验版',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: colors['ink-faint'], fontSize: 12),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _fieldLabel(String text) => Padding(
    padding: const EdgeInsets.only(bottom: 8),
    child: Text(
      text,
      style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
    ),
  );
}

class ErrorNotice extends StatelessWidget {
  const ErrorNotice({
    super.key,
    required this.message,
    required this.onDismiss,
  });
  final String message;
  final VoidCallback onDismiss;
  @override
  Widget build(BuildContext context) => Semantics(
    liveRegion: true,
    child: Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Row(
        children: [
          Icon(
            CupertinoIcons.exclamationmark_circle,
            size: 18,
            color: palette(context)['danger'],
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              message,
              style: TextStyle(color: palette(context)['danger'], fontSize: 13),
            ),
          ),
          IconButton(
            tooltip: '关闭提示',
            onPressed: onDismiss,
            icon: const Icon(CupertinoIcons.xmark, size: 16),
          ),
        ],
      ),
    ),
  );
}

class ChatPage extends StatefulWidget {
  const ChatPage({
    super.key,
    required this.controller,
    required this.onToggleTheme,
    this.platform,
    this.themeMode = ThemeMode.system,
    this.onThemeModeChanged,
  });
  final ChatController controller;
  final VoidCallback onToggleTheme;
  final PlatformCoordinator? platform;
  final ThemeMode themeMode;
  final Future<void> Function(ThemeMode)? onThemeModeChanged;

  @override
  State<ChatPage> createState() => _ChatPageState();
}

class _ChatPageState extends State<ChatPage> {
  int _section = 0;
  late NavigationCountsController _counts;
  final Set<int> _visited = {0};
  int _navigationGeneration = 0;

  @override
  void initState() {
    super.initState();
    _counts = NavigationCountsController(controller);
    _bindNavigation();
  }

  @override
  void didUpdateWidget(ChatPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != controller) {
      _counts.dispose();
      _counts = NavigationCountsController(controller);
    }
    if (oldWidget.platform != platform) {
      oldWidget.platform?.setBroadcastNavigationHandler(null);
      oldWidget.platform?.setConversationNavigationHandler(null);
      _bindNavigation();
    }
  }

  void _bindNavigation() {
    final source = platform;
    final generation = ++_navigationGeneration;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || generation != _navigationGeneration || source == null) {
        return;
      }
      source.chatViewVisible = _section == 0;
      source.setConversationNavigationHandler(
        () => _scheduleNavigation(source, 0),
      );
      source.setBroadcastNavigationHandler(
        (id) => _scheduleNavigation(source, 3, broadcastId: id),
      );
    });
  }

  void _scheduleNavigation(
    PlatformCoordinator source,
    int section, {
    int? broadcastId,
  }) {
    final owner = source.owner;
    final generation = _navigationGeneration;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted ||
          generation != _navigationGeneration ||
          source != platform ||
          owner == null ||
          source.owner != owner) {
        return;
      }
      _selectSection(section);
      if (broadcastId != null) {
        Navigator.of(context).push(
          MaterialPageRoute<void>(
            builder: (_) => BroadcastDetailLoaderPage(
              chat: controller,
              platform: source,
              broadcastId: broadcastId,
            ),
          ),
        );
      }
    });
    WidgetsBinding.instance.ensureVisualUpdate();
  }

  ChatController get controller => widget.controller;
  PlatformCoordinator? get platform => widget.platform;
  ThemeMode get themeMode => widget.themeMode;
  Future<void> Function(ThemeMode)? get onThemeModeChanged =>
      widget.onThemeModeChanged;

  void _selectSection(int value) {
    if (_section == value) return;
    _counts.refresh();
    platform?.chatViewVisible = value == 0;
    setState(() {
      _section = value;
      _visited.add(value);
    });
  }

  void _openProfile() {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => ProfilePage(
          chat: controller,
          platform: platform,
          themeMode: themeMode,
          onThemeModeChanged: onThemeModeChanged ?? (_) async {},
        ),
      ),
    );
  }

  Widget _sectionPage(int value) {
    if (!_visited.contains(value)) return const SizedBox.shrink();
    return switch (value) {
      1 => FriendsPage(
        chat: controller,
        onConversationOpened: () => _selectSection(0),
        onOpenProfile: _openProfile,
      ),
      2 => GroupsPage(
        chat: controller,
        onConversationOpened: () => _selectSection(0),
        onOpenProfile: _openProfile,
      ),
      3 => BroadcastsPage(
        chat: controller,
        platform: platform,
        onOpenProfile: _openProfile,
      ),
      _ => const SizedBox.shrink(),
    };
  }

  @override
  void dispose() {
    ++_navigationGeneration;
    _counts.dispose();
    platform?.setBroadcastNavigationHandler(null);
    platform?.setConversationNavigationHandler(null);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = controller;
    final wide = MediaQuery.sizeOf(context).width >= 760;
    final unread = c.online && c.session != null
        ? c.conversations.fold<int>(
            0,
            (total, item) => total + (item.unread > 0 ? item.unread : 0),
          )
        : 0;
    Widget countedIcon(IconData data, int? count) {
      final icon = Icon(data);
      if (count == null || count <= 0) return icon;
      return Badge.count(count: count, maxCount: 99, child: icon);
    }

    Widget messageIcon(bool selected) {
      final icon = Icon(
        selected ? Icons.chat_bubble : Icons.chat_bubble_outline,
      );
      if (unread == 0) return icon;
      return Badge(label: Text(unread > 99 ? '99+' : '$unread'), child: icon);
    }

    return PopScope(
      canPop: _section == 0 && (c.active == null || wide),
      onPopInvokedWithResult: (didPop, result) {
        if (didPop) return;
        if (_section != 0) {
          _selectSection(0);
        } else {
          c.leaveConversation();
        }
      },
      child: Scaffold(
        appBar: _section == 0
            ? AppBar(
                leading: c.active != null && !wide
                    ? IconButton(
                        key: const Key('back-conversations'),
                        tooltip: '返回消息列表',
                        onPressed: c.leaveConversation,
                        icon: const Icon(CupertinoIcons.chevron_left),
                      )
                    : null,
                title: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      !wide && c.active != null ? c.active!.title : '消息',
                      overflow: TextOverflow.ellipsis,
                    ),
                    Row(
                      children: [
                        Icon(
                          Icons.circle,
                          size: 6,
                          color: palette(
                            context,
                          )[c.online ? 'success' : 'warning'],
                        ),
                        const SizedBox(width: 6),
                        Expanded(
                          child: Text(
                            c.recoveryStatus.isNotEmpty
                                ? c.recoveryStatus
                                : c.online
                                ? '已连接 · ${c.node?.name ?? ''}'
                                : '连接恢复中',
                            key: const Key('connection-status'),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 11,
                              color: palette(context)['ink-soft'],
                            ),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
                actions: [
                  IconButton(
                    key: const Key('theme-toggle'),
                    tooltip: '切换明暗主题',
                    onPressed: widget.onToggleTheme,
                    icon: const Icon(CupertinoIcons.moon, size: 21),
                  ),
                  IconButton(
                    key: const Key('open-profile'),
                    tooltip: '打开个人资料与设置',
                    onPressed: _openProfile,
                    icon: const Icon(Icons.account_circle_outlined),
                  ),
                  IconButton(
                    key: const Key('reconnect'),
                    tooltip: '重新连接',
                    onPressed: c.online ? null : () => unawaited(c.reconnect()),
                    icon: const Icon(Icons.refresh),
                  ),
                ],
              )
            : null,
        body: IndexedStack(
          index: _section,
          children: [
            _section == 0
                ? SafeArea(
                    top: false,
                    child: Column(
                      children: [
                        if (c.error != null)
                          Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 16),
                            child: ErrorNotice(
                              message: c.error!,
                              onDismiss: c.clearError,
                            ),
                          ),
                        Expanded(
                          child: wide
                              ? Row(
                                  children: [
                                    SizedBox(
                                      width: 304,
                                      child: ConversationList(controller: c),
                                    ),
                                    const VerticalDivider(width: 1),
                                    Expanded(
                                      child: c.active == null
                                          ? const Center(
                                              child: Text('选择一个会话，开始交流'),
                                            )
                                          : MessagePane(
                                              key: ValueKey(c.active!.id),
                                              controller: c,
                                              platform: platform,
                                            ),
                                    ),
                                  ],
                                )
                              : c.active == null
                              ? ConversationList(controller: c)
                              : MessagePane(
                                  key: ValueKey(c.active!.id),
                                  controller: c,
                                  platform: platform,
                                ),
                        ),
                      ],
                    ),
                  )
                : const SizedBox.shrink(),
            for (var index = 1; index <= 3; index++)
              TickerMode(
                enabled: _section == index,
                child: _sectionPage(index),
              ),
          ],
        ),
        bottomNavigationBar: !wide && _section == 0 && c.active != null
            ? null
            : AnimatedBuilder(
                animation: _counts,
                builder: (context, _) => NavigationBar(
                  key: const Key('primary-bottom-navigation'),
                  selectedIndex: _section,
                  onDestinationSelected: _selectSection,
                  destinations: [
                    Semantics(
                      label: unread > 0 ? '$unread 条未读消息' : null,
                      child: NavigationDestination(
                        icon: messageIcon(false),
                        selectedIcon: messageIcon(true),
                        label: '消息',
                      ),
                    ),
                    Semantics(
                      label: (_counts.friendRequests ?? 0) > 0
                          ? '${_counts.friendRequests} 条待处理好友申请'
                          : null,
                      child: NavigationDestination(
                        icon: countedIcon(
                          Icons.people_outline,
                          _counts.friendRequests,
                        ),
                        selectedIcon: countedIcon(
                          Icons.people,
                          _counts.friendRequests,
                        ),
                        label: '联系人',
                      ),
                    ),
                    const NavigationDestination(
                      icon: Icon(Icons.groups_outlined),
                      selectedIcon: Icon(Icons.groups),
                      label: '群聊',
                    ),
                    Semantics(
                      label: (_counts.broadcastTasks ?? 0) > 0
                          ? '${_counts.broadcastTasks} 条待办广播'
                          : null,
                      child: NavigationDestination(
                        icon: countedIcon(
                          Icons.campaign_outlined,
                          _counts.broadcastTasks,
                        ),
                        selectedIcon: countedIcon(
                          Icons.campaign,
                          _counts.broadcastTasks,
                        ),
                        label: '广播',
                      ),
                    ),
                  ],
                ),
              ),
      ),
    );
  }
}

class ConversationList extends StatelessWidget {
  const ConversationList({super.key, required this.controller});
  final ChatController controller;
  @override
  Widget build(BuildContext context) {
    final c = controller, colors = palette(context);
    return Material(
      color: colors['panel']!,
      child: RefreshIndicator(
        onRefresh: c.refreshConversations,
        child: ListView.builder(
          key: const Key('conversation-list'),
          physics: const AlwaysScrollableScrollPhysics(),
          itemCount: c.conversations.length + 1,
          itemBuilder: (context, index) {
            if (index == 0) {
              return Padding(
                padding: const EdgeInsets.fromLTRB(20, 20, 20, 12),
                child: Text(
                  c.conversations.isEmpty
                      ? '暂无会话，可在“联系人”添加好友，或在“群聊”创建群聊'
                      : '最近会话 · ${c.conversations.length}',
                  style: TextStyle(fontSize: 12, color: colors['ink-soft']),
                ),
              );
            }
            final conversation = c.conversations[index - 1];
            return ListTile(
              key: ValueKey('conversation-${conversation.id}'),
              contentPadding: const EdgeInsets.symmetric(
                horizontal: 20,
                vertical: 8,
              ),
              selected: c.active?.id == conversation.id,
              selectedTileColor: colors['blue']!.withValues(alpha: .07),
              leading: Avatar(
                name: conversation.title,
                group: conversation.kind != 'private',
              ),
              title: Text(
                conversation.title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w600,
                ),
              ),
              subtitle: Padding(
                padding: const EdgeInsets.only(top: 5),
                child: Text(
                  conversation.preview.isEmpty ? '开始交流' : conversation.preview,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(fontSize: 13, color: colors['ink-soft']),
                ),
              ),
              trailing: conversation.unread > 0
                  ? Badge(
                      label: Text(
                        conversation.unread > 99
                            ? '99+'
                            : conversation.unread.toString(),
                      ),
                    )
                  : null,
              onTap: () => c.select(conversation),
            );
          },
        ),
      ),
    );
  }
}

class Avatar extends StatelessWidget {
  const Avatar({
    super.key,
    required this.name,
    this.group = false,
    this.small = false,
  });
  final String name;
  final bool group, small;
  @override
  Widget build(BuildContext context) => Container(
    width: small ? 32 : 48,
    height: small ? 32 : 48,
    alignment: Alignment.center,
    decoration: BoxDecoration(
      color: palette(context)['blue']!.withValues(alpha: .10),
      borderRadius: BorderRadius.circular(small ? 10 : 15),
    ),
    child: group
        ? Icon(
            CupertinoIcons.person_2,
            color: palette(context)['accent-text'],
            size: 22,
          )
        : Text(
            name.isEmpty ? 'M' : name.characters.first,
            style: TextStyle(
              fontSize: small ? 14 : 20,
              fontWeight: FontWeight.w600,
              color: palette(context)['accent-text'],
            ),
          ),
  );
}

class MessagePane extends StatefulWidget {
  const MessagePane({super.key, required this.controller, this.platform});
  final ChatController controller;
  final PlatformCoordinator? platform;
  @override
  State<MessagePane> createState() => _MessagePaneState();
}

class _MessagePaneState extends State<MessagePane> with RouteAware {
  ModalRoute<dynamic>? _observedRoute;
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final route = ModalRoute.of(context);
    if (route != _observedRoute) {
      meshXRouteObserver.unsubscribe(this);
      _observedRoute = route;
      if (route != null) meshXRouteObserver.subscribe(this, route);
    }
  }

  void _cancelReadDwell() => widget.controller.observeRecoveryRead(
    widget.controller.recoveryReadScope,
    {},
    _readClock.elapsedMilliseconds,
    foreground: false,
  );
  @override
  void didPushNext() => _cancelReadDwell();
  @override
  void didPopNext() => _cancelReadDwell();
  late final TextEditingController _input;
  AttachmentController? _attachments;
  final _scroll = ScrollController();
  final _viewport = GlobalKey();
  final _visibleKeys = <String, GlobalKey>{};
  final _readClock = Stopwatch();
  Timer? _readTimer;
  void _sampleReadVisibility() {
    if (!mounted) return;
    final c = widget.controller, scope = widget.controller.recoveryReadScope;
    if (scope.isEmpty) return;
    final foreground = ModalRoute.of(context)?.isCurrent == true;
    final visible = <int>{};
    final viewport = _viewport.currentContext?.findRenderObject();
    if (foreground && viewport is RenderBox && viewport.hasSize) {
      final bounds = viewport.localToGlobal(Offset.zero) & viewport.size;
      final sequences = {
        for (final m in c.messages) m.key: m.sequence,
        for (final t in c.recoveryTerminals) t.messageId: t.sequence,
      };
      for (final entry in _visibleKeys.entries) {
        final box = entry.value.currentContext?.findRenderObject();
        if (box is! RenderBox || !box.hasSize || !box.attached) continue;
        final rect = box.localToGlobal(Offset.zero) & box.size;
        if (rect.overlaps(bounds) && (sequences[entry.key] ?? 0) > 0) {
          visible.add(sequences[entry.key]!);
        }
      }
    }
    c.observeRecoveryRead(
      scope,
      visible,
      _readClock.elapsedMilliseconds,
      foreground: foreground,
    );
  }

  @override
  void initState() {
    super.initState();
    _readClock.start();
    _readTimer = Timer.periodic(
      const Duration(milliseconds: 100),
      (_) => _sampleReadVisibility(),
    );
    _input = TextEditingController(
      text: widget.controller.drafts[widget.controller.active!.id] ?? '',
    );
    if (widget.platform != null) {
      _attachments = AttachmentController(
        chat: widget.controller,
        platform: widget.platform!,
      );
    }
    _input.addListener(() {
      widget.controller.drafts[widget.controller.active!.id] = _input.text;
      setState(() {});
    });
  }

  @override
  void dispose() {
    meshXRouteObserver.unsubscribe(this);
    _readTimer?.cancel();
    widget.controller.observeRecoveryRead(
      widget.controller.recoveryReadScope,
      {},
      _readClock.elapsedMilliseconds,
      foreground: false,
    );
    _input.dispose();
    _attachments?.dispose();
    _scroll.dispose();
    super.dispose();
  }

  void _send() {
    if (widget.controller.send(_input.text)) {
      _input.clear();
      if (_scroll.hasClients) {
        _scroll.animateTo(
          0,
          duration: const Duration(milliseconds: 160),
          curve: Curves.easeOut,
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.controller,
        colors = palette(context),
        messages = c.messages;
    final terminals = c.recoveryTerminals;
    final rows =
        <({ChatMessage? message, RecoveryTerminal? terminal, int sequence})>[
          for (final message in messages)
            (
              message: message,
              terminal: null,
              sequence: message.sequence > 0
                  ? message.sequence
                  : 9007199254740991,
            ),
          for (final terminal in terminals)
            (message: null, terminal: terminal, sequence: terminal.sequence),
        ];
    if (terminals.isNotEmpty) {
      rows.sort((a, b) => a.sequence.compareTo(b.sequence));
    }
    return Column(
      children: [
        if (MediaQuery.sizeOf(context).width >= 760)
          Padding(
            padding: const EdgeInsets.all(16),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text(
                c.active!.title,
                style: Theme.of(context).textTheme.titleMedium,
              ),
            ),
          ),
        Expanded(
          key: _viewport,
          child: c.recoveryStatus.isNotEmpty
              ? Center(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Semantics(
                      liveRegion: true,
                      child: Text(
                        c.recoveryStatus,
                        textAlign: TextAlign.center,
                      ),
                    ),
                  ),
                )
              : messages.isEmpty && c.loadingHistory
              ? const Center(child: CircularProgressIndicator())
              : SelectionArea(
                  child: ListView.builder(
                    key: const Key('message-list'),
                    controller: _scroll,
                    reverse: true,
                    keyboardDismissBehavior:
                        ScrollViewKeyboardDismissBehavior.onDrag,
                    padding: const EdgeInsets.fromLTRB(16, 16, 16, 12),
                    itemCount: rows.length + 1,
                    itemBuilder: (context, index) {
                      if (index == rows.length) {
                        return Center(
                          child: c.hasOlder
                              ? TextButton(
                                  key: const Key('load-older'),
                                  onPressed: c.loadingHistory
                                      ? null
                                      : c.loadOlder,
                                  child: Text(
                                    c.loadingHistory ? '正在加载…' : '加载更早的消息',
                                  ),
                                )
                              : Padding(
                                  padding: const EdgeInsets.symmetric(
                                    vertical: 16,
                                  ),
                                  child: Text(
                                    rows.isEmpty ? '发送第一条消息，开始交流' : '会话从这里开始',
                                    style: TextStyle(
                                      fontSize: 12,
                                      color: colors['ink-faint'],
                                    ),
                                  ),
                                ),
                        );
                      }
                      final row = rows[rows.length - 1 - index];
                      if (row.terminal != null) {
                        return Padding(
                          key: _visibleKeys.putIfAbsent(
                            row.terminal!.messageId,
                            GlobalKey.new,
                          ),
                          padding: const EdgeInsets.symmetric(
                            vertical: 12,
                            horizontal: 16,
                          ),
                          child: Text(
                            row.terminal!.label,
                            key: ValueKey(
                              'terminal-${row.terminal!.messageId}',
                            ),
                            textAlign: TextAlign.center,
                            style: TextStyle(
                              color: colors['ink-soft'],
                              fontSize: 12,
                            ),
                          ),
                        );
                      }
                      final message = row.message!;
                      return KeyedSubtree(
                        key: _visibleKeys.putIfAbsent(
                          message.key,
                          GlobalKey.new,
                        ),
                        child: MessageBubble(
                          key: ValueKey('message-${message.key}'),
                          message: message,
                          own: message.fromUserId == c.session!.userId,
                          sender: message.nickname.isEmpty
                              ? c.senderName(message.fromUserId)
                              : message.nickname,
                          onRetry: () => c.send('', retry: message),
                          attachments: _attachments,
                          onBroadcast: (broadcastId) =>
                              Navigator.of(context).push(
                                MaterialPageRoute<void>(
                                  builder: (_) => BroadcastDetailLoaderPage(
                                    chat: c,
                                    platform: widget.platform,
                                    broadcastId: broadcastId,
                                  ),
                                ),
                              ),
                        ),
                      );
                    },
                  ),
                ),
        ),
        if (_attachments != null)
          AnimatedBuilder(
            animation: _attachments!,
            builder: (context, _) => Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (_attachments!.busy)
                  LinearProgressIndicator(value: _attachments!.progress),
                if (_attachments!.error != null)
                  MaterialBanner(
                    content: Text(_attachments!.error!),
                    actions: [
                      TextButton(
                        onPressed: () {
                          _attachments!.clearError();
                        },
                        child: const Text('关闭'),
                      ),
                    ],
                  ),
              ],
            ),
          ),
        Container(
          color: colors['panel'],
          padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              if (_attachments != null)
                AnimatedBuilder(
                  animation: _attachments!,
                  builder: (context, _) => IconButton(
                    key: const Key('attach-file'),
                    tooltip: _attachments!.busy ? '取消文件操作' : '发送文件或图片',
                    onPressed: _attachments!.busy
                        ? _attachments!.cancel
                        : c.canSendInActiveConversation
                        ? () => _attachments!.pickAndSend()
                        : null,
                    icon: Icon(
                      _attachments!.busy
                          ? CupertinoIcons.xmark_circle
                          : CupertinoIcons.paperclip,
                    ),
                  ),
                ),
              Expanded(
                child: TextField(
                  key: const Key('composer'),
                  controller: _input,
                  minLines: 1,
                  maxLines: 5,
                  maxLength: 4000,
                  textCapitalization: TextCapitalization.sentences,
                  textInputAction: TextInputAction.newline,
                  style: const TextStyle(fontSize: 16),
                  decoration: const InputDecoration(
                    hintText: '发送消息…',
                    counterText: '',
                    contentPadding: EdgeInsets.symmetric(
                      horizontal: 14,
                      vertical: 11,
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 8),
              IconButton.filled(
                key: const Key('send-message'),
                tooltip: '发送消息',
                onPressed:
                    c.session != null &&
                        c.canSendInActiveConversation &&
                        _input.text.trim().isNotEmpty
                    ? _send
                    : null,
                icon: const Icon(CupertinoIcons.arrow_up, size: 22),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class MessageBubble extends StatelessWidget {
  const MessageBubble({
    super.key,
    required this.message,
    required this.own,
    required this.sender,
    required this.onRetry,
    this.attachments,
    this.onBroadcast,
  });
  final ChatMessage message;
  final bool own;
  final String sender;
  final VoidCallback onRetry;
  final AttachmentController? attachments;
  final void Function(int broadcastId)? onBroadcast;
  @override
  Widget build(BuildContext context) {
    final colors = palette(context), date = message.createdAt;
    final time =
        '${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisAlignment: own
            ? MainAxisAlignment.end
            : MainAxisAlignment.start,
        children: [
          if (!own) ...[
            Avatar(name: sender, small: true),
            const SizedBox(width: 8),
          ],
          Flexible(
            child: Column(
              crossAxisAlignment: own
                  ? CrossAxisAlignment.end
                  : CrossAxisAlignment.start,
              children: [
                if (!own)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: Text(
                      sender,
                      style: TextStyle(fontSize: 11, color: colors['ink-soft']),
                    ),
                  ),
                Container(
                  constraints: BoxConstraints(
                    maxWidth: (MediaQuery.sizeOf(context).width * .73).clamp(
                      160,
                      560,
                    ),
                  ),
                  padding: const EdgeInsets.symmetric(
                    horizontal: 14,
                    vertical: 10,
                  ),
                  decoration: BoxDecoration(
                    color: own ? colors['action-bg'] : colors['panel'],
                    borderRadius:
                        BorderRadius.circular(
                          meshXSizes['radius-bubble']!,
                        ).copyWith(
                          topLeft: Radius.circular(own ? 18 : 5),
                          topRight: Radius.circular(own ? 5 : 18),
                        ),
                  ),
                  child: message.contentType == 'text' || message.recalled
                      ? Text(
                          message.displayContent,
                          style: TextStyle(
                            fontSize: 16,
                            height: 1.45,
                            color: own ? colors['on-accent'] : colors['ink'],
                          ),
                        )
                      : message.contentType == 'broadcast'
                      ? _BroadcastMessageCard(
                          content: message.content,
                          onOpen: onBroadcast,
                        )
                      : AttachmentMessage(
                          message: message,
                          own: own,
                          controller: attachments,
                        ),
                ),
                const SizedBox(height: 4),
                if (own && message.delivery == Delivery.failed)
                  TextButton(
                    onPressed: onRetry,
                    child: Text(
                      '未确认送达 · 点击重试',
                      style: TextStyle(fontSize: 12, color: colors['danger']),
                    ),
                  )
                else
                  Text(
                    time +
                        (own
                            ? message.delivery == Delivery.queued
                                  ? ' · 等待联网'
                                  : message.delivery == Delivery.sending
                                  ? ' · 发送中'
                                  : ' · 已发送'
                            : ''),
                    style: TextStyle(fontSize: 10, color: colors['ink-faint']),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _BroadcastMessageCard extends StatelessWidget {
  const _BroadcastMessageCard({required this.content, this.onOpen});
  final String content;
  final void Function(int broadcastId)? onOpen;
  @override
  Widget build(BuildContext context) {
    int? id;
    String title = '广播通知', summary = '点击查看最新状态', priority = '';
    try {
      final value = jsonDecode(content);
      if (value is Map) {
        id = value['broadcastId'] is int
            ? value['broadcastId'] as int
            : int.tryParse('${value['broadcastId']}');
        title = (value['title'] as String?)?.trim().isNotEmpty == true
            ? (value['title'] as String).trim()
            : title;
        summary = (value['summary'] as String?)?.trim().isNotEmpty == true
            ? (value['summary'] as String).trim()
            : summary;
        priority = (value['priority'] as String?)?.trim().toUpperCase() ?? '';
      }
    } catch (_) {
      id = null;
    }
    return InkWell(
      key: ValueKey('broadcast-card-${id ?? 'invalid'}'),
      onTap: id != null && id > 0 && onOpen != null ? () => onOpen!(id!) : null,
      child: ConstrainedBox(
        constraints: const BoxConstraints(minWidth: 190),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  priority == 'EMERGENCY'
                      ? Icons.warning_amber_rounded
                      : Icons.campaign_outlined,
                  size: 18,
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    title,
                    style: const TextStyle(fontWeight: FontWeight.w700),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Text(summary, maxLines: 3, overflow: TextOverflow.ellipsis),
            const SizedBox(height: 8),
            Text(id == null ? '广播卡片无效' : '查看服务器最新状态 →'),
          ],
        ),
      ),
    );
  }
}
