import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/providers/core_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../../audio/data/audio_turn_sender.dart';

class MessageComposer extends ConsumerStatefulWidget {
  const MessageComposer({super.key, this.audioSender, this.onAudioSent});

  final VoiceMessageSender? audioSender;
  final ValueChanged<AudioTurnResult>? onAudioSent;

  @override
  ConsumerState<MessageComposer> createState() => _MessageComposerState();
}

class _MessageComposerState extends ConsumerState<MessageComposer> {
  final _controller = TextEditingController();
  final _focusNode = FocusNode();
  Timer? _timer;
  DateTime? _recordingStarted;
  bool _recording = false;
  bool _cancelled = false;
  double _dragDistance = 0;

  @override
  void dispose() {
    _timer?.cancel();
    final sender = widget.audioSender;
    if (sender != null && sender.isRecording) {
      unawaited(sender.stop(cancelled: true));
    }
    _controller.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  void _startRecording() {
    if (_recording) return;
    setState(() {
      _recording = true;
      _cancelled = false;
      _dragDistance = 0;
      _recordingStarted = DateTime.now();
    });
    _timer = Timer.periodic(const Duration(milliseconds: 250), (_) {
      if (mounted) setState(() {});
    });
    final sender =
        widget.audioSender ?? ref.read(chatControllerProvider).voiceSender;
    unawaited(_startAudioSender(sender));
  }

  void _finishRecording() {
    final cancelled = _cancelled || _dragDistance > 90;
    _timer?.cancel();
    _timer = null;
    setState(() {
      _recording = false;
      _recordingStarted = null;
    });
    final sender =
        widget.audioSender ?? ref.read(chatControllerProvider).voiceSender;
    unawaited(_finishAudioSender(sender, cancelled));
  }

  Future<void> _startAudioSender(VoiceMessageSender sender) async {
    try {
      final started = await sender.start();
      if (!started && mounted) {
        _timer?.cancel();
        _timer = null;
        setState(() {
          _recording = false;
          _recordingStarted = null;
        });
      }
    } catch (_) {
      if (mounted) {
        _timer?.cancel();
        _timer = null;
        setState(() {
          _recording = false;
          _recordingStarted = null;
        });
      }
    }
  }

  Future<void> _finishAudioSender(
    VoiceMessageSender sender,
    bool cancelled,
  ) async {
    try {
      final result = await sender.stop(cancelled: cancelled);
      if (result != null) widget.onAudioSent?.call(result);
    } catch (_) {
      // The caller keeps the text draft; a failed audio turn is not retried.
    }
  }

  @override
  Widget build(BuildContext context) {
    final store = ref.watch(companionStoreProvider);
    final elapsed = _recordingStarted == null
        ? Duration.zero
        : DateTime.now().difference(_recordingStarted!);
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 8, 14, 14),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (_recording)
            Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
              decoration: BoxDecoration(
                color: AppTheme.sage,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Row(
                children: [
                  const Icon(
                    Icons.fiber_manual_record,
                    color: AppTheme.accent,
                    size: 14,
                  ),
                  const SizedBox(width: 8),
                  Text('${elapsed.inSeconds.toString().padLeft(2, '0')} 秒'),
                  const Spacer(),
                  Text(
                    _cancelled || _dragDistance > 90 ? '松开取消' : '松开发送',
                    style: const TextStyle(color: AppTheme.mutedInk),
                  ),
                ],
              ),
            ),
          const SizedBox(height: 8),
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Expanded(
                child: TextField(
                  controller: _controller,
                  focusNode: _focusNode,
                  minLines: 1,
                  maxLines: 4,
                  textInputAction: TextInputAction.newline,
                  decoration: const InputDecoration(
                    hintText: '和陪伴角色说点什么…',
                    labelText: '消息',
                  ),
                  onSubmitted: (value) {
                    if (value.trim().isNotEmpty) {
                      unawaited(
                        ref.read(chatControllerProvider).sendText(value),
                      );
                      _controller.clear();
                    }
                  },
                ),
              ),
              const SizedBox(width: 8),
              GestureDetector(
                onLongPressStart: (_) {
                  _focusNode.unfocus();
                  _startRecording();
                },
                onLongPressMoveUpdate: (details) {
                  if (!_recording) return;
                  setState(() {
                    _dragDistance = (-details.offsetFromOrigin.dy).clamp(
                      0,
                      160,
                    );
                    _cancelled = _dragDistance > 90;
                  });
                },
                onLongPressEnd: (_) => _finishRecording(),
                onLongPressCancel: () {
                  _cancelled = true;
                  _finishRecording();
                },
                child: Semantics(
                  button: true,
                  label: '按住说话，向上滑动取消',
                  child: Container(
                    width: 52,
                    height: 52,
                    decoration: BoxDecoration(
                      color: _recording ? AppTheme.accentDark : AppTheme.ink,
                      borderRadius: BorderRadius.circular(15),
                    ),
                    child: Icon(
                      _recording ? Icons.mic : Icons.mic_none,
                      color: Colors.white,
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 6),
              IconButton.filled(
                tooltip: '发送消息',
                onPressed: store.isSending
                    ? null
                    : () {
                        final value = _controller.text;
                        if (value.trim().isEmpty) return;
                        unawaited(
                          ref.read(chatControllerProvider).sendText(value),
                        );
                        _controller.clear();
                      },
                icon: store.isSending
                    ? const SizedBox.square(
                        dimension: 18,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: Colors.white,
                        ),
                      )
                    : const Icon(Icons.arrow_upward),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
