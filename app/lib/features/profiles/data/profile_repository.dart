import 'package:dio/dio.dart';

import '../../../core/network/api_client.dart';

class ProfileRepository {
  const ProfileRepository(this.api);

  final ApiClient api;

  static const _basePath = '/app/profiles';

  Future<List<Map<String, dynamic>>> listProfiles() =>
      api.request((dio) => dio.get(_basePath), _mapList);

  Future<Map<String, dynamic>> getProfile(String id) =>
      api.request((dio) => dio.get('$_basePath/${_segment(id)}'), _map);

  Future<List<Map<String, dynamic>>> listTemplates() =>
      api.request((dio) => dio.get('$_basePath/templates'), _mapList);

  Future<List<Map<String, dynamic>>> listModelOptions(String id) => api.request(
    (dio) => dio.get('$_basePath/${_segment(id)}/model-options'),
    _mapList,
  );

  Future<List<Map<String, dynamic>>> listVoiceOptions(String ttsModelId) {
    final modelId = ttsModelId.trim();
    if (modelId.isEmpty) return Future.value(const []);
    return api.request(
      (dio) => dio.get(
        '/api/v1/voices',
        queryParameters: <String, dynamic>{
          'ttsModelId': modelId,
          'page': '1',
          'limit': '100',
        },
      ),
      _voiceOptionList,
    );
  }

  Future<List<Map<String, dynamic>>> listCapabilityOptions(String id) =>
      api.request(
        (dio) => dio.get('$_basePath/${_segment(id)}/capability-options'),
        _mapList,
      );

  Future<String> createFromTemplate(String templateId, String name) =>
      api.request(
        (dio) => dio.post(
          _basePath,
          data: {'source': 'template', 'templateId': templateId, 'name': name},
        ),
        _id,
      );

  Future<String> createCustom(String name) => api.request(
    (dio) => dio.post(_basePath, data: {'source': 'custom', 'name': name}),
    _id,
  );

  Future<void> saveAndActivate(String id, Map<String, dynamic> payload) async {
    await api.request<Object?>(
      (dio) => dio.put('$_basePath/${_segment(id)}', data: payload),
      (_) => null,
    );
  }

  Future<void> setMemoryEnabled(String id, bool enabled) async {
    await api.request<Object?>(
      (dio) => dio.put(
        '$_basePath/${_segment(id)}/memory-settings',
        data: {'enabled': enabled},
      ),
      (_) => null,
    );
  }

  Future<void> deleteProfile(String id) async {
    await api.request<Object?>(
      (dio) => dio.delete('$_basePath/${_segment(id)}'),
      (_) => null,
    );
  }

  Future<Map<String, dynamic>> saveAvatar(
    String id,
    List<int> bytes,
    String contentType,
  ) => api.request(
    (dio) => dio.post(
      '$_basePath/${_segment(id)}/avatar',
      data: FormData.fromMap({
        'file': MultipartFile.fromBytes(
          bytes,
          filename: 'avatar',
          contentType: DioMediaType.parse(contentType),
        ),
      }),
      options: Options(contentType: 'multipart/form-data'),
    ),
    _map,
  );

  Future<Map<String, dynamic>> generateAvatarUpload(
    String id,
    List<int> bytes,
    String contentType,
  ) => saveAvatar(id, bytes, contentType);

  static List<Map<String, dynamic>> _mapList(Object? data) {
    if (data == null) return <Map<String, dynamic>>[];
    if (data is! Iterable) {
      throw const FormatException('角色列表响应格式无效');
    }
    return data
        .map((item) {
          if (item is! Map) throw const FormatException('角色列表项目格式无效');
          return Map<String, dynamic>.from(item);
        })
        .toList(growable: false);
  }

  static List<Map<String, dynamic>> _voiceOptionList(Object? data) {
    final raw = data is Map
        ? (data['list'] ?? data['items'] ?? data['records'])
        : data;
    if (raw is! Iterable) {
      throw const FormatException('音色列表响应格式无效');
    }
    return raw
        .whereType<Map>()
        .map((item) {
          final map = Map<String, dynamic>.from(item);
          final id = (map['id'] ?? map['voiceId'] ?? map['voice_id'])
              ?.toString()
              .trim();
          if (id == null || id.isEmpty) {
            throw const FormatException('音色列表项目缺少 id');
          }
          return <String, dynamic>{
            'id': id,
            'name':
                (map['name'] ?? map['ttsVoiceName'] ?? map['ttsVoice'] ?? id)
                    .toString(),
            if (map['ttsModelId'] != null) 'ttsModelId': map['ttsModelId'],
          };
        })
        .toList(growable: false);
  }

  static Map<String, dynamic> _map(Object? data) {
    if (data is! Map) throw const FormatException('角色响应格式无效');
    return Map<String, dynamic>.from(data);
  }

  static String _id(Object? data) {
    if (data is String && data.isNotEmpty) return data;
    if (data is num) return data.toString();
    if (data is Map && data['id'] != null) return data['id'].toString();
    throw const FormatException('创建角色响应缺少 id');
  }

  static String _segment(String value) => Uri.encodeComponent(value);
}

class ProfileMemoryView {
  const ProfileMemoryView({required this.enabled, required this.items});

  final bool enabled;
  final List<ProfileMemoryItem> items;
}

class ProfileMemoryItem {
  const ProfileMemoryItem({
    required this.id,
    required this.content,
    required this.updatedAt,
    this.sourceDeviceId,
    this.sourceProfileId,
    this.sourceDeviceName,
    this.sourceProfileName,
  });

  final String id;
  final String content;
  final DateTime? updatedAt;
  final String? sourceDeviceId;
  final String? sourceProfileId;
  final String? sourceDeviceName;
  final String? sourceProfileName;

  factory ProfileMemoryItem.fromMap(Map<String, dynamic> map) {
    final id = map['id']?.toString();
    if (id == null || id.isEmpty) {
      throw const FormatException('记忆响应缺少 id');
    }
    return ProfileMemoryItem(
      id: id,
      content: map['content']?.toString() ?? '',
      updatedAt: DateTime.tryParse(
        (map['updatedAt'] ?? map['updated_at'])?.toString() ?? '',
      ),
      sourceDeviceId: (map['sourceDeviceId'] ?? map['source_device_id'])
          ?.toString(),
      sourceProfileId: (map['sourceProfileId'] ?? map['source_profile_id'])
          ?.toString(),
      sourceDeviceName: (map['sourceDeviceName'] ?? map['source_device_name'])
          ?.toString(),
      sourceProfileName:
          (map['sourceProfileName'] ?? map['source_profile_name'])?.toString(),
    );
  }
}

class ProfileMemoryRepository {
  const ProfileMemoryRepository(this.api);

  final ApiClient api;

  Future<ProfileMemoryView> list(String profileId) => api.request(
    (dio) => dio.get(
      '/companion/profiles/${Uri.encodeComponent(profileId)}/memories',
    ),
    _view,
  );

  Future<void> update(String profileId, String memoryId, String content) async {
    await api.request<Object?>(
      (dio) => dio.put(
        '/companion/profiles/${Uri.encodeComponent(profileId)}/memories/${Uri.encodeComponent(memoryId)}',
        data: {'content': content},
      ),
      (_) => null,
    );
  }

  Future<void> delete(String profileId, String memoryId) async {
    await api.request<Object?>(
      (dio) => dio.delete(
        '/companion/profiles/${Uri.encodeComponent(profileId)}/memories/${Uri.encodeComponent(memoryId)}',
      ),
      (_) => null,
    );
  }

  Future<void> clear(String profileId) async {
    await api.request<Object?>(
      (dio) => dio.delete(
        '/companion/profiles/${Uri.encodeComponent(profileId)}/memories',
      ),
      (_) => null,
    );
  }

  static ProfileMemoryView _view(Object? data) {
    if (data is! Map) throw const FormatException('记忆响应格式无效');
    final map = Map<String, dynamic>.from(data);
    final rows = map['items'];
    if (rows is! List) throw const FormatException('记忆响应缺少 items 列表');
    final items = rows
        .map((item) {
          if (item is! Map) {
            throw const FormatException('记忆列表项目格式无效');
          }
          return ProfileMemoryItem.fromMap(Map<String, dynamic>.from(item));
        })
        .toList(growable: false);
    return ProfileMemoryView(
      enabled: _asBool(map['enabled'] ?? map['memoryEnabled'], fallback: true),
      items: items,
    );
  }

  static bool _asBool(Object? value, {required bool fallback}) {
    if (value is bool) return value;
    if (value is num) return value != 0;
    if (value is String) {
      if (value == '1' || value.toLowerCase() == 'true') return true;
      if (value == '0' || value.toLowerCase() == 'false') return false;
    }
    return fallback;
  }
}
