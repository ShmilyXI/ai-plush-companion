import 'dart:async';
import 'dart:typed_data';

import 'package:ai_plush_companion/features/audio/data/audio_capture_controller.dart';
import 'package:ai_plush_companion/features/audio/data/audio_playback_controller.dart';
import 'package:ai_plush_companion/features/audio/domain/playback_state.dart';
import 'package:ai_plush_companion/features/call/application/call_controller.dart';
import 'package:ai_plush_companion/features/call/application/call_transport.dart';
import 'package:ai_plush_companion/features/call/domain/call_models.dart';
import 'package:ai_plush_companion/features/call/presentation/full_screen_call_page.dart';
import 'package:ai_plush_companion/features/chat/domain/realtime_models.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _Transport implements CallTransport {
  final stream = StreamController<RealtimeEvent>.broadcast();
  bool connected = false;
  bool stopped = false;

  @override
  Stream<RealtimeEvent> get events => stream.stream;
  @override
  bool get isConnected => connected;
  @override
  Future<void> connect({
    String? conversationId,
    String? profileId,
    Map<String, dynamic>? format,
  }) async => connected = true;
  @override
  void pushAudio(Uint8List bytes) {}
  @override
  void commitAudio(String requestId, int durationMs) {}
  @override
  void cancel(String turnId, int playedMs) {}
  @override
  void heartbeat() {}
  @override
  void stop() => stopped = true;
  @override
  Future<void> close() {
    connected = false;
    return Future<void>.value();
  }
}

class _Capture implements AudioCapture {
  bool running = false;
  @override
  bool get isRunning => running;
  @override
  Future<bool> start({
    PcmFrameHandler? onFrame,
    AudioFrameHandler? onAudioFrame,
  }) async {
    running = true;
    return true;
  }

  @override
  Future<void> stop() async => running = false;
  @override
  Future<void> dispose() async {}
}

class _Playback implements CallPlayback {
  @override
  int playedMilliseconds = 0;
  @override
  PlaybackStatus status = PlaybackStatus.idle;
  @override
  Future<void> enqueue(PlaybackItem item) async {}
  @override
  Future<void> stop() async {}
}

void main() {
  testWidgets('wires mute and hang-up controls to the call controller', (
    tester,
  ) async {
    final transport = _Transport();
    final controller = CallController(
      transport: transport,
      capture: _Capture(),
      playback: _Playback(),
    );
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          home: FullScreenCallPage(
            controller: controller,
            streamUrl: Uri.parse('wss://example.test'),
            runtimeToken: 'token',
            conversationId: 'conversation-1',
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('正在聆听'), findsOneWidget);

    await tester.tap(find.byIcon(Icons.mic));
    await tester.pump();
    expect(find.text('已静音'), findsOneWidget);
    expect(controller.muted, isTrue);

    await tester.tap(find.byIcon(Icons.call_end));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(controller.state, anyOf(CallState.ending, CallState.ended));
    expect(transport.stopped, isTrue);
    await transport.stream.close();
    controller.dispose();
  });
}
