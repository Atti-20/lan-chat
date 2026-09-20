import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/core/models.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';
import 'package:meshx_flutter_probe/ui/components/meshx_avatar.dart';
import 'package:meshx_flutter_probe/ui/components/meshx_badge.dart';
import 'package:meshx_flutter_probe/ui/profile_page.dart';
import 'package:meshx_flutter_probe/ui/theme.dart';
import 'package:meshx_flutter_probe/ui/tokens.g.dart';

class _AvatarApi extends MeshXApi {
  _AvatarApi() : super(Uri.parse('https://avatar.invalid')) {
    session = const Session(1, '测试用户', 'synthetic-token');
  }

  var avatarLoads = 0;

  @override
  Future<Uint8List> avatarBytes(String raw, {bool retry = true}) async {
    avatarLoads++;
    return Uint8List.fromList(const [
      137,
      80,
      78,
      71,
      13,
      10,
      26,
      10,
      0,
      0,
      0,
      13,
      73,
      72,
      68,
      82,
      0,
      0,
      0,
      1,
      0,
      0,
      0,
      1,
      8,
      6,
      0,
      0,
      0,
      31,
      21,
      196,
      137,
      0,
      0,
      0,
      13,
      73,
      68,
      65,
      84,
      8,
      215,
      99,
      248,
      207,
      192,
      240,
      31,
      0,
      5,
      0,
      1,
      255,
      137,
      153,
      61,
      29,
      0,
      0,
      0,
      0,
      73,
      69,
      78,
      68,
      174,
      66,
      96,
      130,
    ]);
  }
}

void main() {
  testWidgets('MeshXAvatar is circular and exposes its identity', (
    tester,
  ) async {
    final semantics = tester.ensureSemantics();
    await tester.pumpWidget(
      MaterialApp(
        theme: meshXTheme(Brightness.light),
        home: const Scaffold(
          body: Center(
            child: MeshXAvatar(label: '林澈', icon: Icons.groups_outlined),
          ),
        ),
      ),
    );

    expect(find.byType(ClipOval), findsOneWidget);
    expect(find.bySemanticsLabel('林澈的头像'), findsOneWidget);
    semantics.dispose();
  });

  testWidgets('MeshXBadge formats a supplied count without owning it', (
    tester,
  ) async {
    Future<void> render(int? count) => tester.pumpWidget(
      MaterialApp(
        theme: meshXTheme(Brightness.dark),
        home: Scaffold(
          body: MeshXBadge(
            count: count,
            child: const Icon(Icons.chat_bubble_outline),
          ),
        ),
      ),
    );

    await render(100);
    expect(find.text('99+'), findsOneWidget);
    final large = tester.widget<Badge>(find.byType(Badge));
    expect(large.isLabelVisible, isTrue);
    expect(large.smallSize, meshXSizes['component.badge.min-size']);
    expect(large.largeSize, meshXSizes['component.badge.min-size']);

    await render(0);
    expect(tester.widget<Badge>(find.byType(Badge)).isLabelVisible, isFalse);
  });

  testWidgets(
    'ProfileAvatar keeps asynchronous image loading outside MeshXAvatar',
    (tester) async {
      final api = _AvatarApi();
      addTearDown(api.close);
      await tester.pumpWidget(
        MaterialApp(
          theme: meshXTheme(Brightness.light),
          home: Scaffold(
            body: Center(
              child: ProfileAvatar(
                api: api,
                nickname: '林澈',
                avatar: '/api/v1/file/content/avatar.png',
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(api.avatarLoads, 1);
      expect(find.byType(MeshXAvatar), findsOneWidget);
      expect(find.byType(Image), findsOneWidget);
    },
  );
}
