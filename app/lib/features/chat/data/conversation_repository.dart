import '../../../core/network/api_client.dart';

abstract interface class ConversationGateway {
  Future<List<Map<String, dynamic>>> list();
  Future<Map<String, dynamic>> create(String profileId);
  Future<List<Map<String, dynamic>>> history(String id);
  Future<Map<String, dynamic>> continueConversation(String id);
  Future<void> rename(String id, String title);
  Future<void> delete(String id);
}

class ConversationRepository implements ConversationGateway {
  const ConversationRepository(this.api);
  final ApiClient api;
  @override
  Future<List<Map<String, dynamic>>> list() =>
      api.request((dio) => dio.get('/api/v1/conversations'), _list);
  @override
  Future<Map<String, dynamic>> create(String profileId) => api.request(
    (dio) => dio.post(
      '/api/v1/conversations',
      data: {
        'agentId': profileId,
        'profileId': profileId,
        'inputModes': ['text', 'audio'],
        'outputModes': ['text', 'audio'],
      },
    ),
    (data) => Map<String, dynamic>.from(data as Map),
  );
  @override
  Future<List<Map<String, dynamic>>> history(String id) =>
      api.request((dio) => dio.get('/api/v1/conversations/$id/history'), _list);
  @override
  Future<Map<String, dynamic>> continueConversation(String id) => api.request(
    (dio) => dio.post('/api/v1/conversations/$id/runtime'),
    (data) => Map<String, dynamic>.from(data as Map),
  );
  @override
  Future<void> rename(String id, String title) => api.request(
    (dio) => dio.patch('/api/v1/conversations/$id', data: {'title': title}),
    (_) {},
  );
  @override
  Future<void> delete(String id) =>
      api.request((dio) => dio.delete('/api/v1/conversations/$id'), (_) {});

  static List<Map<String, dynamic>> _list(Object? data) {
    if (data == null) return <Map<String, dynamic>>[];
    final raw = data is Map && data['items'] is Iterable ? data['items'] : data;
    if (raw is! Iterable) {
      throw const FormatException('会话列表响应格式无效');
    }
    return raw
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item))
        .toList(growable: false);
  }
}
