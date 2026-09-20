import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'tokens.g.dart';

Map<String, Color> palette(BuildContext context) =>
    Theme.of(context).brightness == Brightness.dark ? meshXDark : meshXLight;

ThemeData meshXTheme(Brightness brightness) {
  final colors = brightness == Brightness.dark ? meshXDark : meshXLight;
  final border = OutlineInputBorder(
    borderRadius: BorderRadius.circular(meshXSizes['radius-control']!),
    borderSide: BorderSide.none,
  );
  return ThemeData(
    brightness: brightness,
    colorScheme:
        ColorScheme.fromSeed(
          seedColor: colors['blue']!,
          brightness: brightness,
        ).copyWith(
          primary: colors['action-bg'],
          onPrimary: colors['on-accent'],
          surface: colors['panel'],
          onSurface: colors['ink'],
          error: colors['danger'],
        ),
    scaffoldBackgroundColor: colors['canvas'],
    dividerColor: colors['ink-faint']!.withValues(alpha: .15),
    appBarTheme: AppBarTheme(
      systemOverlayStyle: brightness == Brightness.dark
          ? SystemUiOverlayStyle.light
          : SystemUiOverlayStyle.dark,
      backgroundColor: Colors.transparent,
      surfaceTintColor: Colors.transparent,
      foregroundColor: colors['ink'],
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
    ),
    textTheme: TextTheme(
      headlineMedium: TextStyle(
        fontSize: 28,
        fontWeight: FontWeight.w700,
        color: colors['ink'],
      ),
      titleLarge: TextStyle(
        fontSize: 20,
        fontWeight: FontWeight.w700,
        color: colors['ink'],
      ),
      titleMedium: TextStyle(
        fontSize: 16,
        fontWeight: FontWeight.w600,
        color: colors['ink'],
      ),
      bodyLarge: TextStyle(fontSize: 16, height: 1.45, color: colors['ink']),
      bodyMedium: TextStyle(fontSize: 14, height: 1.45, color: colors['ink']),
      bodySmall: TextStyle(fontSize: 12, color: colors['ink-soft']),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: colors['fill'],
      border: border,
      enabledBorder: border,
      focusedBorder: border.copyWith(
        borderSide: BorderSide(color: colors['accent-text']!, width: 1.5),
      ),
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: Size(44, meshXSizes['control-height']!),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(meshXSizes['radius-control']!),
        ),
      ),
    ),
    iconButtonTheme: IconButtonThemeData(
      style: IconButton.styleFrom(
        minimumSize: Size.square(meshXSizes['component.glass.control-size']!),
      ),
    ),
  );
}
