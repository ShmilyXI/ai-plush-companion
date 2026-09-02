import 'package:flutter/foundation.dart';

import '../data/profile_repository.dart';
import '../domain/profile_models.dart';

class ProfileController extends ChangeNotifier {
  ProfileController({List<CompanionProfile>? initial})
    : profiles = List.of(initial ?? const []);
  List<CompanionProfile> profiles;
  bool saving = false;
  bool loading = false;
  String? error;

  void replace(CompanionProfile profile) {
    final found = profiles.any((item) => item.id == profile.id);
    profiles = found
        ? profiles
              .map((item) => item.id == profile.id ? profile : item)
              .toList()
        : [...profiles, profile];
    notifyListeners();
  }

  void remove(String profileId) {
    profiles = profiles.where((item) => item.id != profileId).toList();
    notifyListeners();
  }

  Future<bool> load(ProfileRepository repository) async {
    loading = true;
    error = null;
    notifyListeners();
    try {
      final rows = await repository.listProfiles();
      profiles = rows
          .map((row) => CompanionProfile.fromMap(row))
          .where((profile) => !profile.deleted)
          .toList(growable: false);
      loading = false;
      notifyListeners();
      return true;
    } catch (value) {
      loading = false;
      error = value.toString();
      notifyListeners();
      return false;
    }
  }

  Future<bool> saveRemote(
    ProfileRepository repository,
    CompanionProfile profile,
  ) => save(
    (_) => repository.saveAndActivate(profile.id, profile.toSavePayload()),
    profile,
  );

  Future<bool> toggleMemoryRemote(
    ProfileRepository repository,
    CompanionProfile profile,
    bool enabled,
  ) async {
    saving = true;
    error = null;
    notifyListeners();
    try {
      await repository.setMemoryEnabled(profile.id, enabled);
      replace(profile.copyWith(memoryEnabled: enabled));
      saving = false;
      notifyListeners();
      return true;
    } catch (value) {
      saving = false;
      error = value.toString();
      notifyListeners();
      return false;
    }
  }

  Future<bool> deleteRemote(
    ProfileRepository repository,
    String profileId,
  ) async {
    saving = true;
    error = null;
    notifyListeners();
    try {
      await repository.deleteProfile(profileId);
      remove(profileId);
      saving = false;
      notifyListeners();
      return true;
    } catch (value) {
      saving = false;
      error = value.toString();
      notifyListeners();
      return false;
    }
  }

  Future<bool> save(
    Future<void> Function(CompanionProfile profile) persist,
    CompanionProfile profile,
  ) async {
    saving = true;
    error = null;
    notifyListeners();
    try {
      await persist(profile);
      replace(profile);
      saving = false;
      notifyListeners();
      return true;
    } catch (value) {
      saving = false;
      error = value.toString();
      notifyListeners();
      return false;
    }
  }
}
