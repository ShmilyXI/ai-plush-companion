import '../../../core/network/api_client.dart';

class DeviceRepository {
  const DeviceRepository(this.api);

  final ApiClient api;

  static const _basePath = '/companion/devices';

  Future<List<Map<String, dynamic>>> list() =>
      api.request((dio) => dio.get(_basePath), _mapList);

  Future<Map<String, dynamic>> get(String id) =>
      api.request((dio) => dio.get('$_basePath/${_segment(id)}'), _map);

  Future<void> bind({required String activationCode, String? profileId}) async {
    await bindWithResult(activationCode: activationCode, profileId: profileId);
  }

  Future<Object?> bindWithResult({
    required String activationCode,
    String? profileId,
  }) => api.request(
    (dio) => dio.post(
      '$_basePath/bind',
      data: {
        'activationCode': activationCode,
        if (profileId != null && profileId.isNotEmpty) 'profileId': profileId,
      },
    ),
    (data) => data,
  );

  Future<void> update(String id, Map<String, dynamic> payload) async {
    await api.request<Object?>(
      (dio) => dio.put('$_basePath/${_segment(id)}', data: payload),
      (_) => null,
    );
  }

  Future<void> setProfile(String id, String profileId) async {
    await api.request<Object?>(
      (dio) => dio.put(
        '$_basePath/${_segment(id)}/profile',
        data: {'profileId': profileId},
      ),
      (_) => null,
    );
  }

  Future<void> command(String id, String command, int value) async {
    await commandWithResult(id, command, value);
  }

  Future<Object?> commandWithResult(String id, String command, int value) =>
      api.request(
        (dio) => dio.post(
          '$_basePath/${_segment(id)}/commands',
          data: {'command': command, 'value': value},
        ),
        (data) => data,
      );

  Future<void> unbind(String id) async {
    await api.request<Object?>(
      (dio) => dio.delete('$_basePath/${_segment(id)}'),
      (_) => null,
    );
  }

  static List<Map<String, dynamic>> _mapList(Object? data) {
    if (data == null) return <Map<String, dynamic>>[];
    if (data is! Iterable) throw const FormatException('设备列表响应格式无效');
    return data
        .map((item) {
          if (item is! Map) throw const FormatException('设备列表项目格式无效');
          return Map<String, dynamic>.from(item);
        })
        .toList(growable: false);
  }

  static Map<String, dynamic> _map(Object? data) {
    if (data is! Map) throw const FormatException('设备响应格式无效');
    return Map<String, dynamic>.from(data);
  }

  static String _segment(String value) => Uri.encodeComponent(value);
}
