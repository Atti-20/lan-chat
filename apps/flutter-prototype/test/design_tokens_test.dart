import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/ui/theme.dart';
import 'package:meshx_flutter_probe/ui/tokens.g.dart';

void main() {
  testWidgets('filled action restores token foreground after busy state', (
    tester,
  ) async {
    for (final brightness in Brightness.values) {
      final colors = brightness == Brightness.dark ? meshXDark : meshXLight;
      for (final enabled in [true, false, true]) {
        await tester.pumpWidget(
          MaterialApp(
            theme: meshXTheme(brightness),
            home: Scaffold(
              body: FilledButton.icon(
                onPressed: enabled ? () {} : null,
                icon: const Icon(Icons.add_a_photo_outlined),
                label: const Text('上传图片并完成'),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        final label = tester.renderObject<RenderParagraph>(
          find.text('上传图片并完成'),
        );
        if (enabled) {
          expect(label.text.style!.color, colors['on-accent']);
          final context = tester.element(find.byType(FilledButton));
          final button = tester.widget<FilledButton>(find.byType(FilledButton));
          final background = button
              .defaultStyleOf(context)
              .backgroundColor!
              .resolve({});
          expect(background, colors['action-bg']);
          final foregroundLuminance = label.text.style!.color!
              .computeLuminance();
          final backgroundLuminance = background!.computeLuminance();
          expect(
            (foregroundLuminance + 0.05) / (backgroundLuminance + 0.05),
            greaterThanOrEqualTo(4.5),
          );
        } else {
          expect(
            tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
            isNull,
          );
        }
        expect(tester.takeException(), isNull);
      }
    }
  });
  final source =
      jsonDecode(
            File('../../packages/design-tokens/tokens.json').readAsStringSync(),
          )
          as Map<String, dynamic>;
  final definitions = source['tokens'] as Map<String, dynamic>;
  final primitives = source['primitives'] as Map<String, dynamic>;
  final aliases = source['aliases'] as Map<String, dynamic>;
  final targets = <String, String>{
    for (final name in definitions.keys) name: name,
    for (final entry in aliases.entries) entry.key: entry.value as String,
  };

  Object? resolve(String name, String mode) {
    final reference = definitions[name][mode]['ref'] as String;
    if (reference.startsWith('primitives.')) {
      return primitives[reference.substring('primitives.'.length)]['value'];
    }
    return resolve(reference.substring('tokens.'.length), mode);
  }

  Color color(String value) {
    if (value.startsWith('#')) {
      return Color(int.parse('ff${value.substring(1)}', radix: 16));
    }
    final values = value
        .substring(5, value.length - 1)
        .split(',')
        .map((part) => double.parse(part.trim()))
        .toList();
    return Color.fromARGB(
      (values[3] * 255).round(),
      values[0].toInt(),
      values[1].toInt(),
      values[2].toInt(),
    );
  }

  test(
    'every light/dark semantic and legacy color is compiled and matches the source',
    () {
      for (final entry in {'light': meshXLight, 'dark': meshXDark}.entries) {
        final expectedKeys = targets.keys
            .where((key) => definitions[targets[key]]['type'] == 'color')
            .toSet();
        expect(entry.value.keys.toSet(), expectedKeys);
        for (final key in expectedKeys) {
          expect(
            entry.value[key],
            color(resolve(targets[key]!, entry.key) as String),
            reason: '${entry.key}: $key',
          );
        }
      }
      expect(meshXLight['color.message.own'], meshXLight['action-bg']);
      expect(meshXDark['color.message.peer'], meshXDark['fill']);
      expect(meshXLight.containsKey('material.shadow.glass'), isFalse);
    },
  );

  test(
    'all non-color native values have complete types, units and matching aliases',
    () {
      final maps = <String, Map<String, Object>>{
        'dimension': meshXSizes,
        'number': meshXNumbers,
        'duration': meshXDurations,
        'cubicBezier': meshXCurves,
        'fontFamily': meshXFontFamilies,
      };
      for (final kind in maps.keys) {
        final expectedKeys = targets.keys
            .where((key) => definitions[targets[key]]['type'] == kind)
            .toSet();
        expect(maps[kind]!.keys.toSet(), expectedKeys);
        for (final key in expectedKeys) {
          final value = resolve(targets[key]!, 'light');
          expect(value, resolve(targets[key]!, 'dark'));
          Object? expected = value;
          if (kind == 'dimension' || kind == 'duration') {
            final measure = value as Map<String, dynamic>;
            final number =
                (measure['value'] as num) *
                (measure['unit'] == 'rem' ? source['baseFontSize'] as num : 1);
            expected = kind == 'duration'
                ? Duration(milliseconds: number.toInt())
                : number.toDouble();
          }
          expect(maps[kind]![key], expected, reason: key);
        }
      }
      expect(meshXSizes['typography.body.size'], 14);
      expect(meshXNumbers['component.message.line-height'], 1.55);
      expect(
        meshXFontFamilies['typography.body.family'],
        contains('PingFang SC'),
      );
    },
  );

  testWidgets(
    'existing Flutter theme keeps system text scaling with the generated body size',
    (tester) async {
      for (final brightness in Brightness.values) {
        await tester.pumpWidget(
          MaterialApp(
            theme: meshXTheme(brightness),
            home: MediaQuery(
              data: const MediaQueryData(textScaler: TextScaler.linear(2)),
              child: Builder(
                builder: (context) => Text(
                  '中文 English',
                  style: Theme.of(context).textTheme.bodyMedium!.copyWith(
                    fontSize: meshXSizes['typography.body.size'],
                  ),
                ),
              ),
            ),
          ),
        );
        final paragraph = tester.renderObject<RenderParagraph>(
          find.text('中文 English'),
        );
        expect(
          paragraph.textScaler.scale(meshXSizes['typography.body.size']!),
          28,
        );
        expect(paragraph.size.height, greaterThan(28));
        expect(tester.takeException(), isNull);
      }
    },
  );
}
