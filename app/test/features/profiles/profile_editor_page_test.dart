import 'package:ai_plush_companion/features/profiles/presentation/profile_editor_page.dart';
import 'package:ai_plush_companion/core/config/app_config.dart';
import 'package:ai_plush_companion/core/network/api_client.dart';
import 'package:ai_plush_companion/core/providers/core_providers.dart';
import 'package:ai_plush_companion/core/storage/secure_store.dart';
import 'package:ai_plush_companion/features/profiles/data/profile_repository.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _VoiceProfileRepository extends ProfileRepository {
  _VoiceProfileRepository()
    : super(
        ApiClient(
          baseUrl: Uri.parse('https://test.invalid'),
          secureStore: SecureStore(),
        ),
      );

  Map<String, dynamic>? saved;

  @override
  Future<Map<String, dynamic>> getProfile(String id) async => {
    'id': id,
    'name': '小夏',
    'ttsModelId': 'tts-a',
    'ttsVoiceId': 'voice-a',
    'ttsVoiceName': '女声',
  };

  @override
  Future<List<Map<String, dynamic>>> listCapabilityOptions(String id) async =>
      const [];

  @override
  Future<List<Map<String, dynamic>>> listVoiceOptions(String modelId) async =>
      const [
        {'id': 'voice-a', 'name': '女声'},
        {'id': 'voice-b', 'name': '中性声'},
      ];

  @override
  Future<void> saveAndActivate(String id, Map<String, dynamic> payload) async {
    saved = payload;
  }
}

void main() {
  testWidgets('renders editable identity, model, voice and memory fields', (
    tester,
  ) async {
    await tester.pumpWidget(
      const ProviderScope(
        child: MaterialApp(home: ProfileEditorPage(profileId: 'profile-luna')),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('角色名称'), findsOneWidget);
    expect(find.text('关系定位'), findsOneWidget);
    expect(find.text('对话模型 ID'), findsOneWidget);
    expect(find.text('音色 ID'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('允许角色记住重要信息'),
      500,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('允许角色记住重要信息'), findsOneWidget);
  });

  testWidgets('authorized voice selection persists its real ID', (
    tester,
  ) async {
    final repository = _VoiceProfileRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          appConfigProvider.overrideWithValue(
            AppConfig(
              apiBaseUrl: Uri.parse('https://test.invalid'),
              isDemo: false,
            ),
          ),
          profileRepositoryProvider.overrideWithValue(repository),
        ],
        child: MaterialApp(
          home: const ProfileEditorPage(profileId: 'profile-1'),
        ),
      ),
    );
    await tester.pumpAndSettle();
    final voiceSelector = find.byType(DropdownButtonFormField<String>).last;
    await tester.drag(find.byType(ListView).first, const Offset(0, -500));
    await tester.pumpAndSettle();
    await tester.tap(voiceSelector);
    await tester.pump();
    await tester.tap(find.text('中性声'));
    await tester.pump();
    await tester.scrollUntilVisible(
      find.text('保存'),
      -500,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(find.text('保存'));
    await tester.pumpAndSettle();

    expect(repository.saved?['ttsVoiceId'], 'voice-b');
  });
}
