import 'package:ai_plush_companion/features/audio/data/audio_turn_sender.dart';
import 'package:ai_plush_companion/features/chat/presentation/message_composer.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _FakeSender implements VoiceMessageSender {
  bool recording = false;
  int starts = 0;
  int stops = 0;
  bool? cancelled;

  @override
  bool get isRecording => recording;

  @override
  Future<bool> start() async {
    starts++;
    recording = true;
    return true;
  }

  @override
  Future<AudioTurnResult?> stop({bool cancelled = false}) async {
    stops++;
    this.cancelled = cancelled;
    recording = false;
    return null;
  }
}

void main() {
  testWidgets('long press uses the real audio sender and release sends', (
    tester,
  ) async {
    final sender = _FakeSender();
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          home: Scaffold(body: MessageComposer(audioSender: sender)),
        ),
      ),
    );
    final gesture = await tester.startGesture(
      tester.getCenter(find.bySemanticsLabel('按住说话，向上滑动取消')),
    );
    await tester.pump(const Duration(milliseconds: 600));
    await gesture.up();
    await tester.pump();

    expect(sender.starts, 1);
    expect(sender.stops, 1);
    expect(sender.cancelled, isFalse);
  });

  testWidgets('upward release cancels the real audio sender', (tester) async {
    final sender = _FakeSender();
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          home: Scaffold(body: MessageComposer(audioSender: sender)),
        ),
      ),
    );
    final gesture = await tester.startGesture(
      tester.getCenter(find.bySemanticsLabel('按住说话，向上滑动取消')),
    );
    await tester.pump(const Duration(milliseconds: 600));
    await gesture.moveBy(const Offset(0, -120));
    await gesture.up();
    await tester.pump();

    expect(sender.starts, 1);
    expect(sender.stops, 1);
    expect(sender.cancelled, isTrue);
  });
}
