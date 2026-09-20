import 'models.dart';

/// App-owned, account-partitioned upload bytes and stable idempotency identity.
abstract interface class TransferStore {
  Future<Json> stage(String owner, Json metadata, Stream<List<int>> bytes);
  Future<List<Json>> list(String owner);
  Future<List<int>> read(String owner, String id, int offset, int length);
  Future<void> update(String owner, Json task);
  Future<void> remove(String owner, String id);
}
