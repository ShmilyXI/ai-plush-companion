import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'companion_store.dart';
import '../config/app_config.dart';
import '../network/api_client.dart';
import '../storage/secure_store.dart';
import '../storage/preferences_store.dart';
import '../../features/auth/data/auth_repository.dart';
import '../../features/profiles/data/profile_repository.dart';
import '../../features/devices/data/device_repository.dart';
import '../../features/chat/data/conversation_repository.dart';

final appConfigProvider = Provider<AppConfig>(
  (ref) => AppConfig.fromEnvironment(),
);
final secureStoreProvider = Provider<SecureStore>((ref) => SecureStore());
final preferencesStoreProvider = Provider<PreferencesStore>(
  (ref) => PreferencesStore(),
);
final apiClientProvider = Provider<ApiClient>(
  (ref) => ApiClient(
    baseUrl: ref.watch(appConfigProvider).apiBaseUrl,
    secureStore: ref.watch(secureStoreProvider),
  ),
);
final authRepositoryProvider = Provider<AuthRepository>(
  (ref) => AuthRepository(
    ref.watch(apiClientProvider),
    ref.watch(secureStoreProvider),
  ),
);
final profileRepositoryProvider = Provider<ProfileRepository>(
  (ref) => ProfileRepository(ref.watch(apiClientProvider)),
);
final deviceRepositoryProvider = Provider<DeviceRepository>(
  (ref) => DeviceRepository(ref.watch(apiClientProvider)),
);
final conversationRepositoryProvider = Provider<ConversationRepository>(
  (ref) => ConversationRepository(ref.watch(apiClientProvider)),
);

final companionStoreProvider = ChangeNotifierProvider<CompanionStore>((ref) {
  return CompanionStore();
});
