import 'package:flutter/material.dart';

import '../theme.dart';
import '../tokens.g.dart';

/// A display-only circular identity surface.
///
/// Network and file loading stay outside this widget.  Callers that resolve an
/// image asynchronously can pass it through [child], while local fallbacks use
/// the supplied [label] or [icon].
class MeshXAvatar extends StatelessWidget {
  const MeshXAvatar({
    super.key,
    required this.label,
    this.size,
    this.icon,
    this.backgroundColor,
    this.foregroundColor,
    this.child,
  });

  final String label;
  final double? size;
  final IconData? icon;
  final Color? backgroundColor;
  final Color? foregroundColor;
  final Widget? child;

  @override
  Widget build(BuildContext context) {
    final colors = palette(context);
    final avatarSize = size ?? meshXSizes['size.control.default']!;
    final name = label.trim().isEmpty ? '用户' : label.trim();
    final foreground = foregroundColor ?? colors['accent-text']!;
    final fallback = icon == null
        ? FittedBox(
            fit: BoxFit.scaleDown,
            child: Text(
              String.fromCharCode(name.runes.first).toUpperCase(),
              style: TextStyle(
                fontSize: avatarSize * .42,
                fontWeight: FontWeight.w700,
                color: foreground,
              ),
            ),
          )
        : Icon(icon, color: foreground, size: avatarSize * .46);
    return Semantics(
      image: true,
      label: '$name的头像',
      child: ExcludeSemantics(
        child: ClipOval(
          child: SizedBox(
            width: avatarSize,
            height: avatarSize,
            child: ColoredBox(
              color: backgroundColor ?? colors['surface-tint']!,
              child: child ?? Center(child: fallback),
            ),
          ),
        ),
      ),
    );
  }
}
