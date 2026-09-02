import 'dart:convert';

import 'package:ai_plush_companion/features/devices/data/device_repository.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../support/fake_http_client_adapter.dart';

void main() {
  test('decodes a successful device bind whose data is null', () async {
    final adapter = FakeHttpClientAdapter((request) {
      return jsonResponse(
        jsonEncode({'code': 0, 'msg': 'success', 'data': null}),
      );
    });
    final repository = DeviceRepository(testApiClient(adapter));

    await repository.bind(activationCode: '123456', profileId: 'p1');

    expect(adapter.requests.single.path, '/companion/devices/bind');
    expect(adapter.requests.single.data, {
      'activationCode': '123456',
      'profileId': 'p1',
    });
  });

  test('sends basic device commands through the command endpoint', () async {
    final adapter = FakeHttpClientAdapter((request) {
      return jsonResponse(
        jsonEncode({'code': 0, 'msg': 'success', 'data': null}),
      );
    });
    final repository = DeviceRepository(testApiClient(adapter));

    await repository.command('d1', 'volume', 55);

    expect(adapter.requests.single.path, '/companion/devices/d1/commands');
    expect(adapter.requests.single.data, {'command': 'volume', 'value': 55});
  });
}
