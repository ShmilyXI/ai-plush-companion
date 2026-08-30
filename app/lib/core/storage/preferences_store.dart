import 'package:shared_preferences/shared_preferences.dart';

class PreferencesStore {
  PreferencesStore({SharedPreferences? preferences})
    : _preferences = preferences;
  SharedPreferences? _preferences;

  Future<SharedPreferences> get _instance async =>
      _preferences ??= await SharedPreferences.getInstance();

  Future<bool> readAutoPlay() async =>
      (await _instance).getBool('auto_play_replies') ?? true;
  Future<void> writeAutoPlay(bool value) async =>
      (await _instance).setBool('auto_play_replies', value);
}
