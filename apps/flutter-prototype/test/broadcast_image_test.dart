import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';
import 'package:meshx_flutter_probe/data/models.dart';
import 'package:meshx_flutter_probe/data/broadcast_models.dart';
import 'broadcasts_feature_test.dart' show detailJson;

const imagePath = '/api/v1/file/content/0123456789abcdef0123456789abcdef.png';

class ImageApi extends MeshXApi {
  ImageApi(super.origin) {
    session = const Session(1, 'fixture', 'synthetic-image-token');
  }
  int reads = 0;
  bool removed = false, unrelated = false;
  @override
  Future<BroadcastDetail> broadcastDetail(int id) async {
    reads++;
    return BroadcastDetail.fromJson({
      ...detailJson(target: removed ? 'REMOVED' : 'ACTIVE'),
      'contentEvidence': {
        'imageUrls': unrelated ? <String>[] : [imagePath],
      },
    });
  }
}

void main() {
  for (final outcome in [
    'success',
    'removed',
    'foreign',
    'unrelated',
    'redirect',
    'not-image',
  ]) {
    test(
      'broadcast image $outcome respects current recipient and node',
      () async {
        final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
        final api = ImageApi(Uri.parse('http://127.0.0.1:${server.port}'));
        var requests = 0;
        server.listen((request) async {
          requests++;
          expect(request.uri.path, imagePath);
          expect(
            request.headers.value(HttpHeaders.authorizationHeader),
            'Bearer synthetic-image-token',
          );
          if (outcome == 'redirect') {
            request.response.statusCode = 302;
            request.response.headers.set(
              HttpHeaders.locationHeader,
              'https://example.invalid/stolen',
            );
          } else {
            request.response.headers.contentType = ContentType(
              outcome == 'not-image' ? 'text' : 'image',
              outcome == 'not-image' ? 'plain' : 'png',
            );
            request.response.add([137, 80, 78, 71]);
          }
          if (outcome == 'removed') api.removed = true;
          await request.response.close();
        });
        api.unrelated = outcome == 'unrelated';
        final result = api.broadcastImageBytes(
          7,
          outcome == 'foreign'
              ? 'https://example.invalid$imagePath'
              : imagePath,
        );
        if (outcome == 'success') {
          expect(await result, [137, 80, 78, 71]);
          expect(api.reads, 2);
        } else {
          await expectLater(
            result,
            throwsA(anyOf(isA<ApiException>(), isA<FormatException>())),
          );
        }
        expect(requests, ['foreign', 'unrelated'].contains(outcome) ? 0 : 1);
        api.close();
        await server.close(force: true);
      },
    );
  }
}
