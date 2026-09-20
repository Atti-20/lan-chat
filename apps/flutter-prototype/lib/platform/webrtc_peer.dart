import 'dart:async';
import 'dart:typed_data';
import 'package:flutter_webrtc/flutter_webrtc.dart';
import '../core/peer_port.dart';

class WebRtcPeerPort implements PeerPort {
  @override
  Future<PeerLink> create() async => _Link(
    await createPeerConnection({'iceServers': <Map<String, dynamic>>[]}),
  );
}

class _Link implements PeerLink {
  _Link(this.peer) {
    peer.onDataChannel = bind;
  }
  final RTCPeerConnection peer;
  final _messages = StreamController<Object>();
  final _opened = Completer<void>();
  RTCDataChannel? channel;
  bool closed = false;
  @override
  Stream<Object> get messages => _messages.stream;
  void bind(RTCDataChannel value) {
    if (closed || channel != null || value.label != 'lanchat-file') {
      unawaited(value.close());
      return;
    }
    channel = value;
    value.onMessage = (message) {
      if (!closed) {
        _messages.add(message.isBinary ? message.binary : message.text);
      }
    };
    value.onDataChannelState = (state) {
      if (state == RTCDataChannelState.RTCDataChannelOpen &&
          !_opened.isCompleted) {
        _opened.complete();
      }
    };
    if (value.state == RTCDataChannelState.RTCDataChannelOpen &&
        !_opened.isCompleted) {
      _opened.complete();
    }
  }

  Future<String> local(RTCSessionDescription description) async {
    await peer.setLocalDescription(description);
    final start = DateTime.now();
    while (!closed &&
        peer.iceGatheringState !=
            RTCIceGatheringState.RTCIceGatheringStateComplete &&
        DateTime.now().difference(start).inMilliseconds < 2500) {
      await Future<void>.delayed(const Duration(milliseconds: 50));
    }
    if (closed) throw StateError('直传已关闭');
    return (await peer.getLocalDescription())?.sdp ?? description.sdp!;
  }

  @override
  Future<String> offer() async {
    bind(
      await peer.createDataChannel(
        'lanchat-file',
        RTCDataChannelInit()..ordered = true,
      ),
    );
    return local(await peer.createOffer());
  }

  @override
  Future<String> answer(String offer) async {
    await peer.setRemoteDescription(RTCSessionDescription(offer, 'offer'));
    return local(await peer.createAnswer());
  }

  @override
  Future<void> acceptAnswer(String answer) =>
      peer.setRemoteDescription(RTCSessionDescription(answer, 'answer'));
  @override
  Future<void> send(Object data) async {
    await _opened.future.timeout(const Duration(seconds: 10));
    final start = DateTime.now();
    while (!closed && await channel!.getBufferedAmount() > 1024 * 1024) {
      if (DateTime.now().difference(start).inSeconds > 5) {
        throw TimeoutException('直传通道拥塞');
      }
      await Future<void>.delayed(const Duration(milliseconds: 20));
    }
    if (closed) throw StateError('直传已关闭');
    await channel!.send(
      data is String
          ? RTCDataChannelMessage(data)
          : RTCDataChannelMessage.fromBinary(
              Uint8List.fromList(data as List<int>),
            ),
    );
  }

  @override
  Future<void> close() async {
    if (closed) return;
    closed = true;
    await channel?.close();
    await peer.close();
    unawaited(_messages.close());
  }
}
