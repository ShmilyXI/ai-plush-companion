import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/providers/core_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../../audio/data/companion_audio_service.dart';
import '../application/call_controller.dart';
import '../domain/call_models.dart';
import '../../profiles/presentation/profile_selector_drawer.dart';

class FullScreenCallPage extends ConsumerStatefulWidget {
  const FullScreenCallPage({
    super.key,
    this.controller,
    this.streamUrl,
    this.runtimeToken,
    this.conversationId,
    this.profileId,
    this.runtimeLoader,
  });

  final CallController? controller;
  final Uri? streamUrl;
  final String? runtimeToken;
  final String? conversationId;
  final String? profileId;
  final Future<Map<String, dynamic>> Function()? runtimeLoader;

  @override
  ConsumerState<FullScreenCallPage> createState() => _FullScreenCallPageState();
}

class _FullScreenCallPageState extends ConsumerState<FullScreenCallPage>
    with WidgetsBindingObserver {
  late CallController _controller;
  late final bool _ownsController;
  bool _speakerOn = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // Injected and provider-backed controllers are owned by their caller or
    // Riverpod scope. Leaving the page must not dispose an active call.
    _ownsController = false;
    _controller = widget.controller ?? ref.read(callControllerProvider);
    _controller.addListener(_refresh);
    if (widget.runtimeLoader != null) {
      unawaited(
        _controller.startFromRuntime(
          conversationId: widget.conversationId,
          runtime: widget.runtimeLoader!,
          profileId: widget.profileId,
        ),
      );
    } else if (widget.streamUrl != null &&
        widget.runtimeToken != null &&
        widget.conversationId != null) {
      unawaited(
        _controller.start(
          streamUrl: widget.streamUrl!,
          runtimeToken: widget.runtimeToken!,
          conversationId: widget.conversationId!,
          profileId: widget.profileId,
        ),
      );
    } else if (!ref.read(appConfigProvider).isDemo) {
      // The normal route carries no secrets in its URI. Reuse the selected
      // persistent conversation and ask ChatController for a short-lived
      // runtime just before opening the call socket.
      final store = ref.read(companionStoreProvider);
      final chat = ref.read(chatControllerProvider);
      unawaited(
        _controller.startFromRuntime(
          conversationId: store.currentConversation.id,
          runtime: chat.prepareRuntime,
          profileId: store.selectedProfile.id,
        ),
      );
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _controller.removeListener(_refresh);
    if (_ownsController) {
      unawaited(_controller.end());
      _controller.dispose();
    }
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Backgrounding or locking the screen is part of the call workflow. The
    // audio_session interruption stream handles phone calls and other apps
    // taking focus; an ordinary paused/resumed lifecycle must keep the call
    // transport and recorder alive.
    if (state == AppLifecycleState.detached) {
      unawaited(_controller.end());
    }
  }

  void _refresh() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final profile = ref.watch(companionStoreProvider).selectedProfile;
    final status = _statusLabel(_controller.state, _controller.error);
    final elapsed = _controller.elapsed;
    final duration =
        '${elapsed.inMinutes.toString().padLeft(2, '0')}:${(elapsed.inSeconds % 60).toString().padLeft(2, '0')}';
    final speaking = _controller.state == CallState.speaking;
    final muted = _controller.muted;
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
                    onPressed: () {
                      if (Navigator.of(context).canPop()) {
                        Navigator.of(context).pop();
                      }
                    },
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
            if (_controller.transcript.isNotEmpty)
              Padding(
                padding: const EdgeInsets.fromLTRB(28, 10, 28, 0),
                child: Text(
                  _controller.transcript,
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: Colors.white60, fontSize: 13),
                ),
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
              width: speaking ? 154 : 122,
              height: speaking ? 154 : 122,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: AppTheme.accent.withValues(alpha: speaking ? .28 : .12),
                border: Border.all(
                  color: AppTheme.accent.withValues(alpha: .5),
                ),
              ),
              child: Icon(
                speaking ? Icons.graphic_eq : Icons.mic_none,
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
                    icon: muted ? Icons.mic_off : Icons.mic,
                    label: muted ? '取消静音' : '静音',
                    active: muted,
                    onTap: _controller.toggleMute,
                  ),
                  _CallControl(
                    icon: _speakerOn
                        ? Icons.volume_up_outlined
                        : Icons.volume_off_outlined,
                    label: _speakerOn ? '扬声器' : '听筒',
                    active: _speakerOn,
                    onTap: _toggleSpeaker,
                  ),
                  _CallControl(
                    icon: Icons.call_end,
                    label: '结束',
                    destructive: true,
                    onTap: _hangUp,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _hangUp() {
    // Pop immediately so a slow platform recorder/socket shutdown cannot
    // leave the call screen visually stuck in its ending state.
    unawaited(_controller.end());
    if (mounted && Navigator.of(context).canPop()) {
      Navigator.of(context).pop();
    }
  }

  void _toggleSpeaker() {
    final next = !_speakerOn;
    unawaited(() async {
      final changed = await CompanionAudioService.shared.setSpeakerphoneOn(
        next,
      );
      if (mounted && changed) setState(() => _speakerOn = next);
    }());
  }

  String _statusLabel(CallState state, String? error) {
    if (state == CallState.failed) return error ?? '连接失败';
    switch (state) {
      case CallState.preparing:
        return '准备麦克风';
      case CallState.connecting:
        return '正在连接';
      case CallState.thinking:
        return '正在思考';
      case CallState.speaking:
        return '正在说话';
      case CallState.muted:
        return '已静音';
      case CallState.reconnecting:
        return '正在恢复连接';
      case CallState.ending:
        return '正在结束';
      case CallState.ended:
        return '通话已结束';
      case CallState.idle:
        return '尚未连接';
      case CallState.listening:
        return '正在聆听';
      case CallState.failed:
        return error ?? '连接失败';
    }
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
