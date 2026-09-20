import 'package:flutter/material.dart';

import '../theme.dart';
import '../tokens.g.dart';

/// Presents a server/controller-derived count without owning or mutating it.
class MeshXBadge extends StatelessWidget {
  const MeshXBadge({
    super.key,
    required this.count,
    this.child,
    this.maxCount = 99,
  });

  final int? count;
  final Widget? child;
  final int maxCount;

  @override
  Widget build(BuildContext context) {
    final value = count ?? 0;
    final visible = value > 0;
    final label = value > maxCount ? '$maxCount+' : '$value';
    final colors = palette(context);
    final minSize = meshXSizes['component.badge.min-size']!;
    final padding = meshXSizes['component.badge.padding-inline']!;
    return Badge(
      isLabelVisible: visible,
      smallSize: minSize,
      largeSize: minSize,
      padding: EdgeInsets.symmetric(horizontal: padding),
      backgroundColor: colors['color.action.danger'],
      textColor: colors['color.text.on-accent'],
      label: Text(label),
      child: child,
    );
  }
}
