import 'package:ai_plush_companion/core/providers/companion_store.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('switching role starts or resumes a role-scoped conversation', () {
    final store = CompanionStore();
    store.selectProfile('profile-momo');
    expect(store.selectedProfileId, 'profile-momo');
    expect(store.currentConversation.profileId, 'profile-momo');
  });

  test('memory toggle is a durable profile setting in the store', () {
    final store = CompanionStore();
    store.toggleMemory('profile-luna', false);
    expect(store.selectedProfile.memoryEnabled, isFalse);
    store.toggleMemory('profile-luna', true);
    expect(store.selectedProfile.memoryEnabled, isTrue);
  });
}
