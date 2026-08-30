import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../features/chat/domain/chat_models.dart';
import '../../features/profiles/domain/profile_models.dart';

class CompanionStore extends ChangeNotifier {
  CompanionStore()
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
      ];

  List<CompanionProfile> profiles;
  List<CompanionDevice> devices;
  List<CompanionConversation> conversations;
  String selectedProfileId = 'profile-luna';
  String currentConversationId = 'conversation-today';
  bool autoPlay = true;
  bool signedIn = true;
  bool isSending = false;
  String? errorMessage;

  CompanionProfile get selectedProfile => profiles.firstWhere(
    (profile) => profile.id == selectedProfileId,
    orElse: () => profiles.first,
  );

  CompanionConversation get currentConversation => conversations.firstWhere(
    (conversation) => conversation.id == currentConversationId,
    orElse: () => conversations.first,
  );

  List<CompanionConversation> get selectedProfileConversations =>
      conversations
          .where((conversation) => conversation.profileId == selectedProfileId)
          .toList()
        ..sort((a, b) => b.updatedAt.compareTo(a.updatedAt));

  void selectProfile(String profileId) {
    if (profiles.every((profile) => profile.id != profileId)) return;
    selectedProfileId = profileId;
    final existing = selectedProfileConversations;
    if (existing.isEmpty) {
      _createConversation('新对话');
    } else {
      currentConversationId = existing.first.id;
    }
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
    final index = conversations.indexWhere(
      (conversation) => conversation.id == currentConversationId,
    );
    if (index < 0) return;
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
    signedIn = false;
    notifyListeners();
  }

  void signIn() {
    signedIn = true;
    notifyListeners();
  }

  void updateProfile(CompanionProfile updated) {
    profiles = profiles
        .map((profile) => profile.id == updated.id ? updated : profile)
        .toList();
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
    if (conversations.length <= 1) return;
    conversations = conversations
        .where((conversation) => conversation.id != id)
        .toList();
    if (currentConversationId == id) {
      currentConversationId = conversations.first.id;
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
