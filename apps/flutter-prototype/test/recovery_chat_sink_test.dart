import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/application/recovery_chat_sink.dart';
import 'package:meshx_flutter_probe/core/models.dart';
import 'package:meshx_flutter_probe/core/recovery.dart';
import 'package:meshx_flutter_probe/core/recovery_data.dart';
import 'package:meshx_flutter_probe/core/recovery_snapshot.dart';
import 'package:meshx_flutter_probe/platform/storage.dart';

void main() {
  test(
    'full rebuild removes absent bodies and separates held outbox atomically',
    () async {
      final root = await Directory.systemTemp.createTemp('meshx-sink-');
      addTearDown(() => root.delete(recursive: true));
      final store = FileChatStore(directory: root);
      final f =
          jsonDecode(
                File(
                  '../../contracts/test-vectors/recovery-snapshot-pages.json',
                ).readAsStringSync(),
              )
              as Map;
      (f['message']['details'] as Map).addAll(<String, dynamic>{
        'isBurn': 1,
        'burnDuration': 60,
        'replyToId': 'reply-a',
        'mentionUserIds': '8,9',
      });
      final c = f['context'];
      final context = RecoveryContext(
        origin: c['origin'],
        userId: c['userId'],
        streamEpoch: c['streamEpoch'],
        generation: c['generation'],
      );
      ChatMessage pending(String cid, String body) => ChatMessage(
        messageId: '',
        clientMsgId: 'pending-$cid',
        conversationId: cid,
        fromUserId: 7,
        content: body,
        sequence: 0,
        createdAt: DateTime(2026),
        delivery: Delivery.queued,
      );
      RecoveredChatImage? published;
      final sink = RecoveryChatSink(
        store: store,
        seed: () => RecoveryChatData(
          {
            'group:99': [pending('group:99', 'revoked-secret')],
            'group:21': [pending('group:21', 'draft')],
          },
          [],
          {},
        ),
        isCurrent: (_) => true,
        onPublish: (_, image) => published = image,
        onQuarantine: (_) {},
      );
      await store.save('https://node.example|7', {'content': 'legacy-secret'});
      await sink.begin(context);
      final result = stageRecoverySnapshotPage(
        beginFullRecoverySnapshot(context, 's', '0'),
        context,
        null,
        {
          'snapshotId': 's',
          'boundary': '0',
          'items': [f['directory'], f['message']],
          'nextPageToken': null,
          'snapshotComplete': true,
        },
      );
      await sink.snapshot(context, result.items);
      final ready = result.stage.state.copyWith(
        phase: RecoveryPhase.onlineSafe,
      );
      expect(
        () => sink.publish(context, ready),
        throwsA(isA<RecoveryProtocolError>()),
      );
      await sink.commit(context, result.stage.state);
      sink.publish(context, ready);
      expect(published!.chat.messages['group:21'], hasLength(1));
      expect(published!.outbox['group:21'], hasLength(1));
      final disk = (await FileChatStore(
        directory: root,
      ).loadRecovery('https://node.example|7'))!.snapshot;
      expect(jsonEncode(disk), isNot(contains('revoked-secret')));
      expect(jsonEncode(disk), isNot(contains('legacy-secret')));
      expect((disk['messages'] as Map)['group:21'], hasLength(1));
      final normal = ChatMessage.restore(
        Map<String, dynamic>.from(disk['messages']['group:21'][0]),
      );
      expect(normal.isBurn, isTrue);
      expect(normal.burnDuration, 60);
      expect(normal.replyToId, 'reply-a');
      expect(
        normal
            .withRecoveryDisposition(RecoveryDisposition.needsUserAction)
            .mentionUserIds,
        '8,9',
      );
      expect(
        normal
            .withoutRecoveryBody(isRecalled: true)
            .toJson()
            .containsKey('replyToId'),
        isFalse,
      );
      final outbox = disk['outbox'] as Map;
      expect(outbox['group:99'][0]['recoveryDisposition'], 'dropBodyRevoked');
      expect(outbox['group:21'][0]['content'], 'draft');
      expect(outbox['group:21'][0]['delivery'], 'failed');
      expect(disk['recovery']['cursor'], '0');
      final restarted = RecoveryChatSink(
        store: FileChatStore(directory: root),
        seed: () => RecoveryChatData({}, [], {}),
        isCurrent: (_) => true,
        onPublish: (_, _) {},
        onQuarantine: (_) {},
      );
      await restarted.begin(context);
      await restarted.snapshot(context, result.items);
      await restarted.commit(context, result.stage.state);
      final savedAgain = (await store.loadRecovery(
        'https://node.example|7',
      ))!.snapshot;
      expect(savedAgain['outbox']['group:21'][0]['content'], 'draft');
      expect(
        savedAgain['outbox']['group:99'][0]['recoveryDisposition'],
        'dropBodyRevoked',
      );
      sink.quarantine('DISCONNECTED');
      await expectLater(
        sink.commit(context, result.stage.state),
        throwsA(isA<RecoveryProtocolError>()),
      );
      expect((await store.loadRecovery('https://node.example|7'))!.revision, 2);
    },
  );
}
