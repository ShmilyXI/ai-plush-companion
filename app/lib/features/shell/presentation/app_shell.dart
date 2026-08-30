import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import 'bottom_navigation.dart';

class AppShell extends StatelessWidget {
  const AppShell({super.key, required this.child});

  final Widget child;

  static const paths = ['/chat', '/devices', '/profiles', '/account'];

  @override
  Widget build(BuildContext context) {
    final location = GoRouterState.of(context).matchedLocation;
    final index = paths
        .indexWhere((path) => location.startsWith(path))
        .clamp(0, paths.length - 1);
    return Scaffold(
      body: SafeArea(child: child),
      bottomNavigationBar: CompanionBottomNavigation(
        index: index,
        onChanged: (value) => context.go(paths[value]),
      ),
    );
  }
}
