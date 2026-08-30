import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'core/l10n/app_localizations.dart';
import 'core/routing/app_router.dart';
import 'core/theme/app_theme.dart';

class CompanionApp extends ConsumerStatefulWidget {
  const CompanionApp({super.key, this.initialLocation = '/chat'});

  final String initialLocation;

  @override
  ConsumerState<CompanionApp> createState() => _CompanionAppState();
}

class _CompanionAppState extends ConsumerState<CompanionApp> {
  late final GoRouter _router = buildAppRouter(
    ref,
    initialLocation: widget.initialLocation,
  );

  @override
  Widget build(BuildContext context) => MaterialApp.router(
    title: '拾光陪伴',
    theme: AppTheme.light(),
    locale: const Locale('zh'),
    supportedLocales: const [Locale('zh')],
    localizationsDelegates: const [
      AppLocalizations.delegate,
      GlobalMaterialLocalizations.delegate,
      GlobalWidgetsLocalizations.delegate,
      GlobalCupertinoLocalizations.delegate,
    ],
    routerConfig: _router,
  );
}
