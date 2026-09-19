import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/core/store.dart';
import 'package:meshx_flutter_probe/ui/app.dart';
import 'package:meshx_flutter_probe/ui/profile_page.dart';
import 'package:meshx_flutter_probe/ui/theme.dart';
import 'profile_feature_test.dart' show ProfileApi;
import 'platform_fakes.dart';

class MemoryPreferences implements PreferenceStore {
  final values = <String, String>{};
  @override
  Future<String?> read(String key) async => values[key];
  @override
  Future<void> write(String key, String value) async => values[key] = value;
}

class DelayedPreferences implements PreferenceStore {
  final readGate = Completer<String?>();
  final values = <String, String>{};
  @override
  Future<String?> read(String key) => readGate.future;
  @override
  Future<void> write(String key, String value) async => values[key] = value;
}

void main() {
  testWidgets(
    'profile/settings remains reachable on small dark large-text layout',
    (tester) async {
      tester.view.physicalSize = const Size(320, 568);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final chat = ChatController(discovery: FakeDiscovery())
        ..api = ProfileApi();
      await tester.pumpWidget(
        MediaQuery(
          data: const MediaQueryData(textScaler: TextScaler.linear(1.5)),
          child: MaterialApp(
            theme: meshXTheme(Brightness.dark),
            home: ProfilePage(
              chat: chat,
              themeMode: ThemeMode.dark,
              onThemeModeChanged: (_) async {},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('profile-username')), findsOneWidget);
      await tester.scrollUntilVisible(
        find.byKey(const Key('save-profile')),
        180,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.byKey(const Key('save-profile')), findsOneWidget);
      await tester.scrollUntilVisible(
        find.byKey(const Key('theme-mode')),
        180,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.byKey(const Key('theme-mode')), findsOneWidget);
      await tester.scrollUntilVisible(
        find.byKey(const Key('change-password')),
        220,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.scrollUntilVisible(
        find.byKey(const Key('profile-logout')),
        120,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.byKey(const Key('profile-logout')), findsOneWidget);
      expect(tester.takeException(), isNull);
      chat.dispose();
    },
  );

  testWidgets('MeshXApp loads and persists the ordinary theme preference', (
    tester,
  ) async {
    final preferences = MemoryPreferences()..values['themeMode'] = 'dark';
    final chat = ChatController(discovery: FakeDiscovery());
    await tester.pumpWidget(
      MeshXApp(controller: chat, preferences: preferences),
    );
    await tester.pumpAndSettle();
    expect(
      Theme.of(tester.element(find.byKey(const Key('node-origin')))).brightness,
      Brightness.dark,
    );
    await tester.tap(find.byKey(const Key('theme-toggle')));
    await tester.pumpAndSettle();
    expect(preferences.values['themeMode'], 'light');
  });

  testWidgets('late theme preference read cannot overwrite a user choice', (
    tester,
  ) async {
    final preferences = DelayedPreferences();
    final chat = ChatController(discovery: FakeDiscovery());
    await tester.pumpWidget(
      MeshXApp(
        controller: chat,
        preferences: preferences,
        initialThemeMode: ThemeMode.light,
      ),
    );
    await tester.tap(find.byKey(const Key('theme-toggle')));
    await tester.pumpAndSettle();
    expect(preferences.values['themeMode'], 'dark');
    preferences.readGate.complete('light');
    await tester.pumpAndSettle();
    expect(
      Theme.of(tester.element(find.byKey(const Key('node-origin')))).brightness,
      Brightness.dark,
    );
  });
}
