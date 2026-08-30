import '../../../core/network/api_client.dart';

class ProfileRepository {
  const ProfileRepository(this.api);
  final ApiClient api;

  Future<List<Map<String, dynamic>>> listProfiles() => api.request(
    (dio) => dio.get('/companion/profiles'),
    (data) => (data as List)
        .map((item) => Map<String, dynamic>.from(item as Map))
        .toList(),
  );
  Future<Map<String, dynamic>> getProfile(String id) => api.request(
    (dio) => dio.get('/companion/profiles/$id'),
    (data) => Map<String, dynamic>.from(data as Map),
  );
  Future<Map<String, dynamic>> createFromTemplate(
    String templateId,
    String name,
  ) => api.request(
    (dio) => dio.post(
      '/companion/profiles',
      data: {'source': 'template', 'templateId': templateId, 'name': name},
    ),
    (data) => Map<String, dynamic>.from(data as Map),
  );
  Future<Map<String, dynamic>> createCustom(String name) => api.request(
    (dio) => dio.post(
      '/companion/profiles',
      data: {'source': 'custom', 'name': name},
    ),
    (data) => Map<String, dynamic>.from(data as Map),
  );
  Future<void> saveAndActivate(String id, Map<String, dynamic> payload) =>
      api.request(
        (dio) => dio.put('/companion/profiles/$id', data: payload),
        (_) {},
      );
  Future<void> setMemoryEnabled(String id, bool enabled) => api.request(
    (dio) => dio.put(
      '/companion/profiles/$id/memory-settings',
      data: {'enabled': enabled},
    ),
    (_) {},
  );
  Future<void> deleteProfile(String id) =>
      api.request((dio) => dio.delete('/companion/profiles/$id'), (_) {});
}
