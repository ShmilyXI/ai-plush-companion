import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../features/account/presentation/account_page.dart';
import '../../features/auth/presentation/login_page.dart';
import '../../features/auth/presentation/register_page.dart';
import '../../features/auth/presentation/reset_password_page.dart';
import '../../features/call/presentation/full_screen_call_page.dart';
import '../../features/chat/presentation/chat_page.dart';
import '../../features/devices/presentation/devices_page.dart';
import '../../features/onboarding/presentation/onboarding_page.dart';
import '../../features/profiles/presentation/profile_editor_page.dart';
import '../../features/profiles/presentation/profile_list_page.dart';
import '../providers/core_providers.dart';
import '../../features/shell/presentation/app_shell.dart';

GoRouter buildAppRouter(WidgetRef ref, {String initialLocation = '/chat'}) {
  return GoRouter(
    debugLogDiagnostics: false,
    initialLocation: initialLocation,
    overridePlatformDefaultLocation: true,
    routes: [
      GoRoute(path: '/', builder: (context, state) => const ChatPage()),
      GoRoute(
        path: '/health',
        builder: (context, state) => Scaffold(
          appBar: AppBar(title: const Text('AI 陪伴')),
          body: const Center(child: Text('服务正常')),
        ),
      ),
      GoRoute(path: '/login', builder: (context, state) => const LoginPage()),
      GoRoute(
        path: '/register',
        builder: (context, state) => const RegisterPage(),
      ),
      GoRoute(
        path: '/reset-password',
        builder: (context, state) => const ResetPasswordPage(),
      ),
      GoRoute(
        path: '/onboarding',
        builder: (context, state) => const OnboardingPage(),
      ),
      GoRoute(
        path: '/call',
        builder: (context, state) => const FullScreenCallPage(),
      ),
      ShellRoute(
        builder: (context, state, child) => AppShell(child: child),
        routes: [
          GoRoute(path: '/chat', builder: (context, state) => const ChatPage()),
          GoRoute(
            path: '/devices',
            builder: (context, state) => const DevicesPage(),
          ),
          GoRoute(
            path: '/profiles',
            builder: (context, state) => const ProfileListPage(),
          ),
          GoRoute(
            path: '/profiles/new',
            builder: (context, state) => const ProfileEditorPage(),
          ),
          GoRoute(
            path: '/profiles/:id',
            builder: (context, state) =>
                ProfileEditorPage(profileId: state.pathParameters['id']),
          ),
          GoRoute(
            path: '/account',
            builder: (context, state) => const AccountPage(),
          ),
        ],
      ),
    ],
    redirect: (context, state) {
      final store = ref.read(companionStoreProvider);
      final publicPaths = {'/login', '/register', '/reset-password'};
      if (!store.signedIn && !publicPaths.contains(state.matchedLocation)) {
        return '/login';
      }
      if (store.signedIn && publicPaths.contains(state.matchedLocation)) {
        return '/chat';
      }
      return null;
    },
  );
}
