import 'package:flutter/foundation.dart';

import '../domain/profile_models.dart';

class ProfileController extends ChangeNotifier {
  ProfileController({List<CompanionProfile>? initial})
    : profiles = List.of(initial ?? const []);
  List<CompanionProfile> profiles;
  bool saving = false;
  String? error;

  void replace(CompanionProfile profile) {
    profiles = profiles
        .map((item) => item.id == profile.id ? profile : item)
        .toList();
    notifyListeners();
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
