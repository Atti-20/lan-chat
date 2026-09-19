import 'dart:io';
import 'dart:convert';

import 'package:flutter/foundation.dart';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/application/platform_coordinator.dart';
import 'package:meshx_flutter_probe/data/models.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';
import 'package:meshx_flutter_probe/data/attachments_models.dart';
import 'package:meshx_flutter_probe/platform/discovery.dart';
import 'package:meshx_flutter_probe/platform/storage.dart';
import 'package:meshx_flutter_probe/platform/system_capabilities.dart';
import 'package:meshx_flutter_probe/ui/app.dart';

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets(
    'C01 real Vue to Flutter P0 smoke',
    (tester) async {
      const origin = String.fromEnvironment('MESHX_NODE');
      const username = String.fromEnvironment('PROBE_USERNAME');
      const password = String.fromEnvironment('PROBE_PASSWORD');
      const groupId = String.fromEnvironment('PROBE_GROUP_ID');
      const webMessage = String.fromEnvironment('C01_WEB_MESSAGE');
      const flutterMessage = String.fromEnvironment('C01_FLUTTER_MESSAGE');
      const runId = String.fromEnvironment('C01_RUN_ID');
      const broadcastId = int.fromEnvironment('C01_BROADCAST_ID');
      const expectRemoval = bool.fromEnvironment('C01_BROADCAST_REMOVAL');
      const expectExpiry = bool.fromEnvironment('C01_BROADCAST_EXPIRY');
      const expectImage = bool.fromEnvironment('C01_BROADCAST_IMAGE');
      const expectContentImage = bool.fromEnvironment('C01_CONTENT_IMAGE');
      const attachmentName = String.fromEnvironment('C01_ATTACHMENT_NAME');
      const attachmentOnly = bool.fromEnvironment('C01_ATTACHMENT_ONLY');
      const attachmentError = String.fromEnvironment('C01_ATTACHMENT_ERROR');
      const cancelUpload = bool.fromEnvironment('C01_CANCEL_UPLOAD');
      expect(
        !cancelUpload || (attachmentName.isNotEmpty && attachmentError.isEmpty),
        isTrue,
      );
      expect(!attachmentOnly || attachmentName.isNotEmpty, isTrue);
      expect(attachmentError.isEmpty || attachmentName.isNotEmpty, isTrue);
      const cancelImageFirst = bool.fromEnvironment('C01_CANCEL_IMAGE_FIRST');
      const imageErrorFirst = String.fromEnvironment('C01_IMAGE_ERROR_FIRST');
      expect(!cancelImageFirst || expectImage, isTrue);
      expect(
        imageErrorFirst.isEmpty || (expectImage && !cancelImageFirst),
        isTrue,
      );
      expect(expectRemoval && expectExpiry, isFalse);
      for (final entry in {
        'MESHX_NODE': origin,
        'PROBE_USERNAME': username,
        'PROBE_PASSWORD': password,
        'PROBE_GROUP_ID': groupId,
        'C01_WEB_MESSAGE': webMessage,
        'C01_FLUTTER_MESSAGE': flutterMessage,
        'C01_RUN_ID': runId,
      }.entries) {
        expect(entry.value, isNotEmpty, reason: '${entry.key} is required');
      }

      final metrics = <String, Object?>{
        'schema': 'meshx.c01-cross-client-smoke/1',
        'runId': runId,
        'platform': Platform.isAndroid ? 'android' : 'ios',
        'mode': kProfileMode ? 'profile-integration' : 'debug-integration',
        'entrypoint': 'integration_test/c01_cross_client_smoke_test.dart',
        'realSpring': true,
        'realVueMessageExpected': webMessage,
        'c01GateClosed': false,
        'broadcastUi': 'NOT_RUN',
        'attachmentUi': 'NOT_RUN',
        'scope': attachmentOnly
            ? (attachmentError.isEmpty
                  ? 'attachment-cancel-retry'
                  : 'attachment-upload-failure-retry')
            : 'p0-smoke',
      };
      binding.reportData = {'metrics': metrics};

      final evidence = File(
        '${Directory.systemTemp.path}/meshx-c01-result.json',
      );
      await evidence.writeAsString(
        jsonEncode({'runId': runId, 'status': 'RUNNING'}),
        flush: true,
      );
      try {
        final credentials = NativeCredentialStore();
        await credentials.clear();
        final controller = ChatController(
          allowLocalHttp: true,
          discovery: NativeNodeDiscovery(),
          store: FileChatStore(),
          credentials: credentials,
        );
        final system = NativeSystemCapabilities();
        final platform = PlatformCoordinator(
          chat: controller,
          lifecycle: FlutterLifecyclePort(),
          notifications: system,
          files: system,
          sharePort: system,
          network: system,
          settings: system,
          runtimeInfo: system,
          disposeAdapters: system.dispose,
        );
        system.start();
        metrics['nativeSystemPorts'] = true;

        Future<void> waitFor(
          bool Function() predicate,
          String label, {
          Duration timeout = const Duration(seconds: 45),
        }) async {
          final end = DateTime.now().add(timeout);
          while (!predicate() && DateTime.now().isBefore(end)) {
            await tester.pump(const Duration(milliseconds: 100));
          }
          if (!predicate()) {
            debugPrint(
              'C01_WAIT_FAILURE ${{'label': label, 'recoveryStatus': controller.recoveryStatus, 'active': controller.active?.id, 'messages': controller.messages.length, 'visibleTexts': find.byType(Text).evaluate().map((element) => (element.widget as Text).data).whereType<String>().take(60).toList()}}',
            );
          }
          expect(
            predicate(),
            isTrue,
            reason: '$label; error=${controller.error}',
          );
        }

        Future<void> openSection(String label) async {
          if (label == '个人资料与设置') {
            await tester.tap(find.byKey(const Key('open-profile')));
          } else {
            await tester.tap(find.text(label));
          }
          await tester.pump();
        }

        Future<void> sendText(String value) async {
          await tester.enterText(find.byKey(const Key('composer')), value);
          await tester.pump(const Duration(milliseconds: 300));
          await tester.ensureVisible(find.byKey(const Key('send-message')));
          await tester.tap(find.byKey(const Key('send-message')));
          await tester.pump();
          await waitFor(
            () => controller.messages.any(
              (message) =>
                  message.content == value && message.delivery == Delivery.sent,
            ),
            'message must receive a real server ACK',
          );
        }

        await tester.pumpWidget(
          MeshXApp(controller: controller, platform: platform),
        );
        await tester.pumpAndSettle();
        await tester.enterText(find.byKey(const Key('node-origin')), origin);
        await tester.enterText(find.byKey(const Key('username')), username);
        await tester.enterText(find.byKey(const Key('password')), password);
        FocusManager.instance.primaryFocus?.unfocus();
        await tester.pumpAndSettle();
        await tester.ensureVisible(find.byKey(const Key('login')));
        await tester.tap(find.byKey(const Key('login')));
        await waitFor(
          () =>
              controller.online &&
              controller.conversations.any(
                (item) => item.id == 'group:$groupId',
              ),
          'AUTH/SYNC and seeded group',
        );
        metrics['authSync'] = true;

        await tester.tap(find.byKey(Key('conversation-group:$groupId')));
        await waitFor(
          () =>
              controller.active?.id == 'group:$groupId' &&
              find.byKey(const Key('composer')).evaluate().isNotEmpty,
          'group selection and composer mount',
        );
        await waitFor(
          () => controller.messages.any(
            (message) =>
                message.content == webMessage &&
                message.fromUserId != controller.session!.userId,
          ),
          'message sent through the real Vue page must reach Flutter',
        );
        await waitFor(
          () => find.text(webMessage).evaluate().isNotEmpty,
          'received Vue message must render in Flutter',
        );
        final inbound = controller.messages.singleWhere(
          (message) => message.content == webMessage,
        );
        metrics['vueToFlutter'] = {
          'messageId': inbound.messageId,
          'sequence': inbound.sequence,
          'senderIsPeer': inbound.fromUserId != controller.session!.userId,
        };

        await sendText(flutterMessage);
        final outbound = controller.messages.singleWhere(
          (message) => message.content == flutterMessage,
        );
        metrics['flutterToVue'] = {
          'clientMsgId': outbound.clientMsgId,
          'sequence': outbound.sequence,
          'delivery': outbound.delivery.name,
        };

        if (attachmentName.isNotEmpty) {
          final existing = controller.messages
              .map((message) => message.key)
              .toSet();
          final attach = find.byKey(const Key('attach-file'));
          await tester.ensureVisible(attach);
          await tester.tap(attach);
          await waitFor(() => platform.busy, 'system file picker must open');
          debugPrint('C01_ATTACHMENT_CANCEL_NATIVE_PICKER');
          await waitFor(
            () => !platform.busy,
            'cancel system file selection',
            timeout: const Duration(seconds: 120),
          );
          expect(platform.fileStatus.status, CapabilityStatus.cancelled);
          await tester.pumpAndSettle();
          expect(
            controller.messages.where(
              (message) =>
                  !existing.contains(message.key) &&
                  message.fromUserId == controller.session!.userId,
            ),
            isEmpty,
          );
          expect(platform.selectedFile, isNull);
          await waitFor(
            () => controller.online,
            'resume AUTH/SYNC after native picker cancellation',
          );
          await tester.pumpAndSettle();
          await tester.tap(attach);
          await waitFor(() => platform.busy, 'retry system picker must open');
          if (cancelUpload) {
            debugPrint('C01_ATTACHMENT_SELECT_STALLED_FILE');
            await waitFor(
              () =>
                  !platform.busy &&
                  platform.selectedFile != null &&
                  find
                      .byType(LinearProgressIndicator)
                      .evaluate()
                      .any(
                        (element) =>
                            (element.widget as LinearProgressIndicator).value ==
                            1,
                      ),
              'native file read must finish while upload response is held',
              timeout: const Duration(seconds: 120),
            );
            await tester.tap(find.byTooltip('取消文件操作'));
            await waitFor(
              () =>
                  platform.selectedFile == null &&
                  find.byTooltip('发送文件或图片').evaluate().isNotEmpty,
              'cancel must release selection and restore composer',
            );
            expect(find.byType(MaterialBanner), findsNothing);
            expect(
              controller.messages.where(
                (message) =>
                    !existing.contains(message.key) &&
                    message.fromUserId == controller.session!.userId,
              ),
              isEmpty,
            );
            final persisted = await controller.api!.history('group:$groupId');
            expect(
              persisted.where(
                (message) =>
                    message.fromUserId == controller.session!.userId &&
                    message.sequence > outbound.sequence,
              ),
              isEmpty,
            );
            metrics['uploadCancellationUi'] = {
              'cancelButtonTapped': true,
              'selectionReleased': true,
              'zeroNewLocalAndServerMessages': true,
              'errorBannerAbsent': true,
            };
            metrics['scope'] = 'attachment-upload-cancel-retry';
            await waitFor(() => controller.online, 'retry requires ONLINE');
            await tester.tap(attach);
            await waitFor(
              () => platform.busy,
              'picker must reopen after upload cancellation',
            );
          }
          if (attachmentError.isNotEmpty) {
            debugPrint('C01_ATTACHMENT_SELECT_FAILURE_FILE');
            await waitFor(
              () => find.text(attachmentError).evaluate().isNotEmpty,
              'real upload rejection must be visible',
              timeout: const Duration(seconds: 120),
            );
            await waitFor(
              () => !platform.busy && platform.selectedFile == null,
              'failed upload must release its file handle',
            );
            expect(find.text(attachmentError).hitTestable(), findsOneWidget);
            expect(
              controller.messages.where(
                (message) =>
                    !existing.contains(message.key) &&
                    message.fromUserId == controller.session!.userId,
              ),
              isEmpty,
            );
            final persisted = await controller.api!.history('group:$groupId');
            expect(
              persisted.where(
                (message) =>
                    message.fromUserId == controller.session!.userId &&
                    message.sequence > outbound.sequence,
              ),
              isEmpty,
              reason: 'failed upload must not persist a message on Spring',
            );
            expect(find.byTooltip('发送文件或图片'), findsOneWidget);
            metrics['attachmentFailureUi'] = {
              'error': attachmentError,
              'visible': true,
              'selectionReleased': true,
              'zeroNewLocalAndServerMessages': true,
            };
            await tester.tap(
              find.descendant(
                of: find.byType(MaterialBanner),
                matching: find.text('关闭'),
              ),
            );
            await tester.pumpAndSettle();
            expect(find.text(attachmentError), findsNothing);
            await waitFor(() => controller.online, 'retry requires ONLINE');
            await tester.tap(attach);
            await waitFor(
              () => platform.busy,
              'picker must reopen after failure',
            );
          }
          debugPrint('C01_ATTACHMENT_SELECT_NATIVE_FILE');
          bool isNewAttachment(ChatMessage message) =>
              !existing.contains(message.key) &&
              message.fromUserId == controller.session!.userId &&
              {'file', 'image'}.contains(message.contentType) &&
              AttachmentData.decode(message.content).name == attachmentName;
          await waitFor(
            () => controller.messages.any(
              (message) =>
                  isNewAttachment(message) && message.delivery == Delivery.sent,
            ),
            'native selected attachment must receive server ACK',
            timeout: const Duration(seconds: 120),
          );
          final sent = controller.messages.singleWhere(isNewAttachment);
          final data = AttachmentData.decode(sent.content);
          expect(sent.sequence, greaterThan(0));
          expect(data.size, greaterThan(0));
          await waitFor(
            () => !platform.busy && platform.selectedFile == null,
            'uploaded file handle must be released',
          );
          await waitFor(
            () => find.text(attachmentName).evaluate().isNotEmpty,
            'attachment bubble must render',
          );
          metrics['attachmentUi'] = {
            'cancelledWithoutMessage': true,
            'name': data.name,
            'size': data.size,
            'fileHash': data.fileHash,
            'url': data.url,
            'selectionReleased': true,
            'clientMsgId': sent.clientMsgId,
            'messageId': sent.messageId,
            'sequence': sent.sequence,
            'delivery': sent.delivery.name,
          };
          if (attachmentOnly) {
            metrics['passed'] = true;
            binding.reportData = {'metrics': metrics};
            await tester.pumpWidget(const SizedBox.shrink());
            return;
          }
        }

        await tester.tap(find.byTooltip('返回消息列表'));
        await tester.pumpAndSettle();
        expect(controller.active, isNull);
        expect(find.byType(NavigationBar), findsOneWidget);
        final friends = await controller.api!.friends();
        expect(friends, isNotEmpty);
        await openSection('联系人');
        await waitFor(
          () => find
              .byKey(Key('friend-${friends.first.userId}'))
              .evaluate()
              .isNotEmpty,
          'real friend list must render',
        );
        await tester.tap(find.byKey(Key('friend-${friends.first.userId}')));
        await waitFor(
          () =>
              controller.active?.id.startsWith('private:') == true &&
              find.byKey(const Key('composer')).evaluate().isNotEmpty,
          'friend row must open a private conversation',
        );
        metrics['friendUi'] = true;
        await sendText('C01 private Flutter $runId');

        await tester.tap(find.byTooltip('返回消息列表'));
        await tester.pumpAndSettle();
        expect(controller.active, isNull);
        expect(find.byType(NavigationBar), findsOneWidget);
        metrics['conversationBackUi'] = true;
        await openSection('群聊');
        await waitFor(
          () => find.byKey(Key('group-$groupId')).evaluate().isNotEmpty,
          'real group list must render',
        );
        await tester.tap(find.byKey(Key('group-$groupId')));
        await waitFor(
          () => find.byKey(const Key('group-details')).evaluate().isNotEmpty,
          'group details must open',
        );
        final members = await controller.api!.groupMembers(int.parse(groupId));
        expect(members.length, 3);
        for (final member in members) {
          expect(
            find.byKey(Key('group-member-${member.userId}')),
            findsOneWidget,
          );
        }
        metrics['groupUi'] = {'members': members.length};
        await tester.tap(find.byTooltip('Back').last);
        await tester.pumpAndSettle();
        await tester.tap(find.text('消息'));
        await tester.pumpAndSettle();

        if (broadcastId > 0) {
          await openSection('广播');
          final pending = find.byKey(Key('broadcast-$broadcastId-pending'));
          await waitFor(
            () => pending.evaluate().isNotEmpty,
            'server-created broadcast must appear in mobile pending list',
          );
          await tester.tap(pending);
          await waitFor(
            () => find
                .byKey(const Key('complete-broadcast'))
                .evaluate()
                .isNotEmpty,
            'broadcast detail must expose completion action',
          );
          final viewed = await controller.api!.broadcastDetail(broadcastId);
          expect(viewed.receiver?.viewedAt, isNotNull);
          expect(viewed.receiver?.confirmedAt, isNull);
          expect(viewed.broadcast.requireImageProof, expectImage);
          if (expectContentImage) {
            expect(viewed.contentImageUrls, isNotEmpty);
            final imageEntry = find.byKey(
              const Key('broadcast-content-image-0'),
            );
            await tester.ensureVisible(imageEntry);
            await tester.tap(imageEntry);
            await waitFor(
              () => find
                  .byType(RawImage)
                  .evaluate()
                  .any((element) => (element.widget as RawImage).image != null),
              'real broadcast content image must decode on Android',
            );
            expect(find.text('广播图片 1'), findsOneWidget);
            expect(find.byType(InteractiveViewer), findsOneWidget);
            metrics['contentImageUi'] = {
              'decoded': true,
              'count': viewed.contentImageUrls.length,
            };
            await tester.tap(find.byTooltip('Back').last);
            await tester.pumpAndSettle();
            expect(find.byKey(const Key('broadcast-detail')), findsOneWidget);
            expect(find.text('广播图片 1'), findsNothing);
          }
          if (expectExpiry) {
            expect(viewed.broadcast.deadlineAt, isNotNull);
            expect(viewed.broadcast.expired, isFalse);
            debugPrint('C01_BROADCAST_WAITING_FOR_DEADLINE id=$broadcastId');
            await waitFor(
              () => find
                  .byKey(const Key('complete-broadcast'))
                  .evaluate()
                  .isEmpty,
              'deadline must remove action without navigation or user refresh',
              timeout: const Duration(minutes: 3),
            );
            final expired = await controller.api!.broadcastDetail(broadcastId);
            expect(expired.broadcast.expired, isTrue);
            expect(expired.canSubmit, isFalse);
            expect(expired.receiver?.confirmedAt, isNull);
            expect(expired.receiver?.completedAt, isNull);
            metrics['broadcastUi'] = {
              'id': broadcastId,
              'expiredWhileOpen': true,
              'deadline': expired.broadcast.deadlineAt!.toIso8601String(),
              'completionAttempted': false,
            };
          } else if (expectRemoval) {
            debugPrint('C01_BROADCAST_READY_FOR_WEB_REMOVAL id=$broadcastId');
            await waitFor(
              () => find
                  .byKey(const Key('complete-broadcast'))
                  .evaluate()
                  .isEmpty,
              'real Web removal must remove action from the mounted detail',
              timeout: const Duration(seconds: 90),
            );
            expect(
              find.text('已无权查看或广播已不存在').evaluate().isNotEmpty ||
                  find.text('当前广播已结束、过期或你已不在目标范围内。').evaluate().isNotEmpty,
              isTrue,
            );
            try {
              final removed = await controller.api!.broadcastDetail(
                broadcastId,
              );
              expect(removed.receiver?.targetStatus, 'REMOVED');
              expect(removed.receiver?.confirmedAt, isNull);
              expect(removed.canSubmit, isFalse);
            } on ApiException catch (failure) {
              expect(failure.code, 403);
            }
            metrics['broadcastUi'] = {
              'id': broadcastId,
              'viewed': true,
              'removedWhileOpen': true,
              'completionAttempted': false,
            };
          } else {
            if (imageErrorFirst.isNotEmpty) {
              await tester.ensureVisible(
                find.byKey(const Key('complete-broadcast')),
              );
              await tester.tap(find.byKey(const Key('complete-broadcast')));
              debugPrint('C01_BROADCAST_SELECT_INVALID_FILE');
              await waitFor(
                () => find
                    .descendant(
                      of: find.byType(SnackBar),
                      matching: find.text(imageErrorFirst),
                    )
                    .evaluate()
                    .isNotEmpty,
                'invalid file must display visible error feedback',
                timeout: const Duration(seconds: 120),
              );
              expect(platform.selectedFile, isNull);
              final rejected = await controller.api!.broadcastDetail(
                broadcastId,
              );
              expect(rejected.receiver?.confirmedAt, isNull);
              expect(rejected.receiver?.completedAt, isNull);
              expect(rejected.canSubmit, isTrue);
              metrics['imageRejectedWithoutCompletion'] = imageErrorFirst;
              await tester.pump(const Duration(seconds: 5));
            }
            if (cancelImageFirst) {
              await tester.ensureVisible(
                find.byKey(const Key('complete-broadcast')),
              );
              await tester.tap(find.byKey(const Key('complete-broadcast')));
              await waitFor(
                () => platform.busy,
                'native picker must be active',
              );
              debugPrint('C01_BROADCAST_CANCEL_NATIVE_IMAGE_PICKER');
              await waitFor(
                () => !platform.busy,
                'native picker must return after cancellation',
                timeout: const Duration(seconds: 120),
              );
              expect(platform.fileStatus.status, CapabilityStatus.cancelled);
              final cancelled = await controller.api!.broadcastDetail(
                broadcastId,
              );
              expect(cancelled.receiver?.confirmedAt, isNull);
              expect(cancelled.receiver?.completedAt, isNull);
              expect(cancelled.canSubmit, isTrue);
              expect(find.text('已提交回执，这条广播已标记为完成。'), findsNothing);
              await tester.pumpAndSettle();
              metrics['imagePickerCancelledWithoutCompletion'] = true;
            }
            if (expectImage) {
              debugPrint('C01_BROADCAST_OPENING_NATIVE_IMAGE_PICKER');
            }
            await tester.ensureVisible(
              find.byKey(const Key('complete-broadcast')),
            );
            await tester.tap(find.byKey(const Key('complete-broadcast')));
            await waitFor(
              () => find.text('已提交回执，这条广播已标记为完成。').evaluate().isNotEmpty,
              'completed broadcast must render authoritative success',
              // Native picker selection is coordinated outside Flutter's tester.
              timeout: Duration(seconds: expectImage ? 120 : 45),
            );
            final completed = await controller.api!.broadcastDetail(
              broadcastId,
            );
            expect(completed.receiver?.confirmStatus, 'EXECUTED');
            expect(completed.receiver?.confirmedAt, isNotNull);
            expect(completed.receiver?.completedAt, isNotNull);
            expect(find.byKey(const Key('complete-broadcast')), findsNothing);
            metrics['broadcastUi'] = {
              'id': broadcastId,
              'viewed': true,
              'confirmStatus': completed.receiver!.confirmStatus,
              'completed': true,
              'imageProofRequired': expectImage,
            };
            expect(platform.selectedFile, isNull);
          }
          await tester.tap(find.byTooltip('Back').last);
          await tester.pumpAndSettle();
          await waitFor(
            () => find
                .byKey(Key('broadcast-$broadcastId-pending'))
                .evaluate()
                .isEmpty,
            'authoritative pending refresh must remove the handled broadcast',
          );
          await openSection('消息');
        }

        await openSection('个人资料与设置');
        await waitFor(
          () => find.byKey(const Key('save-profile')).evaluate().isNotEmpty,
          'real profile must render',
        );
        final nickname = 'C01-${runId.split('-').last}';
        await tester.enterText(
          find.byKey(const Key('profile-nickname')),
          nickname,
        );
        await tester.ensureVisible(find.byKey(const Key('save-profile')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('save-profile')));
        await waitFor(
          () => controller.session?.nickname == nickname,
          'profile save must be authoritative',
        );
        expect((await controller.api!.currentUser()).nickname, nickname);
        metrics['profileUi'] = true;

        await tester.scrollUntilVisible(
          find.byKey(const Key('profile-logout')),
          360,
          scrollable: find.byType(Scrollable).first,
        );
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('profile-logout')));
        await waitFor(
          () => controller.session == null,
          'logout must clear session',
        );
        expect(await credentials.read(), isNull);
        metrics['logout'] = true;
        metrics['passed'] = true;
        binding.reportData = {'metrics': metrics};
        await tester.pumpWidget(const SizedBox.shrink());
      } catch (error) {
        metrics['passed'] = false;
        metrics['error'] = error.toString();
        rethrow;
      } finally {
        await evidence.writeAsString(
          jsonEncode({
            'runId': runId,
            'status': metrics['passed'] == true ? 'PASS' : 'FAIL',
            'metrics': metrics,
          }),
          flush: true,
        );
      }
    },
    timeout: const Timeout(Duration(minutes: 6)),
  );
}
