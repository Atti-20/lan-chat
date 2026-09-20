import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/application/navigation_counts_controller.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';
import 'package:meshx_flutter_probe/data/models.dart';
import 'package:meshx_flutter_probe/data/friends_models.dart';
import 'package:meshx_flutter_probe/data/broadcast_models.dart';

class CountsChat extends ChatController {
  void signal() => notifyListeners();
}

FriendRequestItem request(int id, {int to = 1, int status = 0}) =>
    FriendRequestItem(
      id: id,
      fromUserId: 9,
      toUserId: to,
      message: '',
      status: status,
    );
const broadcast = BroadcastSummary(
  id: 1,
  senderId: 9,
  title: '任务',
  content: '',
  status: 'ACTIVE',
  priority: 'NORMAL',
  confirmationRequired: true,
  requireImageProof: false,
  requireLocationProof: false,
);

BroadcastSummary timedTask(int id, DateTime deadline) => BroadcastSummary(
  id: id,
  senderId: 9,
  title: 'task',
  content: '',
  status: 'ACTIVE',
  priority: 'NORMAL',
  confirmationRequired: true,
  requireImageProof: false,
  requireLocationProof: false,
  deadlineAt: deadline,
);

class CountsApi extends MeshXApi {
  CountsApi(int user) : super(Uri.parse('https://counts.invalid')) {
    session = Session(user, 'user', 'synthetic');
  }
  int friendCalls = 0, broadcastCalls = 0;
  Future<List<FriendRequestItem>> Function() friendsResponse = () async => [];
  Future<List<BroadcastSummary>> Function() broadcastResponse = () async => [];
  @override
  Future<List<FriendRequestItem>> friendRequests() {
    friendCalls++;
    return friendsResponse();
  }

  @override
  Future<List<BroadcastSummary>> broadcasts({bool pending = false}) {
    expect(pending, isTrue);
    broadcastCalls++;
    return broadcastResponse();
  }
}

Future<void> settle() => Future<void>.delayed(Duration.zero);

void main() {
  test(
    'expired summary checks are bounded and preserve server count',
    () async {
      final deadline = DateTime.now().subtract(const Duration(seconds: 1));
      final api = CountsApi(1)
        ..broadcastResponse = () async => [
          timedTask(1, deadline),
          timedTask(2, deadline),
        ];
      final chat = CountsChat()
        ..api = api
        ..online = true;
      final counts = NavigationCountsController(chat);
      try {
        await Future<void>.delayed(const Duration(milliseconds: 6300));
        expect(api.broadcastCalls, 6); // initial read plus five shared rechecks
        expect(counts.broadcastTasks, 2); // no local expiry subtraction
      } finally {
        counts.dispose();
        chat.dispose();
      }
    },
  );

  for (final action in ['disconnect', 'owner', 'dispose']) {
    test('$action cancels pending deadline requests', () async {
      final api = CountsApi(1)
        ..broadcastResponse = () async => [
          timedTask(1, DateTime.now().add(const Duration(milliseconds: 100))),
        ];
      final chat = CountsChat()
        ..api = api
        ..online = true;
      final counts = NavigationCountsController(chat);
      await settle();
      if (action == 'disconnect') {
        chat.online = false;
        chat.signal();
      } else if (action == 'owner') {
        chat.api = CountsApi(2);
        chat.signal();
      } else {
        counts.dispose();
      }
      try {
        await Future<void>.delayed(const Duration(milliseconds: 250));
        expect(api.broadcastCalls, 1);
      } finally {
        if (action != 'dispose') counts.dispose();
        chat.dispose();
        api.close();
      }
    });
  }

  test('changing a deadline replaces the previous scheduled read', () async {
    final api = CountsApi(1)
      ..broadcastResponse = () async => [
        timedTask(1, DateTime.now().add(const Duration(milliseconds: 100))),
      ];
    final chat = CountsChat()
      ..api = api
      ..online = true;
    final counts = NavigationCountsController(chat);
    try {
      await settle();
      api.broadcastResponse = () async => [
        timedTask(1, DateTime.now().add(const Duration(hours: 1))),
      ];
      counts.refreshBroadcasts();
      await settle();
      await Future<void>.delayed(const Duration(milliseconds: 250));
      expect(api.broadcastCalls, 2);
      expect(counts.broadcastTasks, 1);
    } finally {
      counts.dispose();
      chat.dispose();
    }
  });

  test('pending broadcast deadline refreshes without an event', () async {
    final expired = Completer<void>();
    final api = CountsApi(1);
    final deadline = DateTime.now().add(const Duration(milliseconds: 100));
    var calls = 0;
    api.broadcastResponse = () async {
      calls++;
      return calls == 1
          ? [
              BroadcastSummary(
                id: 1,
                senderId: 9,
                title: 'task',
                content: '',
                status: 'ACTIVE',
                priority: 'NORMAL',
                confirmationRequired: true,
                requireImageProof: false,
                requireLocationProof: false,
                deadlineAt: deadline,
              ),
            ]
          : [];
    };
    final chat = CountsChat()
      ..api = api
      ..online = true;
    final counts = NavigationCountsController(chat);
    counts.addListener(() {
      if (counts.broadcastTasks == 0 && !expired.isCompleted) {
        expired.complete();
      }
    });
    try {
      await settle();
      expect(counts.broadcastTasks, 1);
      await expired.future.timeout(const Duration(seconds: 3));
      expect(api.broadcastCalls, 2);
      expect(counts.broadcastTasks, 0);
    } finally {
      counts.dispose();
      chat.dispose();
    }
  });

  test(
    'separate authoritative counts, event refresh and failed count hidden',
    () async {
      final api = CountsApi(1);
      api.friendsResponse = () async => [
        request(1),
        request(1),
        request(2, to: 9),
        request(3, status: 1),
      ];
      api.broadcastResponse = () async => [broadcast, broadcast];
      final chat = CountsChat()..api = api;
      final counts = NavigationCountsController(chat);
      try {
        await settle();
        expect(api.friendCalls, 0);
        expect(counts.friendRequests, isNull);
        chat.online = true;
        chat.signal();
        await settle();
        expect(counts.friendRequests, 1);
        expect(counts.broadcastTasks, 1);
        api.friendsResponse = () async => throw const ApiException('offline');
        chat.notifyFriendRequestsChanged();
        await settle();
        expect(counts.friendRequests, isNull);
        expect(counts.broadcastTasks, 1);
        api.friendsResponse = () async => [];
        api.broadcastResponse = () async => [];
        chat.notifyFriendRequestsChanged();
        chat.notifyBroadcastsChanged();
        await settle();
        expect(counts.friendRequests, 0);
        expect(counts.broadcastTasks, 0);
      } finally {
        counts.dispose();
        chat.dispose();
      }
    },
  );

  test(
    'old owner and pre-disconnect responses cannot populate new counts',
    () async {
      final old = Completer<List<FriendRequestItem>>();
      final api = CountsApi(1)..friendsResponse = () => old.future;
      final chat = CountsChat()
        ..api = api
        ..online = true;
      final counts = NavigationCountsController(chat);
      final next = CountsApi(2)
        ..friendsResponse = () async => [request(2, to: 2)];
      try {
        chat.api = next;
        chat.signal();
        await settle();
        old.complete([request(1), request(3)]);
        await settle();
        expect(counts.friendRequests, 1);
        final delayed = Completer<List<FriendRequestItem>>();
        next.friendsResponse = () => delayed.future;
        counts.refreshFriends();
        chat.online = false;
        chat.signal();
        expect(counts.friendRequests, isNull);
        expect(counts.broadcastTasks, isNull);
        next.friendsResponse = () async => [];
        chat.online = true;
        chat.signal();
        await settle();
        delayed.complete([request(5, to: 2)]);
        await settle();
        expect(counts.friendRequests, 0);
      } finally {
        counts.dispose();
        chat.dispose();
        api.close();
      }
    },
  );

  test(
    'event burst during request is coalesced into one trailing refresh',
    () async {
      final gate = Completer<List<FriendRequestItem>>();
      final api = CountsApi(1)..friendsResponse = () => gate.future;
      final chat = CountsChat()
        ..api = api
        ..online = true;
      final counts = NavigationCountsController(chat);
      try {
        for (var i = 0; i < 20; i++) {
          chat.notifyFriendRequestsChanged();
        }
        await settle();
        expect(api.friendCalls, 1);
        api.friendsResponse = () async => [];
        gate.complete([request(1)]);
        await settle();
        expect(api.friendCalls, 2);
        expect(counts.friendRequests, 0);
      } finally {
        counts.dispose();
        chat.dispose();
      }
    },
  );

  test('dispose discards late responses and stops event refresh', () async {
    final gate = Completer<List<FriendRequestItem>>();
    final api = CountsApi(1)..friendsResponse = () => gate.future;
    final chat = CountsChat()
      ..api = api
      ..online = true;
    final counts = NavigationCountsController(chat);
    counts.dispose();
    gate.complete([request(1)]);
    chat.notifyFriendRequestsChanged();
    await settle();
    expect(counts.friendRequests, isNull);
    expect(api.friendCalls, 1);
    chat.dispose();
  });
}
