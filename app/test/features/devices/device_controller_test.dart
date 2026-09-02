import 'dart:convert';

import 'package:ai_plush_companion/features/devices/application/device_controller.dart';
import 'package:ai_plush_companion/features/devices/data/device_repository.dart';
import 'package:ai_plush_companion/features/profiles/domain/profile_models.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../support/fake_http_client_adapter.dart';

void main() {
  test(
    'loads devices and only updates the local alias after server success',
    () async {
      final adapter = FakeHttpClientAdapter((request) {
        final data = request.method == 'GET'
            ? [
                {
                  'id': 'd1',
                  'alias': '床头设备',
                  'board': 'S3',
                  'macAddress': 'AA:BB',
                  'activeProfileId': 'p1',
                  'online': true,
                },
              ]
            : null;
        return jsonResponse(
          jsonEncode({'code': 0, 'msg': 'success', 'data': data}),
        );
      });
      final repository = DeviceRepository(testApiClient(adapter));
      final controller = DeviceController();

      expect(await controller.load(repository), isTrue);
      expect(await controller.rename(repository, 'd1', '新名称'), isTrue);
      expect(controller.devices.single.alias, '新名称');
    },
  );

  test(
    'keeps the previous command value when the device rejects a command',
    () async {
      final adapter = FakeHttpClientAdapter((request) {
        return jsonResponse(
          jsonEncode({'code': 409, 'msg': 'device_offline', 'data': null}),
        );
      });
      final repository = DeviceRepository(testApiClient(adapter));
      final controller = DeviceController()
        ..devices = [
          const CompanionDevice(
            id: 'd1',
            alias: '设备',
            board: 'S3',
            macAddress: 'AA',
            profileId: 'p1',
            online: true,
            volume: 40,
          ),
        ];

      expect(
        await controller.setCommand(repository, 'd1', 'volume', 90),
        isFalse,
      );
      expect(controller.devices.single.volume, 40);
    },
  );
}
