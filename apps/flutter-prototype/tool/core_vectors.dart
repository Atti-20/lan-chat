// Executed by the standalone Dart VM, without Flutter bindings or engine.
import 'dart:convert';
import 'dart:io';
import 'package:meshx_flutter_probe/core/models.dart';
import 'package:meshx_flutter_probe/core/store.dart';

void main() {
  var checks = 0;
  void check(bool value, String name) {
    if (!value) throw StateError(name);
    checks++;
  }
  final vectors = jsonDecode(File('../../contracts/fixtures/core-v1.json').readAsStringSync()) as Json;
  for (final item in vectors['sequences'] as List) {
    check(advanceContiguousSequence(item['current'], (item['candidates'] as List).cast<int>()) ==
        item['expected'], item['id']);
  }
  final deliveries = (vectors['frames'] as List).where((item) =>
      item['valid'] == true && item['frame']['event'] == 'CHAT_DELIVER').toList();
  for (final item in deliveries) {
    final message = ChatMessage.fromJson(item['frame']['payload'] as Json);
    check(mergeMessages([message], [message]).length == 1, '${item['id']} duplicate');
    final pending = message.withDelivery(Delivery.sending, id: '', serverSequence: 0);
    final restored = ChatMessage.restore(pending.toJson());
    check(restored.delivery == Delivery.queued && restored.clientMsgId == pending.clientMsgId &&
      restored.content == pending.content, '${item['id']} durable outbox identity');
  }
  for (final attempt in [0, 1, 2, 3, 4, 5, 100]) {
    check(reconnectDelay(attempt).inMilliseconds ==
        [1000, 2000, 4000, 8000, 16000, 30000][attempt.clamp(0, 5)], 'retry cap $attempt');
  }
  check(accountScope(Uri.parse('https://a.example'), 1) != accountScope(Uri.parse('https://b.example'), 1), 'server scope');
  check(accountScope(Uri.parse('https://a.example'), 1) != accountScope(Uri.parse('https://a.example'), 2), 'account scope');
  stdout.writeln('PASS: $checks pure Dart behavior/vector checks, no Flutter runtime.');
}
