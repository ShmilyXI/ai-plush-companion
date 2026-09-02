import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../features/chat/domain/chat_models.dart';
import '../../features/chat/data/conversation_repository.dart';
import '../../features/devices/data/device_repository.dart';
import '../../features/profiles/data/profile_repository.dart';
import '../../features/profiles/domain/profile_models.dart';
import '../storage/preferences_store.dart';

class CompanionStore extends ChangeNotifier {
  final bool demo;
  late final List<CompanionProfile> _demoProfiles;
  late final List<CompanionDevice> _demoDevices;
  late final List<CompanionConversation> _demoConversations;

  CompanionStore({this.demo = false})
    : profiles = <CompanionProfile>[
        const CompanionProfile(
          id: 'profile-luna',
          name: '露娜',
          summary: '温柔、敏锐，记得你在意的小事',
          personality: '温柔、耐心、带一点俏皮',
          systemPrompt: '你是露娜，用户的长期陪伴者。先理解情绪，再给出简短、有温度的回应。',
          voice: '温柔女声',
          capabilities: {'情绪感知', '天气'},
          memoryEnabled: true,
          source: ProfileSource.preset,
          activeVersionNo: 3,
        ),
        const CompanionProfile(
          id: 'profile-momo',
          name: '墨墨',
          summary: '安静的思考搭子，适合一起专注',
          personality: '冷静、清晰、偶尔幽默',
          systemPrompt: '你是墨墨，擅长帮助用户梳理想法和完成计划。',
          voice: '清澈中性',
          capabilities: {'联网搜索'},
          memoryEnabled: false,
          source: ProfileSource.preset,
          activeVersionNo: 1,
        ),
      ],
      devices = <CompanionDevice>[
        const CompanionDevice(
          id: 'device-aurora',
          alias: '床头小熊',
          board: 'Plush S3',
          macAddress: '••:••:7A:21',
          profileId: 'profile-luna',
          online: true,
          volume: 64,
          brightness: 72,
        ),
      ],
      conversations = <CompanionConversation>[
        CompanionConversation(
          id: 'conversation-today',
          profileId: 'profile-luna',
          title: '今天的心情',
          updatedAt: DateTime.now().subtract(const Duration(minutes: 8)),
          messages: [
            ChatMessage(
              id: 'm-1',
              text: '今天有点忙，但终于把最难的事情做完了。',
              author: MessageAuthor.user,
              createdAt: DateTime.now().subtract(const Duration(minutes: 10)),
            ),
            ChatMessage(
              id: 'm-2',
              text: '听起来你扛过了一个不轻松的上午。先给自己留一点喘气的时间，晚点想聊聊那件最难的事吗？',
              author: MessageAuthor.assistant,
              createdAt: DateTime.now().subtract(const Duration(minutes: 8)),
              hasAudio: true,
            ),
          ],
        ),
        CompanionConversation(
          id: 'conversation-weekend',
          profileId: 'profile-momo',
          title: '周末计划',
          updatedAt: DateTime.now().subtract(const Duration(days: 1, hours: 2)),
          messages: [
            ChatMessage(
              id: 'm-3',
              text: '帮我把周末安排得松弛一点。',
              author: MessageAuthor.user,
              createdAt: DateTime.now().subtract(
                const Duration(days: 1, hours: 2),
              ),
            ),
            ChatMessage(
              id: 'm-4',
              text: '周六留一段不安排的上午，下午去散步；周日只放一个必须完成的小目标。',
              author: MessageAuthor.assistant,
              createdAt: DateTime.now().subtract(
                const Duration(days: 1, hours: 1, minutes: 58),
              ),
              hasAudio: true,
            ),
          ],
        ),
      ] {
    _demoProfiles = demo
        ? List<CompanionProfile>.of(profiles)
        : const <CompanionProfile>[];
    _demoDevices = demo
        ? List<CompanionDevice>.of(devices)
        : const <CompanionDevice>[];
    _demoConversations = demo
        ? List<CompanionConversation>.of(conversations)
        : const <CompanionConversation>[];
    if (demo) {
      // Demo mode is a local, already-authenticated session. Keep the seed
      // data available without requiring the router to trigger a notification.
      signedIn = true;
      _accountGeneration = 1;
    } else {
      _clearAccountState();
    }
  }

  List<CompanionProfile> profiles;
  List<CompanionDevice> devices;
  List<CompanionConversation> conversations;
  String selectedProfileId = 'profile-luna';
  String currentConversationId = 'conversation-today';
  bool autoPlay = true;
  bool signedIn = false;
  bool isSending = false;
  String? errorMessage;
  int _accountGeneration = 0;

  int get accountGeneration => _accountGeneration;

  void _clearAccountState() {
    profiles = <CompanionProfile>[];
    devices = <CompanionDevice>[];
    conversations = <CompanionConversation>[];
    selectedProfileId = '';
    currentConversationId = '';
    isSending = false;
    errorMessage = null;
  }

  void _restoreDemoState() {
    profiles = List<CompanionProfile>.of(_demoProfiles);
    devices = List<CompanionDevice>.of(_demoDevices);
    conversations = List<CompanionConversation>.of(_demoConversations);
    selectedProfileId = profiles.isEmpty ? '' : profiles.first.id;
    currentConversationId = conversations.isEmpty ? '' : conversations.first.id;
    isSending = false;
    errorMessage = null;
  }

  bool _isCurrentAccount(int generation) =>
      signedIn && generation == _accountGeneration;

  Future<void> bootstrap({
    required ProfileRepository profileRepository,
    required DeviceRepository deviceRepository,
    required ConversationRepository conversationRepository,
    required PreferencesStore preferences,
  }) async {
    final generation = _accountGeneration;
    if (demo || !_isCurrentAccount(generation)) return;
    try {
      final nextAutoPlay = await preferences.readAutoPlay();
      if (!_isCurrentAccount(generation)) return;
      autoPlay = nextAutoPlay;
      final remoteProfiles = await profileRepository.listProfiles();
      if (!_isCurrentAccount(generation)) return;
      final parsedProfiles = remoteProfiles
          .map(_profileFromMap)
          .whereType<CompanionProfile>()
          .toList();
      // The response is authoritative for the current account. An all-invalid
      // payload must clear the old snapshot instead of silently retaining demo
      // data or a previous account's profiles.
      profiles = parsedProfiles;
      if (profiles.isNotEmpty &&
          profiles.every((item) => item.id != selectedProfileId)) {
        selectedProfileId = profiles.first.id;
      }
      final remoteDevices = await deviceRepository.list();
      if (!_isCurrentAccount(generation)) return;
      devices = remoteDevices
          .map(_deviceFromMap)
          .whereType<CompanionDevice>()
          .toList();
      final remoteConversations = await conversationRepository.list();
      if (!_isCurrentAccount(generation)) return;
      final parsedConversations = remoteConversations
          .map(_conversationFromMap)
          .whereType<CompanionConversation>()
          .toList();
      conversations = parsedConversations;
      if (conversations.isNotEmpty) {
        currentConversationId = conversations.first.id;
        selectedProfileId = conversations.first.profileId;
      } else {
        currentConversationId = '';
      }
      notifyListeners();
    } catch (_) {
      if (_isCurrentAccount(generation) && !demo) {
        _clearAccountState();
        errorMessage = '暂时无法加载账号数据，请稍后重试';
        notifyListeners();
      }
      // Demo mode is intentionally local and does not bootstrap remotely.
    }
  }

  CompanionProfile? _profileFromMap(Map<String, dynamic> map) {
    try {
      return CompanionProfile.fromMap(map);
    } on FormatException {
      return null;
    }
  }

  CompanionDevice? _deviceFromMap(Map<String, dynamic> map) {
    try {
      final device = CompanionDevice.fromMap(map);
      return device.profileId.isEmpty
          ? device.copyWith(profileId: selectedProfileId)
          : device;
    } on FormatException {
      return null;
    }
  }

  CompanionConversation? _conversationFromMap(Map<String, dynamic> map) {
    final id = map['id']?.toString() ?? map['conversationId']?.toString();
    final profileId =
        map['profileId']?.toString() ?? map['agentId']?.toString();
    if (id == null || profileId == null) return null;
    return CompanionConversation(
      id: id,
      profileId: profileId,
      title: map['title']?.toString() ?? '新对话',
      updatedAt:
          DateTime.tryParse(map['lastActivityAt']?.toString() ?? '') ??
          DateTime.now(),
      messages: const [],
      source: map['source']?.toString() ?? 'app',
    );
  }

  CompanionProfile get selectedProfile => profiles.isEmpty
      ? const CompanionProfile(
          id: 'profile-empty',
          name: '陪伴角色',
          summary: '创建一个属于你的角色',
          personality: '',
          systemPrompt: '',
          voice: '默认音色',
          capabilities: {},
          memoryEnabled: true,
          source: ProfileSource.custom,
        )
      : profiles.firstWhere(
          (profile) => profile.id == selectedProfileId,
          orElse: () => profiles.first,
        );

  CompanionConversation get currentConversation {
    if (conversations.isEmpty) {
      return CompanionConversation(
        id: 'conversation-empty',
        profileId: selectedProfileId,
        title: '新对话',
        updatedAt: DateTime.now(),
        messages: const [],
      );
    }
    return conversations.firstWhere(
      (conversation) => conversation.id == currentConversationId,
      orElse: () => conversations.first,
    );
  }

  List<CompanionConversation> get selectedProfileConversations =>
      conversations
          .where((conversation) => conversation.profileId == selectedProfileId)
          .toList()
        ..sort((a, b) => b.updatedAt.compareTo(a.updatedAt));

  List<CompanionConversation> get allConversations =>
      conversations.toList()
        ..sort((a, b) => b.updatedAt.compareTo(a.updatedAt));

  void selectProfile(String profileId) {
    if (profiles.every((profile) => profile.id != profileId)) return;
    if (selectedProfileId == profileId) {
      notifyListeners();
      return;
    }
    selectedProfileId = profileId;
    _createConversation('新对话');
    notifyListeners();
  }

  void selectConversation(String conversationId) {
    final conversation = conversations
        .cast<CompanionConversation?>()
        .firstWhere((item) => item?.id == conversationId, orElse: () => null);
    if (conversation == null) return;
    currentConversationId = conversation.id;
    selectedProfileId = conversation.profileId;
    notifyListeners();
  }

  void newConversation() {
    _createConversation('新对话');
    notifyListeners();
  }

  void _createConversation(String title) {
    final id = 'conversation-${DateTime.now().microsecondsSinceEpoch}';
    conversations = [
      CompanionConversation(
        id: id,
        profileId: selectedProfileId,
        title: title,
        updatedAt: DateTime.now(),
        messages: const [],
      ),
      ...conversations,
    ];
    currentConversationId = id;
  }

  Future<void> sendMessage(String text) async {
    final trimmed = text.trim();
    if (trimmed.isEmpty || isSending) return;
    isSending = true;
    errorMessage = null;
    final now = DateTime.now();
    final userMessage = ChatMessage(
      id: 'message-${now.microsecondsSinceEpoch}',
      text: trimmed,
      author: MessageAuthor.user,
      createdAt: now,
    );
    _appendMessage(userMessage);
    notifyListeners();
    await Future<void>.delayed(const Duration(milliseconds: 520));
    final profile = selectedProfile;
    final reply = _replyFor(trimmed, profile);
    _appendMessage(
      ChatMessage(
        id: 'message-${DateTime.now().microsecondsSinceEpoch}',
        text: reply,
        author: MessageAuthor.assistant,
        createdAt: DateTime.now(),
        hasAudio: true,
      ),
    );
    isSending = false;
    notifyListeners();
  }

  void addVoiceMessage() {
    unawaited(sendMessage('我刚刚用语音和你说了一句话。'));
  }

  String _replyFor(String text, CompanionProfile profile) {
    if (text.contains('天气')) return '${profile.name}：我可以帮你查天气。你想看哪个城市？';
    if (text.contains('记得') || text.contains('记忆')) {
      return profile.memoryEnabled
          ? '我会把这件事记在我们的长期记忆里。之后你再提起时，我会接着聊。'
          : '现在没有开启长期记忆。我仍然会在这次对话里记住上下文。';
    }
    return '${profile.name}：收到。你可以慢慢说，我在这里听着。';
  }

  void _appendMessage(ChatMessage message) {
    var index = conversations.indexWhere(
      (conversation) => conversation.id == currentConversationId,
    );
    if (index < 0) {
      _createConversation('新对话');
      index = conversations.indexWhere(
        (conversation) => conversation.id == currentConversationId,
      );
    }
    final conversation = conversations[index];
    final title =
        conversation.title == '新对话' && message.author == MessageAuthor.user
        ? message.text.substring(0, message.text.length.clamp(0, 18))
        : conversation.title;
    final updated = conversation.copyWith(
      title: title,
      updatedAt: message.createdAt,
      messages: [...conversation.messages, message],
    );
    conversations = [...conversations]..[index] = updated;
  }

  void toggleMemory(String profileId, bool enabled) {
    profiles = profiles
        .map(
          (profile) => profile.id == profileId
              ? profile.copyWith(memoryEnabled: enabled)
              : profile,
        )
        .toList();
    notifyListeners();
  }

  void setAutoPlay(bool enabled) {
    autoPlay = enabled;
    notifyListeners();
  }

  void signOut() {
    ++_accountGeneration;
    signedIn = false;
    // These collections are scoped to the authenticated account. Keeping the
    // previous snapshot around would expose it while the next account loads.
    _clearAccountState();
    notifyListeners();
  }

  void signIn() {
    ++_accountGeneration;
    signedIn = true;
    if (demo) {
      _restoreDemoState();
    } else {
      _clearAccountState();
    }
    notifyListeners();
  }

  void updateProfile(CompanionProfile updated) {
    final exists = profiles.any((profile) => profile.id == updated.id);
    profiles = exists
        ? profiles
              .map((profile) => profile.id == updated.id ? updated : profile)
              .toList()
        : [...profiles, updated];
    notifyListeners();
  }

  void removeProfile(String profileId) {
    final current = conversations
        .where((conversation) => conversation.id == currentConversationId)
        .firstOrNull;
    profiles = profiles
        .where((profile) => profile.id != profileId)
        .toList(growable: false);
    if (selectedProfileId == profileId) {
      selectedProfileId = profiles.isEmpty ? '' : profiles.first.id;
      if (current?.profileId == profileId) {
        currentConversationId =
            conversations
                .where(
                  (conversation) => conversation.profileId == selectedProfileId,
                )
                .firstOrNull
                ?.id ??
            '';
      }
    }
    notifyListeners();
  }

  void replaceProfiles(Iterable<CompanionProfile> next) {
    profiles = List<CompanionProfile>.of(next);
    if (profiles.isNotEmpty &&
        profiles.every((profile) => profile.id != selectedProfileId)) {
      selectedProfileId = profiles.first.id;
    }
    notifyListeners();
  }

  void replaceDevices(Iterable<CompanionDevice> next) {
    devices = List<CompanionDevice>.of(next);
    notifyListeners();
  }

  void replaceConversation(CompanionConversation replacement) {
    final index = conversations.indexWhere(
      (conversation) => conversation.id == replacement.id,
    );
    if (index < 0) {
      conversations = [replacement, ...conversations];
    } else {
      conversations = [...conversations]..[index] = replacement;
    }
    currentConversationId = replacement.id;
    selectedProfileId = replacement.profileId;
    notifyListeners();
  }

  void replaceConversationId(String oldId, CompanionConversation replacement) {
    final index = conversations.indexWhere(
      (conversation) => conversation.id == oldId,
    );
    if (index < 0) {
      replaceConversation(replacement);
      return;
    }
    conversations = [...conversations]..[index] = replacement;
    currentConversationId = replacement.id;
    selectedProfileId = replacement.profileId;
    notifyListeners();
  }

  void appendCurrentMessage(ChatMessage message) {
    _appendMessage(message);
    notifyListeners();
  }

  void upsertCurrentMessage({
    required String messageId,
    required String text,
    MessageAuthor author = MessageAuthor.assistant,
    bool? hasAudio,
  }) {
    var conversationIndex = conversations.indexWhere(
      (conversation) => conversation.id == currentConversationId,
    );
    if (conversationIndex < 0) {
      _createConversation('新对话');
      conversationIndex = conversations.indexWhere(
        (conversation) => conversation.id == currentConversationId,
      );
    }
    final conversation = conversations[conversationIndex];
    final messages = [...conversation.messages];
    final messageIndex = messages.indexWhere((item) => item.id == messageId);
    final next = ChatMessage(
      id: messageId,
      text: text,
      author: author,
      createdAt: messageIndex < 0
          ? DateTime.now()
          : messages[messageIndex].createdAt,
      hasAudio:
          hasAudio ??
          (messageIndex < 0 ? false : messages[messageIndex].hasAudio),
      audioPlaying: messageIndex < 0
          ? false
          : messages[messageIndex].audioPlaying,
    );
    if (messageIndex < 0) {
      messages.add(next);
    } else {
      messages[messageIndex] = next;
    }
    conversations = [...conversations]
      ..[conversationIndex] = conversation.copyWith(
        updatedAt: DateTime.now(),
        messages: messages,
      );
    notifyListeners();
  }

  void setSending(bool value) {
    isSending = value;
    notifyListeners();
  }

  void setError(String? message) {
    errorMessage = message;
    notifyListeners();
  }

  void createProfile({
    required String name,
    required String personality,
    required String prompt,
  }) {
    final id = 'profile-${DateTime.now().microsecondsSinceEpoch}';
    profiles = [
      ...profiles,
      CompanionProfile(
        id: id,
        name: name.trim().isEmpty ? '新角色' : name.trim(),
        summary: '由你亲手定义的陪伴角色',
        personality: personality.trim().isEmpty ? '温柔、真诚' : personality.trim(),
        systemPrompt: prompt.trim().isEmpty ? '请以真诚、尊重的方式陪伴用户。' : prompt.trim(),
        voice: '温柔女声',
        capabilities: const {},
        memoryEnabled: true,
        source: ProfileSource.custom,
      ),
    ];
    selectedProfileId = id;
    _createConversation('新对话');
    notifyListeners();
  }

  void renameConversation(String id, String title) {
    final clean = title.trim();
    if (clean.isEmpty) return;
    conversations = conversations
        .map(
          (conversation) => conversation.id == id
              ? conversation.copyWith(title: clean)
              : conversation,
        )
        .toList();
    notifyListeners();
  }

  void deleteConversation(String id) {
    conversations = conversations
        .where((conversation) => conversation.id != id)
        .toList();
    if (currentConversationId == id) {
      currentConversationId = conversations.isEmpty
          ? ''
          : conversations.first.id;
    }
    notifyListeners();
  }

  void toggleAudio(String messageId) {
    final conversationIndex = conversations.indexWhere(
      (conversation) => conversation.id == currentConversationId,
    );
    if (conversationIndex < 0) return;
    final current = conversations[conversationIndex];
    final messages = current.messages
        .map(
          (message) => message.id == messageId
              ? message.copyWith(audioPlaying: !message.audioPlaying)
              : message.copyWith(audioPlaying: false),
        )
        .toList();
    conversations = [...conversations]
      ..[conversationIndex] = current.copyWith(messages: messages);
    notifyListeners();
  }

  void setAudioPlaying(String messageId, bool playing) {
    final conversationIndex = conversations.indexWhere(
      (conversation) => conversation.id == currentConversationId,
    );
    if (conversationIndex < 0) return;
    final current = conversations[conversationIndex];
    final messages = current.messages
        .map(
          (message) => message.copyWith(
            audioPlaying: message.id == messageId ? playing : false,
          ),
        )
        .toList();
    conversations = [...conversations]
      ..[conversationIndex] = current.copyWith(messages: messages);
    notifyListeners();
  }

  void updateDevice(CompanionDevice updated) {
    devices = devices
        .map((device) => device.id == updated.id ? updated : device)
        .toList();
    notifyListeners();
  }

  void removeDevice(String id) {
    devices = devices.where((device) => device.id != id).toList();
    notifyListeners();
  }

  void addDemoDevice() {
    final id = 'device-${DateTime.now().microsecondsSinceEpoch}';
    devices = [
      ...devices,
      CompanionDevice(
        id: id,
        alias: '新设备',
        board: 'Plush S3',
        macAddress: '••:••:4C:8D',
        profileId: selectedProfileId,
        online: false,
      ),
    ];
    notifyListeners();
  }
}
