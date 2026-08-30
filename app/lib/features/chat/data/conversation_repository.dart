import '../../../core/network/api_client.dart';

class ConversationRepository {
  const ConversationRepository(this.api);
  final ApiClient api;
  Future<List<Map<String, dynamic>>> list() => api.request(
    (dio) => dio.get('/api/v1/conversations'),
    (data) => (data as List)
        .map((item) => Map<String, dynamic>.from(item as Map))
        .toList(),
  );
  Future<Map<String, dynamic>> create(String profileId) => api.request(
    (dio) => dio.post(
      '/api/v1/conversations',
      data: {'agentId': profileId, 'profileId': profileId},
    ),
    (data) => Map<String, dynamic>.from(data as Map),
  );
  Future<Map<String, dynamic>> history(String id) => api.request(
    (dio) => dio.get('/api/v1/conversations/$id/history'),
    (data) => Map<String, dynamic>.from(data as Map),
  );
  Future<Map<String, dynamic>> continueConversation(String id) => api.request(
    (dio) => dio.post('/api/v1/conversations/$id/runtime'),
    (data) => Map<String, dynamic>.from(data as Map),
  );
  Future<void> rename(String id, String title) => api.request(
    (dio) => dio.patch('/api/v1/conversations/$id', data: {'title': title}),
    (_) {},
  );
  Future<void> delete(String id) =>
      api.request((dio) => dio.delete('/api/v1/conversations/$id'), (_) {});
}
