enum ProfileSource { preset, custom }

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
    );
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
  });

  final String id;
  final String alias;
  final String board;
  final String macAddress;
  final String profileId;
  final bool online;
  final int volume;
  final int brightness;

  CompanionDevice copyWith({
    String? alias,
    String? profileId,
    bool? online,
    int? volume,
    int? brightness,
  }) => CompanionDevice(
    id: id,
    alias: alias ?? this.alias,
    board: board,
    macAddress: macAddress,
    profileId: profileId ?? this.profileId,
    online: online ?? this.online,
    volume: volume ?? this.volume,
    brightness: brightness ?? this.brightness,
  );
}
