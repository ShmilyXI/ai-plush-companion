import 'dart:async';

import 'package:ai_plush_companion/core/providers/companion_store.dart';
import 'package:ai_plush_companion/core/network/api_client.dart';
import 'package:ai_plush_companion/core/storage/preferences_store.dart';
import 'package:ai_plush_companion/core/storage/secure_store.dart';
import 'package:ai_plush_companion/features/chat/data/conversation_repository.dart';
import 'package:ai_plush_companion/features/chat/domain/chat_models.dart';
import 'package:ai_plush_companion/features/devices/data/device_repository.dart';
import 'package:ai_plush_companion/features/profiles/data/profile_repository.dart';
import 'package:ai_plush_companion/features/profiles/domain/profile_models.dart';
import 'package:flutter_test/flutter_test.dart';

ApiClient _testApiClient() => ApiClient(
  baseUrl: Uri.parse('https://test.invalid'),
  secureStore: SecureStore(),
);

class _BlockingProfileRepository extends ProfileRepository {
  _BlockingProfileRepository() : super(_testApiClient());

  final response = Completer<List<Map<String, dynamic>>>();

  @override
  Future<List<Map<String, dynamic>>> listProfiles() => response.future;
}

class _EmptyDeviceRepository extends DeviceRepository {
  _EmptyDeviceRepository() : super(_testApiClient());

  @override
  Future<List<Map<String, dynamic>>> list() async => const [];
}

class _EmptyConversationRepository extends ConversationRepository {
  _EmptyConversationRepository() : super(_testApiClient());

  @override
  Future<List<Map<String, dynamic>>> list() async => const [];
}

class _FixedPreferencesStore extends PreferencesStore {
  @override
  Future<bool> readAutoPlay() async => true;
}

void main() {
  test('switching role starts or resumes a role-scoped conversation', () {
    final store = CompanionStore(demo: true);
    store.selectProfile('profile-momo');
    expect(store.selectedProfileId, 'profile-momo');
    expect(store.currentConversation.profileId, 'profile-momo');
    expect(store.currentConversation.title, '新对话');
  });

  test('memory toggle is a durable profile setting in the store', () {
    final store = CompanionStore(demo: true);
    store.toggleMemory('profile-luna', false);
    expect(store.selectedProfile.memoryEnabled, isFalse);
    store.toggleMemory('profile-luna', true);
    expect(store.selectedProfile.memoryEnabled, isTrue);
  });

  test(
    'chat state remains readable when a new account has no conversations',
    () {
      final store = CompanionStore(demo: true);
      store.conversations = <CompanionConversation>[];

      expect(store.currentConversation.id, 'conversation-empty');
      expect(store.currentConversation.messages, isEmpty);
    },
  );

  test('profile updates also insert a newly created remote profile', () {
    final store = CompanionStore(demo: true);
    const profile = CompanionProfile(
      id: 'profile-new',
      name: '新角色',
      summary: '新的陪伴角色',
      personality: '温柔',
      systemPrompt: '保持真诚',
      voice: '默认音色',
      capabilities: {},
      memoryEnabled: true,
      source: ProfileSource.custom,
    );

    store.updateProfile(profile);

    expect(store.profiles.any((item) => item.id == 'profile-new'), isTrue);
  });

  test('removing a profile hides it without changing saved conversations', () {
    final store = CompanionStore(demo: true);
    final conversationId = store.currentConversationId;

    store.removeProfile('profile-luna');

    expect(store.profiles.any((item) => item.id == 'profile-luna'), isFalse);
    expect(
      store.conversations.any((item) => item.id == conversationId),
      isTrue,
    );
    expect(store.selectedProfileId, 'profile-momo');
    expect(store.currentConversation.profileId, 'profile-momo');
  });

  test(
    'allows deleting the last conversation and recreates it on send',
    () async {
      final store = CompanionStore(demo: true);
      store.conversations = <CompanionConversation>[store.currentConversation];
      final id = store.currentConversationId;

      store.deleteConversation(id);

      expect(store.conversations, isEmpty);
      expect(store.currentConversation.id, 'conversation-empty');
      await store.sendMessage('重新开始');
      expect(store.conversations, isNotEmpty);
      expect(store.currentConversation.messages.first.text, '重新开始');
    },
  );

  test(
    'signing out clears account-scoped profiles, devices and conversations',
    () {
      final store = CompanionStore(demo: true);

      store.signOut();

      expect(store.signedIn, isFalse);
      expect(store.profiles, isEmpty);
      expect(store.devices, isEmpty);
      expect(store.conversations, isEmpty);
      expect(store.currentConversation.id, 'conversation-empty');
    },
  );

  test('only an explicitly demo store contains demo fixtures', () {
    final productionStore = CompanionStore();
    final demoStore = CompanionStore(demo: true);

    expect(productionStore.profiles, isEmpty);
    expect(productionStore.devices, isEmpty);
    expect(productionStore.conversations, isEmpty);
    expect(demoStore.profiles, isNotEmpty);
    expect(demoStore.devices, isNotEmpty);
    expect(demoStore.conversations, isNotEmpty);
  });

  test('demo stores do not bootstrap or replace local fixtures', () async {
    final profiles = _BlockingProfileRepository();
    final store = CompanionStore(demo: true);
    final originalProfileIds = store.profiles.map((profile) => profile.id);

    await store.bootstrap(
      profileRepository: profiles,
      deviceRepository: _EmptyDeviceRepository(),
      conversationRepository: _EmptyConversationRepository(),
      preferences: _FixedPreferencesStore(),
    );

    expect(profiles.response.isCompleted, isFalse);
    expect(store.profiles.map((profile) => profile.id), originalProfileIds);
  });

  test(
    'ignores a bootstrap response from an older account generation',
    () async {
      final profiles = _BlockingProfileRepository();
      final store = CompanionStore();
      store.signIn();
      final bootstrap = store.bootstrap(
        profileRepository: profiles,
        deviceRepository: _EmptyDeviceRepository(),
        conversationRepository: _EmptyConversationRepository(),
        preferences: _FixedPreferencesStore(),
      );

      await Future<void>.delayed(Duration.zero);
      store.signOut();
      store.signIn();
      profiles.response.complete([
        {'id': 'old-account-profile', 'name': '旧账号角色'},
      ]);
      await bootstrap;

      expect(store.signedIn, isTrue);
      expect(store.profiles, isEmpty);
      expect(store.devices, isEmpty);
      expect(store.conversations, isEmpty);
    },
  );
}
