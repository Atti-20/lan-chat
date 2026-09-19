import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';
import 'package:meshx_flutter_probe/data/models.dart';

ChatMessage message({
  String id = '',
  String clientId = 'same_client',
  int sender = 1,
  int sequence = 0,
  Delivery delivery = Delivery.sent,
}) => ChatMessage(
  messageId: id,
  clientMsgId: clientId,
  conversationId: 'private:1:2',
  fromUserId: sender,
  content: '你好',
  sequence: sequence,
  createdAt: DateTime(2026, 9, 8),
  delivery: delivery,
);

void main() {
  group('node transport policy', () {
    test('HTTPS nodes and local debug HTTP are accepted', () {
      expect(
        parseNodeOrigin(
          'https://chat.example.com/',
          allowLocalHttp: false,
        ).host,
        'chat.example.com',
      );
      expect(
        parseNodeOrigin('http://10.0.2.2:18381', allowLocalHttp: true).port,
        18381,
      );
      expect(
        parseNodeOrigin('http://[::1]:18381', allowLocalHttp: true).host,
        '::1',
      );
    });
    test('release HTTP and public HTTP cannot carry credentials', () {
      expect(
        () => parseNodeOrigin('http://192.168.1.2', allowLocalHttp: false),
        throwsFormatException,
      );
      expect(
        () => parseNodeOrigin('http://example.com', allowLocalHttp: true),
        throwsFormatException,
      );
    });
    test('reject userinfo, paths, query strings and non-HTTP schemes', () {
      for (final url in [
        'https://user:pass@example.com',
        'https://example.com/api',
        'https://example.com?token=secret',
        'file:///etc/passwd',
        'https://example.com#x',
      ]) {
        expect(
          () => parseNodeOrigin(url, allowLocalHttp: true),
          throwsFormatException,
          reason: url,
        );
      }
    });
    test('node metadata cannot switch the authority or traverse a path', () {
      for (final path in [
        '//elsewhere.example/auth',
        'https://elsewhere.example/auth',
        '/api/../admin',
        '/api/%2e%2e/admin',
        '/api?token=x',
      ]) {
        expect(
          () => NodeInfo.fromJson(Uri.parse('https://chat.example.com'), {
            'protocolVersion': 1,
            'apiBasePath': path,
          }),
          throwsFormatException,
          reason: path,
        );
      }
    });
    test('incompatible nodes fail before login', () {
      expect(
        () => NodeInfo.fromJson(Uri.parse('https://chat.example.com'), {
          'protocolVersion': 2,
        }),
        throwsFormatException,
      );
    });
  });
  group('message identity and ordering', () {
    test('ACK replaces pending message without duplication', () {
      final pending = message(delivery: Delivery.sending);
      final ack = pending.withDelivery(
        Delivery.sent,
        id: 'server-10',
        serverSequence: 10,
      );
      final result = mergeMessages([pending], [ack, ack]);
      expect(result, hasLength(1));
      expect(result.single.sequence, 10);
      expect(result.single.delivery, Delivery.sent);
    });
    test(
      'same client ID from a different sender remains a different message',
      () {
        expect(
          mergeMessages([message(sender: 1)], [message(sender: 2)]),
          hasLength(2),
        );
      },
    );
    test(
      'out-of-order delivery is sorted while pending messages remain at the end',
      () {
        final result = mergeMessages(
          [message(clientId: 'pending', delivery: Delivery.sending)],
          [
            message(id: 'two', clientId: 'two', sequence: 2),
            message(id: 'one', clientId: 'one', sequence: 1),
          ],
        );
        expect(result.map((m) => m.sequence), [1, 2, 0]);
      },
    );
    test('retry preserves the original clientMsgId', () {
      final failed = message(delivery: Delivery.failed);
      expect(
        failed.withDelivery(Delivery.sending).clientMsgId,
        failed.clientMsgId,
      );
    });
    test('a recall snapshot does not expose the old content', () {
      final recalled = ChatMessage.fromJson({
        'messageId': 'one',
        'conversationId': 'private:1:2',
        'sequence': 1,
        'fromUserId': 2,
        'content': '不应显示',
        'isRecalled': true,
      });
      expect(recalled.displayContent, '这条消息已撤回');
    });
  });
}
