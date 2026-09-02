import 'dart:convert';

enum ProfileSource { preset, custom }

class _ProfileValue {
  const _ProfileValue._();

  static bool asBool(Object? value, {required bool fallback}) {
    if (value is bool) return value;
    if (value is num) return value != 0;
    if (value is String) {
      if (value == '1' || value.toLowerCase() == 'true') return true;
      if (value == '0' || value.toLowerCase() == 'false') return false;
    }
    return fallback;
  }

  static int? asInt(Object? value) {
    if (value is int) return value;
    if (value is num) return value.round();
    return int.tryParse(value?.toString() ?? '');
  }
}

class ProfileModelBinding {
  const ProfileModelBinding({
    required this.modelType,
    required this.source,
    this.resourceId,
    this.name,
    this.overrides = const <String, dynamic>{},
    this.enabled = true,
    this.unavailableReason,
  });

  final String modelType;
  final String source;
  final String? resourceId;
  final String? name;
  final Map<String, dynamic> overrides;
  final bool enabled;
  final String? unavailableReason;

  factory ProfileModelBinding.fromMap(Map<String, dynamic> map) {
    return ProfileModelBinding(
      modelType: map['modelType']?.toString() ?? '',
      source: map['source']?.toString() ?? 'default',
      resourceId: map['resourceId']?.toString(),
      name: map['name']?.toString(),
      overrides: _redactMap(map['overrides']),
      enabled: _ProfileValue.asBool(map['enabled'], fallback: true),
      unavailableReason: map['unavailableReason']?.toString(),
    );
  }

  Map<String, dynamic> toPayload() => <String, dynamic>{
    'modelType': modelType,
    'source': source,
    if (resourceId != null && resourceId!.isNotEmpty) 'resourceId': resourceId,
    if (overrides.isNotEmpty) 'overrides': overrides,
  };

  static Map<String, dynamic> _redactMap(Object? value) {
    if (value is! Map) return const <String, dynamic>{};
    const sensitive = {
      'api_key',
      'apikey',
      'access_key',
      'accesskey',
      'secret',
      'password',
      'token',
      'credential',
      'credentials',
    };
    Object? redact(Object? item) {
      if (item is Map) {
        return <String, dynamic>{
          for (final entry in item.entries)
            if (!sensitive.contains(entry.key.toString().toLowerCase()))
              entry.key.toString(): redact(entry.value),
        };
      }
      if (item is Iterable) return item.map(redact).toList(growable: false);
      return item;
    }

    return Map<String, dynamic>.from(redact(value) as Map);
  }
}

class ProfileSkillBinding {
  const ProfileSkillBinding({
    required this.skillId,
    this.versionMode = 'LATEST',
    this.fixedVersion,
    this.overrideJson,
    this.triggerPriority = 0,
    this.enabled = true,
  });

  final String skillId;
  final String versionMode;
  final int? fixedVersion;
  final String? overrideJson;
  final int triggerPriority;
  final bool enabled;

  factory ProfileSkillBinding.fromMap(Map<String, dynamic> map) {
    return ProfileSkillBinding(
      skillId: map['skillId']?.toString() ?? '',
      versionMode: map['versionMode']?.toString() ?? 'LATEST',
      fixedVersion: _ProfileValue.asInt(map['fixedVersion']),
      overrideJson: _safeOverrideJson(map['overrideJson']),
      triggerPriority: _ProfileValue.asInt(map['triggerPriority']) ?? 0,
      enabled: _ProfileValue.asBool(map['enabled'], fallback: true),
    );
  }

  ProfileSkillBinding copyWith({
    String? skillId,
    String? versionMode,
    int? fixedVersion,
    String? overrideJson,
    int? triggerPriority,
    bool? enabled,
  }) => ProfileSkillBinding(
    skillId: skillId ?? this.skillId,
    versionMode: versionMode ?? this.versionMode,
    fixedVersion: fixedVersion ?? this.fixedVersion,
    overrideJson: overrideJson ?? this.overrideJson,
    triggerPriority: triggerPriority ?? this.triggerPriority,
    enabled: enabled ?? this.enabled,
  );

  Map<String, dynamic> toPayload() => <String, dynamic>{
    'skillId': skillId,
    'versionMode': versionMode,
    if (fixedVersion != null) 'fixedVersion': fixedVersion,
    if (overrideJson != null) 'overrideJson': overrideJson,
    'triggerPriority': triggerPriority,
    'enabled': enabled,
  };

  static String? _safeOverrideJson(Object? raw) {
    if (raw == null) return null;
    if (raw is Map) {
      return jsonEncode(ProfileModelBinding._redactMap(raw));
    }
    final text = raw.toString();
    try {
      final parsed = jsonDecode(text);
      if (parsed is Map) {
        return jsonEncode(ProfileModelBinding._redactMap(parsed));
      }
    } catch (_) {
      // Keep malformed legacy text out of the editable payload.
      return null;
    }
    return text;
  }
}

class CompanionProfile {
  const CompanionProfile({
    required this.id,
    required this.name,
    required this.summary,
    required this.personality,
    required this.systemPrompt,
    required this.voice,
    required this.capabilities,
    required this.memoryEnabled,
    required this.source,
    this.avatarUrl,
    this.activeVersionNo = 1,
    this.boundDeviceCount = 0,
    this.deleted = false,
    this.ttsLanguage = 'zh-CN',
    this.ttsVolume = 1,
    this.ttsRate = 1,
    this.ttsPitch = 1,
    this.chatHistoryTextOnly = true,
    this.relationMode = 'friend',
    this.userAddress,
    this.llmModelId,
    this.llmModelName,
    this.ttsModelId,
    this.ttsModelName,
    this.ttsVoiceId,
    this.models = const <ProfileModelBinding>[],
    this.skills = const <ProfileSkillBinding>[],
    this.companionCueConfig,
    this.screenExpressionEnabled = true,
    this.cameraPreferenceEnabled = true,
  });

  final String id;
  final String name;
  final String summary;
  final String personality;
  final String systemPrompt;
  final String voice;
  final Set<String> capabilities;
  final bool memoryEnabled;
  final ProfileSource source;
  final String? avatarUrl;
  final int activeVersionNo;
  final int boundDeviceCount;
  final bool deleted;
  final String ttsLanguage;
  final double ttsVolume;
  final double ttsRate;
  final double ttsPitch;
  final bool chatHistoryTextOnly;
  final String relationMode;
  final String? userAddress;
  final String? llmModelId;
  final String? llmModelName;
  final String? ttsModelId;
  final String? ttsModelName;
  final String? ttsVoiceId;
  final List<ProfileModelBinding> models;
  final List<ProfileSkillBinding> skills;
  final String? companionCueConfig;
  final bool screenExpressionEnabled;
  final bool cameraPreferenceEnabled;

  CompanionProfile copyWith({
    String? id,
    String? name,
    String? summary,
    String? personality,
    String? systemPrompt,
    String? voice,
    Set<String>? capabilities,
    bool? memoryEnabled,
    ProfileSource? source,
    String? avatarUrl,
    int? activeVersionNo,
    int? boundDeviceCount,
    bool? deleted,
    String? ttsLanguage,
    double? ttsVolume,
    double? ttsRate,
    double? ttsPitch,
    bool? chatHistoryTextOnly,
    String? relationMode,
    String? userAddress,
    String? llmModelId,
    String? llmModelName,
    String? ttsModelId,
    String? ttsModelName,
    String? ttsVoiceId,
    bool clearTtsVoiceId = false,
    List<ProfileModelBinding>? models,
    List<ProfileSkillBinding>? skills,
    String? companionCueConfig,
    bool? screenExpressionEnabled,
    bool? cameraPreferenceEnabled,
  }) {
    return CompanionProfile(
      id: id ?? this.id,
      name: name ?? this.name,
      summary: summary ?? this.summary,
      personality: personality ?? this.personality,
      systemPrompt: systemPrompt ?? this.systemPrompt,
      voice: voice ?? this.voice,
      capabilities: capabilities ?? this.capabilities,
      memoryEnabled: memoryEnabled ?? this.memoryEnabled,
      source: source ?? this.source,
      avatarUrl: avatarUrl ?? this.avatarUrl,
      activeVersionNo: activeVersionNo ?? this.activeVersionNo,
      boundDeviceCount: boundDeviceCount ?? this.boundDeviceCount,
      deleted: deleted ?? this.deleted,
      ttsLanguage: ttsLanguage ?? this.ttsLanguage,
      ttsVolume: ttsVolume ?? this.ttsVolume,
      ttsRate: ttsRate ?? this.ttsRate,
      ttsPitch: ttsPitch ?? this.ttsPitch,
      chatHistoryTextOnly: chatHistoryTextOnly ?? this.chatHistoryTextOnly,
      relationMode: relationMode ?? this.relationMode,
      userAddress: userAddress ?? this.userAddress,
      llmModelId: llmModelId ?? this.llmModelId,
      llmModelName: llmModelName ?? this.llmModelName,
      ttsModelId: ttsModelId ?? this.ttsModelId,
      ttsModelName: ttsModelName ?? this.ttsModelName,
      ttsVoiceId: clearTtsVoiceId ? null : ttsVoiceId ?? this.ttsVoiceId,
      models: models ?? this.models,
      skills: skills ?? this.skills,
      companionCueConfig: companionCueConfig ?? this.companionCueConfig,
      screenExpressionEnabled:
          screenExpressionEnabled ?? this.screenExpressionEnabled,
      cameraPreferenceEnabled:
          cameraPreferenceEnabled ?? this.cameraPreferenceEnabled,
    );
  }

  factory CompanionProfile.fromMap(Map<String, dynamic> map) {
    final id = map['id']?.toString();
    final name = (map['name'] ?? map['agentName'])?.toString();
    if (id == null || id.isEmpty || name == null || name.isEmpty) {
      throw const FormatException('角色响应缺少 id 或 name');
    }
    final modelRows = map['models'];
    final skillRows = map['skills'];
    final capabilityRows = map['capabilities'];
    final capabilities = <String>{};
    if (capabilityRows is Iterable) {
      for (final item in capabilityRows) {
        if (item is Map) {
          final id = item['id']?.toString();
          if (id != null &&
              id.isNotEmpty &&
              _asBool(item['enabled'], fallback: true)) {
            capabilities.add(id);
          }
        } else if (item != null) {
          capabilities.add(item.toString());
        }
      }
    }
    if (skillRows is Iterable) {
      for (final item in skillRows) {
        if (item is Map &&
            item['skillId'] != null &&
            _asBool(item['enabled'], fallback: true)) {
          final skillId = item['skillId'].toString();
          capabilities.add(switch (skillId) {
            'skill-weather' || 'get_weather' => 'weather',
            'skill-web-search' || 'web_search' => 'web_search',
            _ => skillId,
          });
        }
      }
    }
    return CompanionProfile(
      id: id,
      name: name,
      summary:
          map['summary']?.toString() ??
          map['personality']?.toString() ??
          '你的专属陪伴角色',
      personality: map['personality']?.toString() ?? '',
      systemPrompt:
          map['systemPrompt']?.toString() ??
          map['system_prompt']?.toString() ??
          '',
      voice:
          map['ttsVoiceName']?.toString() ??
          map['voiceName']?.toString() ??
          map['ttsVoiceId']?.toString() ??
          map['voiceId']?.toString() ??
          '默认音色',
      capabilities: capabilities,
      memoryEnabled: _asBool(
        map['memoryEnabled'] ??
            (map['memoryPolicy'] is Map
                ? (map['memoryPolicy'] as Map)['enabled']
                : null),
        fallback: true,
      ),
      source: (map['templateId'] ?? map['sourceTemplateId']) == null
          ? ProfileSource.custom
          : ProfileSource.preset,
      avatarUrl: map['avatarUrl']?.toString(),
      activeVersionNo: _asInt(map['activeVersionNo']) ?? 1,
      boundDeviceCount: map['boundDevices'] is Iterable
          ? (map['boundDevices'] as Iterable).length
          : _asInt(map['boundDeviceCount']) ?? 0,
      deleted:
          map['consumerDeletedAt'] != null ||
          _asBool(map['deleted'], fallback: false),
      ttsLanguage: map['ttsLanguage']?.toString() ?? 'zh-CN',
      ttsVolume: _normaliseVolume(map['ttsVolume']),
      ttsRate: _normaliseVoiceParameter(map['ttsRate']),
      ttsPitch: _normaliseVoiceParameter(map['ttsPitch']),
      chatHistoryTextOnly: _asInt(map['chatHistoryConf']) != 2,
      relationMode: map['relationMode']?.toString() ?? 'friend',
      userAddress: map['userAddress']?.toString(),
      llmModelId: map['llmModelId']?.toString(),
      llmModelName: map['llmModelName']?.toString(),
      ttsModelId: map['ttsModelId']?.toString(),
      ttsModelName: map['ttsModelName']?.toString(),
      ttsVoiceId: (map['ttsVoiceId'] ?? map['voiceId'])?.toString(),
      models: modelRows is Iterable
          ? modelRows
                .whereType<Map>()
                .map(
                  (item) => ProfileModelBinding.fromMap(
                    Map<String, dynamic>.from(item),
                  ),
                )
                .toList(growable: false)
          : const <ProfileModelBinding>[],
      skills: skillRows is Iterable
          ? skillRows
                .whereType<Map>()
                .map(
                  (item) => ProfileSkillBinding.fromMap(
                    Map<String, dynamic>.from(item),
                  ),
                )
                .toList(growable: false)
          : const <ProfileSkillBinding>[],
      companionCueConfig: map['companionCueConfig']?.toString(),
      screenExpressionEnabled: _asBool(
        map['screenExpressionEnabled'],
        fallback: true,
      ),
      cameraPreferenceEnabled: _asBool(
        map['cameraPreferenceEnabled'],
        fallback: true,
      ),
    );
  }

  Map<String, dynamic> toSavePayload() => <String, dynamic>{
    'agentName': name,
    'relationMode': relationMode,
    if (userAddress != null) 'userAddress': userAddress,
    'personality': personality,
    'systemPrompt': systemPrompt,
    if (ttsVoiceId != null) 'ttsVoiceId': ttsVoiceId,
    'ttsLanguage': ttsLanguage,
    'ttsVolume': (ttsVolume * 100).round().clamp(0, 100),
    'ttsRate': (ttsRate * 100).round().clamp(25, 200),
    'ttsPitch': (ttsPitch * 100).round().clamp(25, 200),
    'chatHistoryConf': chatHistoryTextOnly ? 1 : 2,
    if (avatarUrl != null) 'avatarUrl': avatarUrl,
    'memoryEnabled': memoryEnabled ? 1 : 0,
    if (companionCueConfig != null) 'companionCueConfig': companionCueConfig,
    'screenExpressionEnabled': screenExpressionEnabled ? 1 : 0,
    'cameraPreferenceEnabled': cameraPreferenceEnabled ? 1 : 0,
    // Empty arrays are intentional. They clear bindings that the user turned
    // off instead of silently leaving the previous active version in place.
    'models': models.map((item) => item.toPayload()).toList(growable: false),
    'skills': skills.map((item) => item.toPayload()).toList(growable: false),
  };

  static bool _asBool(Object? value, {required bool fallback}) {
    if (value is bool) return value;
    if (value is num) return value != 0;
    if (value is String) {
      if (value.toLowerCase() == 'true' || value == '1') return true;
      if (value.toLowerCase() == 'false' || value == '0') return false;
    }
    return fallback;
  }

  static int? _asInt(Object? value) {
    if (value is int) return value;
    if (value is num) return value.round();
    return int.tryParse(value?.toString() ?? '');
  }

  static double _normaliseVolume(Object? value) {
    final parsed = value is num
        ? value.toDouble()
        : double.tryParse(value?.toString() ?? '');
    if (parsed == null) return 1;
    return parsed > 2 ? parsed / 100 : parsed;
  }

  static double _normaliseVoiceParameter(Object? value) {
    final parsed = value is num
        ? value.toDouble()
        : double.tryParse(value?.toString() ?? '');
    if (parsed == null) return 1;
    return parsed > 2 ? parsed / 100 : parsed;
  }
}

class CompanionDevice {
  const CompanionDevice({
    required this.id,
    required this.alias,
    required this.board,
    required this.macAddress,
    required this.profileId,
    required this.online,
    this.volume = 70,
    this.brightness = 80,
    this.lastConnectedAt,
    this.appVersion,
    this.hasDisplay = false,
    this.hasCamera = false,
    this.debugLogEnabled = false,
  });

  final String id;
  final String alias;
  final String board;
  final String macAddress;
  final String profileId;
  final bool online;
  final int volume;
  final int brightness;
  final DateTime? lastConnectedAt;
  final String? appVersion;
  final bool hasDisplay;
  final bool hasCamera;
  final bool debugLogEnabled;

  CompanionDevice copyWith({
    String? alias,
    String? profileId,
    bool? online,
    int? volume,
    int? brightness,
    DateTime? lastConnectedAt,
    String? appVersion,
    bool? hasDisplay,
    bool? hasCamera,
    bool? debugLogEnabled,
  }) => CompanionDevice(
    id: id,
    alias: alias ?? this.alias,
    board: board,
    macAddress: macAddress,
    profileId: profileId ?? this.profileId,
    online: online ?? this.online,
    volume: volume ?? this.volume,
    brightness: brightness ?? this.brightness,
    lastConnectedAt: lastConnectedAt ?? this.lastConnectedAt,
    appVersion: appVersion ?? this.appVersion,
    hasDisplay: hasDisplay ?? this.hasDisplay,
    hasCamera: hasCamera ?? this.hasCamera,
    debugLogEnabled: debugLogEnabled ?? this.debugLogEnabled,
  );

  factory CompanionDevice.fromMap(Map<String, dynamic> map) {
    final id = map['id']?.toString();
    if (id == null || id.isEmpty) {
      throw const FormatException('设备响应缺少 id');
    }
    return CompanionDevice(
      id: id,
      alias: map['alias']?.toString() ?? '未命名设备',
      board: map['board']?.toString() ?? 'Companion',
      macAddress: (map['macAddress'] ?? map['mac_address'])?.toString() ?? '',
      profileId:
          map['activeProfileId']?.toString() ??
          map['active_profile_id']?.toString() ??
          map['profileId']?.toString() ??
          map['profile_id']?.toString() ??
          '',
      online: _asBool(map['online']),
      volume: _asInt(map['volume']) ?? 70,
      brightness: _asInt(map['brightness']) ?? 80,
      lastConnectedAt: DateTime.tryParse(
        (map['lastConnectedAt'] ?? map['last_connected_at'])?.toString() ?? '',
      ),
      appVersion: map['appVersion']?.toString(),
      hasDisplay: _asBool(map['hasDisplay']),
      hasCamera: _asBool(map['hasCamera']),
      debugLogEnabled: _asBool(map['debugLogEnabled']),
    );
  }

  static bool _asBool(Object? value) {
    if (value is bool) return value;
    if (value is num) return value != 0;
    return value?.toString().toLowerCase() == 'true' || value == '1';
  }

  static int? _asInt(Object? value) {
    if (value is int) return value;
    if (value is num) return value.round();
    return int.tryParse(value?.toString() ?? '');
  }
}
