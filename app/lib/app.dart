import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'core/l10n/app_localizations.dart';
import 'core/providers/core_providers.dart';
import 'core/routing/app_router.dart';
import 'core/theme/app_theme.dart';

class CompanionApp extends ConsumerStatefulWidget {
  const CompanionApp({super.key, this.initialLocation = '/chat'});

  final String initialLocation;

  @override
  ConsumerState<CompanionApp> createState() => _CompanionAppState();
}

class _CompanionAppState extends ConsumerState<CompanionApp> {
  late final GoRouter _router;

  @override
  void initState() {
    super.initState();
    final config = ref.read(appConfigProvider);
    _router = buildAppRouter(ref, initialLocation: widget.initialLocation);
    if (!config.isDemo) unawaited(_restoreSession());
  }

  Future<void> _restoreSession() async {
    try {
      final session = await ref.read(secureStoreProvider).read();
      if (!mounted) return;
      final store = ref.read(companionStoreProvider);
      if (session == null) {
        // A login may finish while the initial secure-store read is in
        // flight. Do not let the stale empty result sign that user out.
        if (!store.signedIn) store.signOut();
        return;
      }
      // Logout can race the first read as well. Confirm the token is still
      // present before restoring the session into the router.
      if (await ref.read(secureStoreProvider).read() == null) return;
      if (!mounted) return;
      store.signIn();
      await store.bootstrap(
        profileRepository: ref.read(profileRepositoryProvider),
        deviceRepository: ref.read(deviceRepositoryProvider),
        conversationRepository: ref.read(conversationRepositoryProvider),
        preferences: ref.read(preferencesStoreProvider),
      );
      if (mounted &&
          store.profiles.isEmpty &&
          (widget.initialLocation == '/chat' ||
              widget.initialLocation == '/' ||
              widget.initialLocation == '/login')) {
        _router.go('/onboarding');
      }
    } catch (_) {
      // A storage/plugin failure must leave the app at the signed-out branch
      // instead of producing an unhandled asynchronous exception.
      if (mounted) ref.read(companionStoreProvider).signOut();
    }
  }

  @override
  Widget build(BuildContext context) => MaterialApp.router(
    title: '拾光陪伴',
    debugShowCheckedModeBanner: false,
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
