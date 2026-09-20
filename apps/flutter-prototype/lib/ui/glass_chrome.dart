import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' as ui;
import 'package:flutter/cupertino.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter/rendering.dart';
import '../platform/glass_bridge.dart';
import 'theme.dart';
import 'tokens.g.dart';

/// Set MESHX_NATIVE_GLASS=false for a device-specific composition regression.
/// Fallback changes rendering only; it never changes networking or permissions.
const _nativeGlassEnabled = bool.fromEnvironment(
  'MESHX_NATIVE_GLASS',
  defaultValue: true,
);

class MeshXGlassScope extends StatefulWidget {
  const MeshXGlassScope({super.key, required this.child});
  final Widget child;
  @override
  State<MeshXGlassScope> createState() => _MeshXGlassScopeState();
}

class _MeshXGlassScopeState extends State<MeshXGlassScope> {
  @override
  void initState() {
    super.initState();
    unawaited(MeshXGlassRuntime.instance.initialize());
  }

  @override
  Widget build(BuildContext context) => _GlassCapabilities(
    notifier: MeshXGlassRuntime.instance,
    child: widget.child,
  );
}

class _GlassCapabilities extends InheritedNotifier<MeshXGlassRuntime> {
  const _GlassCapabilities({required super.notifier, required super.child});
}

MeshXGlassRuntime _runtime(BuildContext context) =>
    context
        .dependOnInheritedWidgetOfExactType<_GlassCapabilities>()
        ?.notifier ??
    MeshXGlassRuntime.instance;

bool _native(BuildContext context) =>
    !kIsWeb &&
    _nativeGlassEnabled &&
    defaultTargetPlatform == TargetPlatform.iOS &&
    _runtime(context).available;

Map<String, Object?> _appearance(BuildContext context) => {
  'version': 1,
  'dark': Theme.of(context).brightness == Brightness.dark,
  'tint': palette(context)['color.action.text']!.toARGB32(),
};

/// Owns one channel per native view and never installs a global gesture capture.
class _NativeChrome extends StatefulWidget {
  const _NativeChrome({
    required this.parameters,
    required this.fallback,
    this.onAction,
    this.onHeight,
  });
  final Map<String, Object?> parameters;
  final Widget fallback;
  final ValueChanged<String>? onAction;
  final ValueChanged<double>? onHeight;
  @override
  State<_NativeChrome> createState() => _NativeChromeState();
}

class _NativeChromeState extends State<_NativeChrome> {
  MethodChannel? _channel;
  bool _failed = false;
  int _revision = 0;

  Future<void> _created(int id) async {
    if (!mounted) return;
    final channel = MethodChannel('com.meshx.mobile/glass/view/$id');
    _channel = channel;
    channel.setMethodCallHandler((call) async {
      if (!mounted || _channel != channel) return;
      if (call.method == 'action' && call.arguments is Map) {
        final action = (call.arguments as Map)['id'];
        if (action is String) widget.onAction?.call(action);
      }
      if (call.method == 'height' && call.arguments is num) {
        final height = (call.arguments as num).toDouble();
        if (height.isFinite && height > 0 && height < 300) {
          widget.onHeight?.call(height);
        }
      }
    });
    await _update();
  }

  Future<void> _update() async {
    final channel = _channel;
    if (channel == null || _failed) return;
    final revision = ++_revision;
    try {
      await channel.invokeMethod<void>('update', {
        ...widget.parameters,
        'revision': revision,
      });
    } on PlatformException {
      if (mounted && revision == _revision) setState(() => _failed = true);
    } on MissingPluginException {
      if (mounted && revision == _revision) setState(() => _failed = true);
    }
  }

  @override
  void didUpdateWidget(_NativeChrome oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!mapEquals(oldWidget.parameters, widget.parameters)) {
      unawaited(_update());
    }
  }

  @override
  void dispose() {
    ++_revision;
    _channel?.setMethodCallHandler(null);
    _channel = null;
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => _failed
      ? widget.fallback
      : UiKitView(
          viewType: 'com.meshx.mobile/glass-view',
          creationParams: widget.parameters,
          creationParamsCodec: const StandardMessageCodec(),
          onPlatformViewCreated: _created,
        );
}

class MeshXGlassSurface extends StatelessWidget {
  const MeshXGlassSurface({
    super.key,
    required this.child,
    this.padding = EdgeInsets.zero,
    this.radius,
    this.nativeMaterial = true,
    this.readable = false,
  });
  final Widget child;
  final EdgeInsetsGeometry padding;
  final double? radius;
  final bool nativeMaterial, readable;

  @override
  Widget build(BuildContext context) {
    final r = radius ?? meshXSizes['shape.radius.sheet']!;
    final runtime = _runtime(context);
    final solid =
        runtime.reduceTransparency ||
        runtime.increaseContrast ||
        MediaQuery.highContrastOf(context);
    final colors = palette(context);
    final border = BorderRadius.circular(r);
    Widget background = DecoratedBox(
      decoration: BoxDecoration(
        color:
            colors[solid
                ? 'color.background.surface'
                : readable
                ? 'color.glass.readable'
                : 'color.glass.regular'],
        borderRadius: border,
        border: Border.all(
          color:
              colors[solid
                  ? 'color.glass.accessible-border'
                  : 'color.glass.rim']!,
        ),
      ),
    );
    if (!solid) {
      background = ClipRRect(
        borderRadius: border,
        child: BackdropFilter(
          filter: ui.ImageFilter.blur(
            sigmaX: meshXSizes['component.glass.fallback-blur']!,
            sigmaY: meshXSizes['component.glass.fallback-blur']!,
          ),
          child: background,
        ),
      );
    }
    if (nativeMaterial && _native(context)) {
      background = _NativeChrome(
        parameters: {
          ..._appearance(context),
          'kind': 'surface',
          'radius': r,
          'opaque': solid,
        },
        fallback: background,
      );
    }
    // Do not put a ClipRRect/Opacity around a native glass view. UIKit owns its
    // corners; Flutter owns the opaque, selectable foreground and hit targets.
    return Stack(
      children: [
        Positioned.fill(
          child: IgnorePointer(child: ExcludeSemantics(child: background)),
        ),
        Padding(padding: padding, child: child),
      ],
    );
  }
}

class MeshXGlassButton extends StatelessWidget {
  const MeshXGlassButton({
    super.key,
    required this.tooltip,
    required this.icon,
    required this.nativeSymbol,
    required this.onPressed,
    this.prominent = false,
  });
  final String tooltip, nativeSymbol;
  final Widget icon;
  final VoidCallback? onPressed;
  final bool prominent;
  @override
  Widget build(BuildContext context) {
    final size = meshXSizes['component.glass.control-size']!;
    final fallback = prominent
        ? IconButton.filled(tooltip: tooltip, onPressed: onPressed, icon: icon)
        : MeshXGlassSurface(
            nativeMaterial: false,
            radius: size / 2,
            child: IconButton(
              tooltip: tooltip,
              onPressed: onPressed,
              icon: icon,
            ),
          );
    return SizedBox(
      width: size,
      height: size,
      child: _native(context)
          ? _NativeChrome(
              parameters: {
                ..._appearance(context),
                'kind': 'button',
                'symbol': nativeSymbol,
                'label': tooltip,
                'enabled': onPressed != null,
                'prominent': prominent,
                if (prominent)
                  'tint': palette(context)['color.action.primary']!.toARGB32(),
              },
              onAction: (_) => onPressed?.call(),
              fallback: fallback,
            )
          : fallback,
    );
  }
}

/// Native UITabBar geometry is measured by UIKit; 64 is only the first-frame
/// reservation and the non-native fallback's minimum, not an iOS hard height.
class MeshXNavigationBar extends StatefulWidget {
  const MeshXNavigationBar({
    super.key,
    required this.selectedIndex,
    required this.onDestinationSelected,
    required this.destinations,
    required this.counts,
  });
  final int selectedIndex;
  final ValueChanged<int> onDestinationSelected;
  final List<Widget> destinations;
  final List<int?> counts;
  @override
  State<MeshXNavigationBar> createState() => _MeshXNavigationBarState();
}

class _MeshXNavigationBarState extends State<MeshXNavigationBar> {
  double? _nativeHeight;
  @override
  Widget build(BuildContext context) {
    final minimum = meshXSizes['component.glass.navigation-min-height']!;
    final scaledLabel = MediaQuery.textScalerOf(context).scale(12);
    final fallbackHeight = math.max(minimum, 40 + scaledLabel * 1.5);
    final colors = palette(context);
    final fallback = MeshXGlassSurface(
      nativeMaterial: false,
      child: NavigationBar(
        height: fallbackHeight,
        selectedIndex: widget.selectedIndex,
        onDestinationSelected: widget.onDestinationSelected,
        backgroundColor: Colors.transparent,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        indicatorColor: colors['color.interaction.selected'],
        destinations: widget.destinations,
      ),
    );
    final bar = _native(context)
        ? SizedBox(
            height: _nativeHeight ?? fallbackHeight,
            child: _NativeChrome(
              parameters: {
                ..._appearance(context),
                'kind': 'navigation',
                'selected': widget.selectedIndex,
                'counts': widget.counts.map((v) => v ?? -1).toList(),
              },
              onHeight: (height) {
                if (!mounted ||
                    (_nativeHeight != null &&
                        (height - _nativeHeight!).abs() < .5)) {
                  return;
                }
                WidgetsBinding.instance.addPostFrameCallback((_) {
                  if (mounted) setState(() => _nativeHeight = height);
                });
              },
              onAction: (id) {
                final index = int.tryParse(id);
                if (index != null && index >= 0 && index < 4) {
                  widget.onDestinationSelected(index);
                }
              },
              fallback: fallback,
            ),
          )
        : fallback;
    return SafeArea(
      top: false,
      minimum: EdgeInsets.fromLTRB(
        meshXSizes['component.glass.navigation-inset']!,
        0,
        meshXSizes['component.glass.navigation-inset']!,
        meshXSizes['component.glass.navigation-inset']!,
      ),
      child: Center(
        heightFactor: 1,
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxWidth: meshXSizes['component.glass.navigation-max-width']!,
          ),
          child: bar,
        ),
      ),
    );
  }
}

class MeshXComposerSurface extends StatelessWidget {
  const MeshXComposerSurface({super.key, required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) {
    final inset = meshXSizes['component.glass.navigation-inset']!;
    final keyboardOpen = MediaQuery.viewInsetsOf(context).bottom > 0;
    // Scaffold already resizes for the IME. Never add its height a second time.
    final bottom = keyboardOpen
        ? meshXSizes['spacing.2']!
        : math.max(inset, MediaQuery.viewPaddingOf(context).bottom);
    return Padding(
      padding: EdgeInsets.fromLTRB(inset, 6, inset, bottom),
      child: MeshXGlassSurface(
        readable: true,
        padding: EdgeInsets.all(meshXSizes['spacing.2']!),
        child: child,
      ),
    );
  }
}

Future<String?> showMeshXAttachmentMenu(
  BuildContext context, {
  bool direct = false,
}) async {
  final runtime = _runtime(context);
  final photos =
      defaultTargetPlatform == TargetPlatform.android ||
      (defaultTargetPlatform == TargetPlatform.iOS && runtime.photoPicker);
  if (_native(context) && !direct) {
    try {
      return await runtime.attachmentMenu(
        photos: photos,
        dark: Theme.of(context).brightness == Brightness.dark,
      );
    } on MissingPluginException {
      // A deliberately disabled/older native build keeps a usable local menu.
    } on PlatformException catch (error) {
      // Never stack another menu on an existing native presentation.
      if (error.code == 'PRESENTATION_BUSY' ||
          error.code == 'PRESENTATION_UNAVAILABLE') {
        return null;
      }
    } on TimeoutException {
      return null;
    }
  }
  if (!context.mounted) return null;
  if (defaultTargetPlatform != TargetPlatform.iOS) {
    return runtime.guardPresentation(
      () => showModalBottomSheet<String>(
        context: context,
        useSafeArea: true,
        builder: (sheetContext) => SafeArea(
          top: false,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Padding(padding: EdgeInsets.all(16), child: Text('发送附件')),
              if (direct)
                ListTile(
                  leading: const Icon(Icons.wifi),
                  title: const Text('设备直传'),
                  subtitle: const Text('局域网直连，失败后自动通过节点续传'),
                  onTap: () => Navigator.pop(sheetContext, 'direct'),
                ),
              if (photos)
                ListTile(
                  leading: const Icon(Icons.photo_outlined),
                  title: const Text('照片'),
                  onTap: () => Navigator.pop(sheetContext, 'photos'),
                ),
              ListTile(
                leading: const Icon(Icons.insert_drive_file_outlined),
                title: const Text('文件'),
                onTap: () => Navigator.pop(sheetContext, 'file'),
              ),
              ListTile(
                title: const Text('取消'),
                onTap: () => Navigator.pop(sheetContext),
              ),
            ],
          ),
        ),
      ),
    );
  }
  return runtime.guardPresentation(
    () => showCupertinoModalPopup<String>(
      context: context,
      builder: (sheetContext) => CupertinoActionSheet(
        title: const Text('发送附件'),
        actions: [
          if (direct)
            CupertinoActionSheetAction(
              onPressed: () => Navigator.pop(sheetContext, 'direct'),
              child: const Text('设备直传（失败后节点续传）'),
            ),
          if (photos)
            CupertinoActionSheetAction(
              onPressed: () => Navigator.pop(sheetContext, 'photos'),
              child: const Text('照片'),
            ),
          CupertinoActionSheetAction(
            onPressed: () => Navigator.pop(sheetContext, 'file'),
            child: const Text('文件'),
          ),
        ],
        cancelButton: CupertinoActionSheetAction(
          onPressed: () => Navigator.pop(sheetContext),
          child: const Text('取消'),
        ),
      ),
    ),
  );
}

/// The list genuinely extends behind the glass. Only its scroll-content tail
/// reserves space; the surface never owns message state or receipt cursors.
class MeshXMessageLayout extends StatefulWidget {
  const MeshXMessageLayout({
    super.key,
    required this.threadBuilder,
    required this.composer,
    required this.composerKey,
  });
  final Widget Function(BuildContext, double) threadBuilder;
  final Widget composer;
  final GlobalKey composerKey;
  @override
  State<MeshXMessageLayout> createState() => _MeshXMessageLayoutState();
}

class _MeshXMessageLayoutState extends State<MeshXMessageLayout> {
  double? _height;
  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, limits) {
      final bottom = MediaQuery.viewInsetsOf(context).bottom > 0
          ? 8.0
          : math.max(12.0, MediaQuery.viewPaddingOf(context).bottom);
      final initial = meshXSizes['component.glass.control-size']! + 22 + bottom;
      final maximum = math.max(
        1.0,
        limits.maxHeight - math.min(80.0, limits.maxHeight * .35),
      );
      final inset = math.min(_height ?? initial, maximum);
      return Stack(
        children: [
          Positioned.fill(child: widget.threadBuilder(context, inset)),
          Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            child: _ChromeMeasure(
              key: widget.composerKey,
              onSize: (size) {
                if (mounted &&
                    (_height == null || (_height! - size.height).abs() > .5)) {
                  setState(() => _height = size.height);
                }
              },
              child: ConstrainedBox(
                constraints: BoxConstraints(maxHeight: maximum),
                child: SingleChildScrollView(
                  reverse: true,
                  child: widget.composer,
                ),
              ),
            ),
          ),
        ],
      );
    },
  );
}

class _ChromeMeasure extends SingleChildRenderObjectWidget {
  const _ChromeMeasure({super.key, required this.onSize, required super.child});
  final ValueChanged<Size> onSize;
  @override
  RenderObject createRenderObject(BuildContext context) =>
      _ChromeMeasureBox(onSize);
  @override
  void updateRenderObject(
    BuildContext context,
    _ChromeMeasureBox renderObject,
  ) {
    renderObject.onSize = onSize;
  }
}

class _ChromeMeasureBox extends RenderProxyBox {
  _ChromeMeasureBox(this.onSize);
  ValueChanged<Size> onSize;
  Size? _reported;
  @override
  void performLayout() {
    super.performLayout();
    final measured = size;
    if (_reported == measured) return;
    _reported = measured;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (attached && _reported == measured) onSize(measured);
    });
  }
}
