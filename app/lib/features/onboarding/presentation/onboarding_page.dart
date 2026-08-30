import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/app_theme.dart';

class OnboardingPage extends StatelessWidget {
  const OnboardingPage({super.key});
  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(24, 42, 24, 26),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Spacer(),
            Container(
              width: 92,
              height: 92,
              alignment: Alignment.center,
              decoration: const BoxDecoration(
                color: AppTheme.sage,
                shape: BoxShape.circle,
              ),
              child: const Icon(
                Icons.auto_awesome,
                size: 42,
                color: AppTheme.accentDark,
              ),
            ),
            const SizedBox(height: 24),
            const Text(
              '先选一个陪伴角色',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 28, fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 9),
            const Text(
              '角色可以随时更换，也可以之后自己创建。',
              textAlign: TextAlign.center,
              style: TextStyle(color: AppTheme.mutedInk, fontSize: 15),
            ),
            const Spacer(),
            FilledButton(
              onPressed: () => context.go('/profiles'),
              child: const Text('浏览预设角色'),
            ),
            const SizedBox(height: 10),
            OutlinedButton(
              onPressed: () => context.go('/chat'),
              child: const Text('先进入聊天'),
            ),
            const SizedBox(height: 10),
          ],
        ),
      ),
    ),
  );
}
