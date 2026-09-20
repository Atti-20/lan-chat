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
import '../data/meshx_api.dart';
import '../core/store.dart';
import 'theme.dart';
import 'glass_chrome.dart';
import '../platform/glass_bridge.dart';
import 'tokens.g.dart';
import 'profile_page.dart';
import 'components/meshx_avatar.dart';
import 'components/meshx_badge.dart';
import 'chat_tools.dart';
import 'burn_reader.dart';
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
      child: MeshXGlassScope(child: child ?? const SizedBox.shrink()),
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
                TextButton.icon(
                  icon: const Icon(Icons.help_outline),
                  label: const Text('局域网连接与证书帮助'),
                  onPressed: () => showDialog<void>(
                    context: context,
                    builder: (ctx) => AlertDialog(
                      title: const Text('连接局域网节点'),
                      content: const SingleChildScrollView(
                        child: Text(
                          '1. 手机与节点接入同一局域网，允许系统的本地网络权限。\n\n2. 使用管理员提供的 HTTPS 域名和端口；域名可以解析到内网地址，业务服务不必开放到公网。\n\n3. 若采用单位的私有证书，请由管理员按设备策略配置可信证书。不要忽略证书错误或改用未知节点。\n\n4. 无法连接时区分：网络/端口不可达、域名解析失败、证书验证失败、账号登录失败。断开外网后能否继续使用取决于内网解析、节点服务和证书是否仍有效。',
                        ),
                      ),
                      actions: [
                        TextButton(
                          onPressed: () => Navigator.pop(ctx),
                          child: const Text('知道了'),
                        ),
                      ],
                    ),
                  ),
                ),
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

class _ChatPageState extends State<ChatPage> with RouteAware {
  int _section = 0;
  late NavigationCountsController _counts;
  final Set<int> _visited = {0};
  int _navigationGeneration = 0;
  ModalRoute<dynamic>? _rootRoute;
  CupertinoPageRoute<void>? _conversationRoute;
  String? _routeConversationId;
  bool _preserveSelection = false;
  bool _routeSyncScheduled = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final route = ModalRoute.of(context);
    if (route != _rootRoute) {
      meshXRouteObserver.unsubscribe(this);
      _rootRoute = route;
      if (route != null) meshXRouteObserver.subscribe(this, route);
    }
    _queueConversationRoute();
  }

  @override
  void didPopNext() => _queueConversationRoute();

  void _queueConversationRoute() {
    if (_routeSyncScheduled || !mounted) return;
    _routeSyncScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _routeSyncScheduled = false;
      if (!mounted) return;
      final routed =
          Theme.of(context).platform == TargetPlatform.iOS &&
          MediaQuery.sizeOf(context).width < 760;
      final active = controller.active;
      final old = _conversationRoute;
      if (old != null &&
          (!routed || _section != 0 || active?.id != _routeConversationId)) {
        _preserveSelection = true;
        Navigator.of(context).removeRoute(old);
        return;
      }
      if (!routed ||
          _section != 0 ||
          active == null ||
          old != null ||
          _rootRoute?.isCurrent != true) {
        return;
      }
      final id = active.id;
      _routeConversationId = id;
      final route = CupertinoPageRoute<void>(
        builder: (routeContext) => AnimatedBuilder(
          animation: controller,
          builder: (context, _) {
            if (controller.active?.id != id) {
              return const Scaffold(body: SizedBox.shrink());
            }
            return Scaffold(
              appBar: AppBar(
                title: Text(controller.active!.title),
                actions: [
                  IconButton(
                    tooltip: '搜索消息',
                    onPressed: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) => MessageSearchPage(chat: controller),
                      ),
                    ),
                    icon: const Icon(Icons.search),
                  ),
                ],
              ),
              body: MessagePane(
                key: ValueKey(id),
                controller: controller,
                platform: platform,
              ),
            );
          },
        ),
      );
      _conversationRoute = route;
      Navigator.of(context).push(route).then((_) {
        if (!mounted) return;
        _conversationRoute = null;
        if (!_preserveSelection && controller.active?.id == id) {
          controller.leaveConversation();
        }
        _preserveSelection = false;
        _queueConversationRoute();
      });
    });
  }

  @override
  void initState() {
    super.initState();
    _counts = NavigationCountsController(controller);
    controller.addListener(_queueConversationRoute);
    MeshXGlassRuntime.instance.addListener(_syncGlassVisibility);
    _bindNavigation();
  }

  @override
  void didUpdateWidget(ChatPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != controller) {
      oldWidget.controller.removeListener(_queueConversationRoute);
      controller.addListener(_queueConversationRoute);
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

  void _syncGlassVisibility() {
    platform?.chatViewVisible =
        _section == 0 && !MeshXGlassRuntime.instance.obscuresConversation;
  }

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
    controller.removeListener(_queueConversationRoute);
    meshXRouteObserver.unsubscribe(this);
    MeshXGlassRuntime.instance.removeListener(_syncGlassVisibility);
    _counts.dispose();
    platform?.setBroadcastNavigationHandler(null);
    platform?.setConversationNavigationHandler(null);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = controller;
    final wide = MediaQuery.sizeOf(context).width >= 760;
    final routed = !wide && Theme.of(context).platform == TargetPlatform.iOS;
    _queueConversationRoute();
    // Offline changes transport state, not the server-derived conversation
    // summaries already held by this signed-in account. Keep the badge visible
    // until an authoritative CHAT_READ event or refreshed summary changes it.
    final unread = c.session != null
        ? c.conversations.fold<int>(
            0,
            (total, item) => total + (item.unread > 0 ? item.unread : 0),
          )
        : 0;
    Widget countedIcon(IconData data, int? count) {
      final icon = Icon(data);
      if (count == null || count <= 0) return icon;
      return MeshXBadge(count: count, child: icon);
    }

    Widget messageIcon(bool selected) {
      final icon = Icon(
        selected ? Icons.chat_bubble : Icons.chat_bubble_outline,
      );
      if (unread == 0) return icon;
      return MeshXBadge(count: unread, child: icon);
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
        extendBody: true,
        appBar: _section == 0
            ? AppBar(
                leading: c.active != null && !wide
                    ? MeshXGlassButton(
                        nativeSymbol: 'chevron.left',
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
                  PopupMenuButton<String>(
                    tooltip: '消息工具',
                    icon: const Icon(Icons.more_horiz),
                    onSelected: (value) {
                      Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          builder: (_) => value == 'search'
                              ? MessageSearchPage(chat: c)
                              : TemporaryRoomsPage(chat: c),
                        ),
                      );
                    },
                    itemBuilder: (_) => const [
                      PopupMenuItem(value: 'search', child: Text('搜索消息')),
                      PopupMenuItem(value: 'rooms', child: Text('临时房间')),
                    ],
                  ),
                  MeshXGlassButton(
                    nativeSymbol: 'person.crop.circle',
                    key: const Key('open-profile'),
                    tooltip: '打开个人资料与设置',
                    onPressed: _openProfile,
                    icon: const Icon(Icons.account_circle_outlined),
                  ),
                  if (!c.online)
                    MeshXGlassButton(
                      nativeSymbol: 'arrow.clockwise',
                      key: const Key('reconnect'),
                      tooltip: '重新连接',
                      onPressed: c.online
                          ? null
                          : () => unawaited(c.reconnect()),
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
                    bottom: false,
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
                              : c.active == null || routed
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
        bottomNavigationBar:
            !wide && !routed && _section == 0 && c.active != null
            ? null
            : AnimatedBuilder(
                animation: _counts,
                builder: (context, _) => MeshXNavigationBar(
                  counts: [
                    unread,
                    _counts.friendRequests,
                    0,
                    _counts.broadcastTasks,
                  ],
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
          padding: EdgeInsets.only(
            bottom: MediaQuery.paddingOf(context).bottom + 16,
          ),
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
              leading: _ConversationAvatar(
                name: conversation.title,
                avatar: conversation.avatar,
                api: c.api,
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
                  !c.online ? '等待节点校验' : c.conversationPreview(conversation),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(fontSize: 13, color: colors['ink-soft']),
                ),
              ),
              trailing: conversation.unread > 0
                  ? MeshXBadge(count: conversation.unread)
                  : null,
              onTap: () => c.select(conversation),
            );
          },
        ),
      ),
    );
  }
}

class _ConversationAvatar extends StatelessWidget {
  const _ConversationAvatar({
    required this.name,
    this.group = false,
    this.small = false,
    this.avatar = '',
    this.api,
  });
  final MeshXApi? api;
  final String avatar;
  final String name;
  final bool group, small;
  @override
  Widget build(BuildContext context) =>
      (!group || avatar.isNotEmpty) && api != null
      ? ProfileAvatar(
          api: api!,
          nickname: name,
          avatar: avatar,
          size: small ? 32 : 48,
        )
      : MeshXAvatar(
          label: name,
          size: small ? 32 : 48,
          icon: group ? CupertinoIcons.person_2 : null,
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
  late final String _conversationId;
  AttachmentController? _attachments;
  ChatMessage? _reply;
  bool _burn = false;
  final _mentions = <int, String>{};
  String? _lastFocus;
  final _scroll = ScrollController();
  final _viewport = GlobalKey();
  final _composerViewport = GlobalKey();
  final _visibleKeys = <String, GlobalKey>{};
  final _visibleSequences = <String, int>{};
  final _readClock = Stopwatch();
  Timer? _readTimer;
  void _sampleReadVisibility() {
    if (!mounted) return;
    final c = widget.controller, scope = widget.controller.recoveryReadScope;
    if (scope.isEmpty) return;
    final foreground =
        ModalRoute.of(context)?.isCurrent == true &&
        c.active?.id == _conversationId &&
        !MeshXGlassRuntime.instance.obscuresConversation &&
        widget.platform?.busy != true;
    final visible = <int>{};
    final viewport = _viewport.currentContext?.findRenderObject();
    if (foreground && viewport is RenderBox && viewport.hasSize) {
      var bounds = viewport.localToGlobal(Offset.zero) & viewport.size;
      final composerBox = _composerViewport.currentContext?.findRenderObject();
      if (composerBox is RenderBox &&
          composerBox.hasSize &&
          composerBox.attached) {
        final top = composerBox.localToGlobal(Offset.zero).dy;
        // Native/Flutter controls obscure messages even though the list paints
        // behind them. Never submit visibility for that covered strip.
        bounds = Rect.fromLTRB(
          bounds.left,
          bounds.top,
          bounds.right,
          top.clamp(bounds.top, bounds.bottom).toDouble(),
        );
      }
      _visibleKeys.removeWhere((key, value) {
        if (value.currentContext != null) return false;
        _visibleSequences.remove(key);
        return true;
      });
      final sequences = _visibleSequences;
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
    _conversationId = widget.controller.active!.id;
    _input = TextEditingController(
      text: widget.controller.drafts[_conversationId] ?? '',
    );
    if (widget.platform != null) {
      _attachments = AttachmentController(
        chat: widget.controller,
        platform: widget.platform!,
      );
      unawaited(_attachments!.refreshPending());
    }
    _input.addListener(() {
      final id = widget.controller.active?.id;
      if (id == _conversationId) {
        widget.controller.updateDraft(_conversationId, _input.text);
        widget.controller.sendTyping(_input.text.isNotEmpty);
      }
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

  Future<void> _chooseAttachment() async {
    final controller = widget.controller;
    final api = controller.api;
    final userId = controller.session?.userId;
    final conversationId = controller.active?.id;
    if (conversationId != _conversationId) return;
    final choice = await showMeshXAttachmentMenu(
      context,
      direct:
          widget.controller.active?.kind == 'private' &&
          widget.platform?.direct != null,
    );
    if (!mounted ||
        choice == null ||
        _attachments == null ||
        !identical(controller.api, api) ||
        controller.session?.userId != userId ||
        controller.active?.id != conversationId ||
        !controller.canSendInActiveConversation) {
      return;
    }
    await _attachments!.pickAndSend(
      photos: choice == 'photos',
      direct: choice == 'direct',
    );
  }

  Future<void> _messageActions(ChatMessage message) async {
    final c = widget.controller;
    if (!c.allowsMessage(message) || message.delivery != Delivery.sent) return;
    _cancelReadDwell();
    final action = await showModalBottomSheet<String>(
      context: context,
      useSafeArea: true,
      builder: (ctx) => Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          ListTile(
            title: const Text('引用'),
            leading: const Icon(Icons.reply),
            onTap: () => Navigator.pop(ctx, 'reply'),
          ),
          if (message.fromUserId == c.session?.userId)
            ListTile(
              title: const Text('撤回'),
              leading: const Icon(Icons.undo),
              onTap: () => Navigator.pop(ctx, 'recall'),
            ),
        ],
      ),
    );
    if (!mounted ||
        !c.allowsMessage(message) ||
        c.active?.id != message.conversationId) {
      return;
    }
    if (action == 'reply') setState(() => _reply = message);
    if (action == 'recall') {
      final yes = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('撤回这条消息？'),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('撤回'),
            ),
          ],
        ),
      );
      if (yes == true && mounted) c.requestRecall(message);
    }
  }

  Future<void> _mention() async {
    final c = widget.controller, api = widget.controller.api;
    final conversation = c.active;
    if (api == null ||
        conversation?.id != _conversationId ||
        conversation?.kind != 'group') {
      return;
    }
    _cancelReadDwell();
    try {
      final members = await api.groupMembers(conversation!.targetId);
      if (!mounted || c.api != api || c.active?.id != conversation.id) return;
      final selected = await showModalBottomSheet<int>(
        context: context,
        useSafeArea: true,
        builder: (ctx) => ListView(
          shrinkWrap: true,
          children: [
            for (final member in members.where(
              (m) => m.userId != c.session?.userId,
            ))
              ListTile(
                title: Text(member.displayName),
                onTap: () => Navigator.pop(ctx, member.userId),
              ),
          ],
        ),
      );
      if (selected == null ||
          !mounted ||
          c.api != api ||
          c.active?.id != conversation.id) {
        return;
      }
      setState(
        () => _mentions[selected] = members
            .firstWhere((m) => m.userId == selected)
            .displayName,
      );
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(c.describe(e))));
      }
    }
  }

  void _send() {
    final c = widget.controller;
    if (c.active?.id != _conversationId) return;
    final reply = _reply != null && c.allowsMessage(_reply!)
        ? _reply!.messageId
        : null;
    if (c.send(
      _input.text,
      replyToId: reply,
      mentions: _mentions.keys.toSet(),
      burn: _burn,
    )) {
      setState(() {
        _reply = null;
        _burn = false;
        _mentions.clear();
      });
      _input.clear();
      _scrollToLatest();
    }
  }

  void _scrollToLatest() {
    if (!_scroll.hasClients) return;
    if (MediaQuery.of(context).disableAnimations) {
      _scroll.jumpTo(0);
      return;
    }
    unawaited(
      _scroll.animateTo(
        0,
        duration: meshXDurations['motion.duration.fast']!,
        curve: Curves.easeOut,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.controller,
        colors = palette(context),
        messages = c.messages
            .where(
              (m) =>
                  c.focusedSequence == null || m.sequence <= c.focusedSequence!,
            )
            .toList();
    final terminals = c.recoveryTerminals;
    if (_lastFocus != c.focusedMessageId) {
      _lastFocus = c.focusedMessageId;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && _scroll.hasClients) _scroll.jumpTo(0);
      });
    }
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
          for (final terminal in terminals.where(
            (t) =>
                c.focusedSequence == null || t.sequence <= c.focusedSequence!,
          ))
            (message: null, terminal: terminal, sequence: terminal.sequence),
        ];
    if (terminals.isNotEmpty) {
      rows.sort((a, b) => a.sequence.compareTo(b.sequence));
    }
    return MeshXMessageLayout(
      composerKey: _composerViewport,
      threadBuilder: (context, composerHeight) => Column(
        children: [
          if (c.focusedMessageId != null)
            TextButton(
              onPressed: c.clearMessageFocus,
              child: const Text('已定位搜索消息 · 返回最新消息'),
            ),
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
                      padding: EdgeInsets.fromLTRB(
                        16,
                        16,
                        16,
                        composerHeight + 12,
                      ),
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
                          _visibleSequences[row.terminal!.messageId] =
                              row.sequence;
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
                        _visibleSequences[message.key] = message.sequence;
                        return KeyedSubtree(
                          key: _visibleKeys.putIfAbsent(
                            message.key,
                            GlobalKey.new,
                          ),
                          child: MessageBubble(
                            key: ValueKey('message-${message.key}'),
                            message: message,
                            onLongPress: () => _messageActions(message),
                            api: c.api,
                            avatar: c.senderAvatar(message.fromUserId),
                            own: message.fromUserId == c.session!.userId,
                            sender: message.nickname.isEmpty
                                ? c.senderName(message.fromUserId)
                                : message.nickname,
                            onReadBurn: () async {
                              _cancelReadDwell();
                              await showDialog<void>(
                                context: context,
                                barrierDismissible: false,
                                builder: (_) =>
                                    BurnReader(chat: c, message: message),
                              );
                            },
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
        ],
      ),
      composer: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (_attachments != null)
            AnimatedBuilder(
              animation: _attachments!,
              builder: (context, _) => Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  for (final task in _attachments!.pending)
                    ListTile(
                      title: Text(
                        '待传：${task['fileName']}',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      trailing: Wrap(
                        children: [
                          IconButton(
                            tooltip: '继续上传',
                            onPressed: _attachments!.busy
                                ? null
                                : () => _attachments!.resumePending(task),
                            icon: const Icon(Icons.play_arrow),
                          ),
                          IconButton(
                            tooltip: '删除待传文件',
                            onPressed: _attachments!.busy
                                ? null
                                : () => _attachments!.discardPending(task),
                            icon: const Icon(Icons.delete_outline),
                          ),
                        ],
                      ),
                    ),
                  if (_attachments!.busy)
                    LinearProgressIndicator(value: _attachments!.progress),
                  if (_attachments!.preparing)
                    const Padding(
                      padding: EdgeInsets.symmetric(
                        vertical: 8,
                        horizontal: 12,
                      ),
                      child: Text('正在准备文件；大尺寸图片会自动缩小，可点击取消'),
                    ),
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
          if (c.typingLabel.isNotEmpty)
            Padding(
              padding: const EdgeInsets.all(6),
              child: Text(c.typingLabel),
            ),
          Row(
            children: [
              FilterChip(
                label: const Text('阅后即焚'),
                selected: _burn,
                onSelected: (value) => setState(() => _burn = value),
              ),
              if (_burn)
                const Flexible(
                  child: Padding(
                    padding: EdgeInsets.only(left: 8),
                    child: Text('接收者点击阅读后销毁', style: TextStyle(fontSize: 12)),
                  ),
                ),
            ],
          ),
          if (_reply != null && c.allowsMessage(_reply!))
            ListTile(
              dense: true,
              title: Text('引用 ${c.senderName(_reply!.fromUserId)} 的消息'),
              trailing: IconButton(
                tooltip: '取消引用',
                onPressed: () => setState(() => _reply = null),
                icon: const Icon(Icons.close),
              ),
            ),
          if (_mentions.isNotEmpty)
            Wrap(
              children: [
                for (final entry in _mentions.entries)
                  InputChip(
                    label: Text('@${entry.value}'),
                    onDeleted: () =>
                        setState(() => _mentions.remove(entry.key)),
                  ),
              ],
            ),
          MeshXComposerSurface(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                if (_attachments != null)
                  AnimatedBuilder(
                    animation: _attachments!,
                    builder: (context, _) => MeshXGlassButton(
                      nativeSymbol: _attachments!.busy
                          ? 'xmark.circle'
                          : 'plus',
                      key: const Key('attach-file'),
                      tooltip: _attachments!.busy ? '取消文件操作' : '发送文件或图片',
                      onPressed: _attachments!.busy
                          ? _attachments!.cancel
                          : c.canSendInActiveConversation
                          ? _chooseAttachment
                          : null,
                      icon: Icon(
                        _attachments!.busy
                            ? CupertinoIcons.xmark_circle
                            : CupertinoIcons.plus,
                      ),
                    ),
                  ),
                if (c.active?.kind == 'group')
                  IconButton(
                    tooltip: '提及群成员',
                    onPressed: c.online ? _mention : null,
                    icon: const Icon(Icons.alternate_email),
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
                      filled: false,
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
                MeshXGlassButton(
                  nativeSymbol: 'arrow.up',
                  prominent: true,
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
      ),
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
    this.api,
    this.avatar = '',
    this.onLongPress,
    this.onReadBurn,
  });
  final MeshXApi? api;
  final String avatar;
  final VoidCallback? onLongPress, onReadBurn;
  final ChatMessage message;
  final bool own;
  final String sender;
  final VoidCallback onRetry;
  final AttachmentController? attachments;
  final void Function(int broadcastId)? onBroadcast;
  @override
  Widget build(BuildContext context) {
    final colors = palette(context), date = message.createdAt;
    final radius = meshXSizes['shape.radius.message']!;
    final tailRadius = meshXSizes['component.message.tail-radius']!;
    final messageLineHeight = meshXNumbers['component.message.line-height']!;
    final messageFontSize = meshXSizes['typography.body-large.size']!;
    final time =
        '${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
    return GestureDetector(
      onLongPress: onLongPress,
      child: Padding(
        padding: const EdgeInsets.only(bottom: 16),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisAlignment: own
              ? MainAxisAlignment.end
              : MainAxisAlignment.start,
          children: [
            if (!own) ...[
              _ConversationAvatar(
                name: sender,
                small: true,
                api: api,
                avatar: avatar,
              ),
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
                        style: TextStyle(
                          fontSize: 11,
                          color: colors['ink-soft'],
                        ),
                      ),
                    ),
                  if (message.replyToId != null)
                    const Text('↪ 引用消息', style: TextStyle(fontSize: 12)),
                  if (message.mentionUserIds?.isNotEmpty == true)
                    const Text('@ 提及成员', style: TextStyle(fontSize: 12)),
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
                      color: own
                          ? colors['color.message.own']
                          : colors['color.message.peer'],
                      borderRadius:
                          BorderRadius.circular(radius).copyWith(
                            topLeft: Radius.circular(
                              own ? radius : tailRadius,
                            ),
                            topRight: Radius.circular(
                              own ? tailRadius : radius,
                            ),
                          ),
                    ),
                    child:
                        message.isBurn && !message.recalled && !message.burned
                        ? own
                              ? const Text('阅后即焚消息 · 等待阅读')
                              : TextButton(
                                  onPressed: onReadBurn,
                                  child: const Text('点击阅读 · 阅后即焚'),
                                )
                        : message.contentType == 'text' ||
                              message.recalled ||
                              message.burned
                        ? Text(
                            message.displayContent,
                            style: TextStyle(
                              fontSize: messageFontSize,
                              height: messageLineHeight,
                              color: own
                                  ? colors['color.message.on-own']
                                  : colors['color.message.on-peer'],
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
                      style: TextStyle(
                        fontSize: 10,
                        color: colors['ink-faint'],
                      ),
                    ),
                ],
              ),
            ),
          ],
        ),
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
