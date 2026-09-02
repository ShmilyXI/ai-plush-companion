import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app.dart';
import 'features/audio/data/companion_audio_service.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await CompanionAudioService.initializeShared();
  runApp(const ProviderScope(child: CompanionApp()));
}
