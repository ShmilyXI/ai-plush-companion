import 'dart:typed_data';

import 'package:ai_plush_companion/features/chat/data/realtime_event_decoder.dart';
import 'package:ai_plush_companion/features/chat/domain/realtime_models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('decodes known events and preserves unknown events', () {
    final decoder = RealtimeEventDecoder();
    final ready = decoder.decodeJson('{"type":"session.ready","sequence":1}');
    final unknown = decoder.decodeJson(
      '{"type":"future.event","sequence":2,"details":{"x":1}}',
    );
    expect(ready, isA<RealtimeJsonEvent>());
    expect(unknown, isA<RealtimeUnknownEvent>());
  });

  test('pairs binary TTS data with metadata and rejects an unpaired frame', () {
    final decoder = RealtimeEventDecoder();
    expect(
      () => decoder.decodeBinary(Uint8List.fromList([1])),
      throwsA(isA<RealtimeProtocolException>()),
    );
    decoder.decodeJson(
      '{"type":"tts.audio","sequence":1,"transport":"binary","byte_length":2,"mime_type":"audio/wav","audio_sequence":1}',
    );
    final audio = decoder.decodeBinary(Uint8List.fromList([1, 2]));
    expect(audio.bytes, [1, 2]);
    expect(audio.details['mime_type'], 'audio/wav');
  });

  test('rejects non-increasing sequences', () {
    final decoder = RealtimeEventDecoder();
    decoder.decodeJson('{"type":"stream.ready","sequence":2}');
    expect(
      () => decoder.decodeJson('{"type":"asr.final","sequence":2}'),
      throwsA(isA<RealtimeProtocolException>()),
    );
  });
}
