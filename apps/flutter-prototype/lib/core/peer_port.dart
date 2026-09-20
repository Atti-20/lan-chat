abstract interface class PeerPort {
  Future<PeerLink> create();
}

abstract interface class PeerLink {
  Stream<Object> get messages;
  Future<String> offer();
  Future<String> answer(String offer);
  Future<void> acceptAnswer(String answer);
  Future<void> send(Object data);
  Future<void> close();
}
