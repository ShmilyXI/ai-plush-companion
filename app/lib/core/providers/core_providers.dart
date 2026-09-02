import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'companion_store.dart';
import 'session_runtime_coordinator.dart';
import '../config/app_config.dart';
import '../network/api_client.dart';
import '../storage/secure_store.dart';
import '../storage/preferences_store.dart';
import '../../features/auth/data/auth_repository.dart';
import '../../features/account/data/account_repository.dart';
import '../../features/profiles/data/profile_repository.dart';
import '../../features/devices/data/device_repository.dart';
import '../../features/chat/data/conversation_repository.dart';
import '../../features/chat/application/chat_controller.dart';
import '../../features/call/application/call_controller.dart';
import '../../features/audio/data/companion_audio_service.dart';

final appConfigProvider = Provider<AppConfig>(
  (ref) => AppConfig.fromEnvironment(),
);
final secureStoreProvider = Provider<SecureStore>((ref) => SecureStore());
final preferencesStoreProvider = Provider<PreferencesStore>(
  (ref) => PreferencesStore(),
);
final sessionRuntimeCoordinatorProvider = Provider<SessionRuntimeCoordinator>(
  (ref) => SessionRuntimeCoordinator(),
);
final apiClientProvider = Provider<ApiClient>(
  (ref) => ApiClient(
    baseUrl: ref.watch(appConfigProvider).apiBaseUrl,
    secureStore: ref.watch(secureStoreProvider),
    onAuthExpired: () async {
      // Close account-scoped transports before clearing the store so late
      // realtime events cannot repopulate state after token expiry.
      await ref.read(sessionRuntimeCoordinatorProvider).expire();
      ref.read(companionStoreProvider).signOut();
    },
  ),
);
final authRepositoryProvider = Provider<AuthRepository>(
  (ref) => AuthRepository(
    ref.watch(apiClientProvider),
    ref.watch(secureStoreProvider),
  ),
);
final accountRepositoryProvider = Provider<AccountRepository>(
  (ref) => AccountRepository(ref.watch(apiClientProvider)),
);
final profileRepositoryProvider = Provider<ProfileRepository>(
  (ref) => ProfileRepository(ref.watch(apiClientProvider)),
);
final profileMemoryRepositoryProvider = Provider<ProfileMemoryRepository>(
  (ref) => ProfileMemoryRepository(ref.watch(apiClientProvider)),
);
final deviceRepositoryProvider = Provider<DeviceRepository>(
  (ref) => DeviceRepository(ref.watch(apiClientProvider)),
);
final conversationRepositoryProvider = Provider<ConversationRepository>(
  (ref) => ConversationRepository(ref.watch(apiClientProvider)),
);

final companionStoreProvider = ChangeNotifierProvider<CompanionStore>((ref) {
  return CompanionStore(demo: ref.watch(appConfigProvider).isDemo);
});

final chatControllerProvider = ChangeNotifierProvider<ChatController>((ref) {
  final controller = ChatController(
    conversations: ref.watch(conversationRepositoryProvider),
    store: ref.watch(companionStoreProvider),
    demo: ref.watch(appConfigProvider).isDemo,
    runtimeWsOrigin: ref.watch(appConfigProvider).runtimeWsOrigin,
    playback: CompanionAudioService.shared.playbackOrCreate,
  );
  final coordinator = ref.read(sessionRuntimeCoordinatorProvider);
  final close = controller.close;
  coordinator.closeChat = close;
  ref.onDispose(() {
    if (coordinator.closeChat == close) coordinator.closeChat = null;
  });
  return controller;
});

final callControllerProvider = ChangeNotifierProvider<CallController>((ref) {
  final controller = CallController(
    runtimeWsOrigin: ref.watch(appConfigProvider).runtimeWsOrigin,
    playback: CompanionAudioService.shared.playbackOrCreate,
    configureAudioSession: CompanionAudioService.shared.configureForCall,
    deactivateAudioSession: CompanionAudioService.shared.deactivateCall,
    audioInterruptions: CompanionAudioService.shared.interruptionEvents,
    stopRequests: CompanionAudioService.shared.stopRequests,
  );
  final coordinator = ref.read(sessionRuntimeCoordinatorProvider);
  final end = controller.end;
  coordinator.endCall = end;
  ref.onDispose(() {
    if (coordinator.endCall == end) coordinator.endCall = null;
  });
  return controller;
});
