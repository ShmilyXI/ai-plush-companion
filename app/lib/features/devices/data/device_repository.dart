import '../../../core/network/api_client.dart';

class DeviceRepository {
  const DeviceRepository(this.api);
  final ApiClient api;
  Future<List<Map<String, dynamic>>> list() => api.request(
    (dio) => dio.get('/companion/devices'),
    (data) => (data as List)
        .map((item) => Map<String, dynamic>.from(item as Map))
        .toList(),
  );
  Future<Map<String, dynamic>> bind({
    required String activationCode,
    String? profileId,
  }) => api.request(
    (dio) => dio.post(
      '/companion/devices/bind',
      data: {
        'activationCode': activationCode,
        if (profileId != null) 'profileId': profileId,
      },
    ),
    (data) => Map<String, dynamic>.from(data as Map),
  );
  Future<void> update(String id, Map<String, dynamic> payload) => api.request(
    (dio) => dio.put('/companion/devices/$id', data: payload),
    (_) {},
  );
  Future<void> setProfile(String id, String profileId) => api.request(
    (dio) => dio.put(
      '/companion/devices/$id/profile',
      data: {'profileId': profileId},
    ),
    (_) {},
  );
  Future<void> command(String id, String command, int value) => api.request(
    (dio) => dio.post(
      '/companion/devices/$id/commands',
      data: {'command': command, 'value': value},
    ),
    (_) {},
  );
  Future<void> unbind(String id) =>
      api.request((dio) => dio.delete('/companion/devices/$id'), (_) {});
}
