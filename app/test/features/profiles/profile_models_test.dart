import 'package:ai_plush_companion/features/profiles/domain/profile_models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('maps the app profile contract and emits an editable save payload', () {
    final profile = CompanionProfile.fromMap({
      'id': 'p1',
      'name': '露娜',
      'personality': '耐心',
      'systemPrompt': '先听再答',
      'relationMode': 'friend',
      'ttsVoiceId': 'voice-1',
      'ttsVoiceName': '温柔女声',
      'ttsVolume': 64,
      'ttsRate': 110,
      'ttsPitch': 90,
      'memoryEnabled': 0,
      'models': [
        {'modelType': 'LLM', 'source': 'global', 'resourceId': 'model-1'},
      ],
      'skills': [
        {'skillId': 'weather', 'enabled': true},
        {'skillId': 'skill-web-search', 'enabled': false},
      ],
      'capabilities': [
        {'id': 'web_search', 'enabled': true},
      ],
      'boundDevices': [],
      'activeVersionNo': 4,
    });

    expect(profile.memoryEnabled, isFalse);
    expect(profile.ttsVolume, closeTo(.64, .001));
    expect(profile.models.single.resourceId, 'model-1');
    expect(profile.skills.first.skillId, 'weather');
    expect(profile.capabilities, contains('web_search'));
    expect(profile.toSavePayload(), containsPair('agentName', '露娜'));
    expect(profile.toSavePayload(), containsPair('ttsVoiceId', 'voice-1'));
    expect(profile.toSavePayload(), containsPair('ttsVolume', 64));
    expect(profile.toSavePayload(), containsPair('ttsRate', 110));
    expect(profile.toSavePayload(), containsPair('memoryEnabled', 0));
    expect(profile.toSavePayload(), contains('models'));
    expect(profile.toSavePayload(), contains('skills'));
  });

  test('emits empty binding arrays so disabled capabilities are persisted', () {
    final profile = CompanionProfile(
      id: 'p3',
      name: '空配置',
      summary: '',
      personality: '',
      systemPrompt: '',
      voice: '默认音色',
      capabilities: const {},
      memoryEnabled: true,
      source: ProfileSource.custom,
    );

    expect(profile.toSavePayload()['models'], isEmpty);
    expect(profile.toSavePayload()['skills'], isEmpty);
  });

  test('does not expose disabled skills as active capabilities', () {
    final profile = CompanionProfile.fromMap({
      'id': 'p2',
      'name': '墨墨',
      'skills': [
        {'skillId': 'skill-weather', 'enabled': false},
        {'skillId': 'skill-web-search', 'enabled': true},
      ],
    });

    expect(profile.capabilities, isNot(contains('weather')));
    expect(profile.capabilities, contains('web_search'));
  });

  test('keeps the public voice ID when the response uses voice aliases', () {
    final profile = CompanionProfile.fromMap({
      'id': 'p-alias',
      'name': '别名角色',
      'voiceId': 'voice-alias',
      'voiceName': '别名音色',
    });

    expect(profile.ttsVoiceId, 'voice-alias');
    expect(profile.voice, '别名音色');
    expect(profile.toSavePayload(), containsPair('ttsVoiceId', 'voice-alias'));
  });

  test('can explicitly clear an existing voice ID in a draft', () {
    final profile = CompanionProfile.fromMap({
      'id': 'p-clear',
      'name': '清除音色',
      'ttsVoiceId': 'voice-old',
    });

    expect(profile.copyWith(clearTtsVoiceId: true).ttsVoiceId, isNull);
  });

  test('does not retain provider credentials in model overrides', () {
    final binding = ProfileModelBinding.fromMap({
      'modelType': 'LLM',
      'source': 'global',
      'resourceId': 'model-1',
      'overrides': {
        'api_key': 'secret-value',
        'nested': {'token': 'secret-token', 'temperature': 0.2},
      },
    });

    expect(binding.overrides, isNot(contains('api_key')));
    expect((binding.overrides['nested'] as Map), isNot(contains('token')));
    expect((binding.overrides['nested'] as Map)['temperature'], 0.2);
  });

  test('redacts credentials from skill override JSON', () {
    final binding = ProfileSkillBinding.fromMap({
      'skillId': 'skill-weather',
      'overrideJson': '{"api_key":"secret","location":"上海"}',
    });

    expect(binding.overrideJson, isNot(contains('secret')));
    expect(binding.overrideJson, contains('上海'));
  });
}
