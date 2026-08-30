import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/providers/core_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../../profiles/presentation/profile_selector_drawer.dart';

class FullScreenCallPage extends ConsumerStatefulWidget {
  const FullScreenCallPage({super.key});

  @override
  ConsumerState<FullScreenCallPage> createState() => _FullScreenCallPageState();
}

class _FullScreenCallPageState extends ConsumerState<FullScreenCallPage> {
  Timer? _timer;
  int _seconds = 0;
  bool _muted = false;
  final bool _speaking = false;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() => _seconds++);
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final profile = ref.watch(companionStoreProvider).selectedProfile;
    final status = _speaking ? '正在说话…' : (_muted ? '已静音' : '正在聆听');
    final duration =
        '${(_seconds ~/ 60).toString().padLeft(2, '0')}:${(_seconds % 60).toString().padLeft(2, '0')}';
    return Scaffold(
      backgroundColor: AppTheme.ink,
      body: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 10, 16, 0),
              child: Row(
                children: [
                  IconButton(
                    tooltip: '收起通话',
                    onPressed: () => context.pop(),
                    color: Colors.white,
                    icon: const Icon(Icons.keyboard_arrow_down),
                  ),
                  const Spacer(),
                  const Icon(
                    Icons.lock_outline,
                    color: Colors.white54,
                    size: 17,
                  ),
                  const SizedBox(width: 6),
                  const Text(
                    '端到端连接',
                    style: TextStyle(color: Colors.white54, fontSize: 12),
                  ),
                ],
              ),
            ),
            const Spacer(),
            ProfileAvatar(profile: profile, size: 112),
            const SizedBox(height: 18),
            Text(
              profile.name,
              style: const TextStyle(
                color: Colors.white,
                fontSize: 26,
                fontWeight: FontWeight.w800,
              ),
            ),
            const SizedBox(height: 7),
            Text(
              status,
              style: const TextStyle(color: Colors.white70, fontSize: 15),
            ),
            const SizedBox(height: 10),
            Text(
              duration,
              style: const TextStyle(
                color: Colors.white54,
                fontSize: 14,
                fontFeatures: [FontFeature.tabularFigures()],
              ),
            ),
            const SizedBox(height: 34),
            AnimatedContainer(
              duration: const Duration(milliseconds: 300),
              width: _speaking ? 154 : 122,
              height: _speaking ? 154 : 122,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: AppTheme.accent.withValues(alpha: _speaking ? .28 : .12),
                border: Border.all(
                  color: AppTheme.accent.withValues(alpha: .5),
                ),
              ),
              child: Icon(
                _speaking ? Icons.graphic_eq : Icons.mic_none,
                color: Colors.white,
                size: 40,
              ),
            ),
            const Spacer(),
            Padding(
              padding: const EdgeInsets.fromLTRB(38, 0, 38, 28),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  _CallControl(
                    icon: _muted ? Icons.mic_off : Icons.mic,
                    label: _muted ? '取消静音' : '静音',
                    active: _muted,
                    onTap: () => setState(() => _muted = !_muted),
                  ),
                  const _CallControl(
                    icon: Icons.volume_up_outlined,
                    label: '扬声器',
                  ),
                  _CallControl(
                    icon: Icons.call_end,
                    label: '结束',
                    destructive: true,
                    onTap: () => context.pop(),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _CallControl extends StatelessWidget {
  const _CallControl({
    required this.icon,
    required this.label,
    this.onTap,
    this.active = false,
    this.destructive = false,
  });

  final IconData icon;
  final String label;
  final VoidCallback? onTap;
  final bool active;
  final bool destructive;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        IconButton.filled(
          onPressed: onTap,
          style: IconButton.styleFrom(
            backgroundColor: destructive
                ? const Color(0xFFD9574E)
                : (active ? Colors.white : Colors.white12),
            foregroundColor: destructive || !active
                ? Colors.white
                : AppTheme.ink,
            fixedSize: const Size(58, 58),
          ),
          icon: Icon(icon, size: 24),
        ),
        const SizedBox(height: 7),
        Text(
          label,
          style: const TextStyle(color: Colors.white70, fontSize: 12),
        ),
      ],
    );
  }
}
