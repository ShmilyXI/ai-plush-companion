import 'dart:convert';
import 'dart:typed_data';

import '../domain/realtime_models.dart';

class RealtimeEventDecoder {
  int _lastSequence = 0;
  _PendingAudio? _pendingAudio;

  RealtimeEvent decodeJson(String payload) {
    final dynamic parsed;
    try {
      parsed = jsonDecode(payload);
    } on FormatException catch (error) {
      throw RealtimeProtocolException('invalid JSON: $error');
    }
    if (parsed is! Map<String, dynamic>) {
      throw const RealtimeProtocolException('event must be an object');
    }
    final type = parsed['type'];
    final sequence = parsed['sequence'];
    if (type is! String || type.isEmpty || sequence is! int) {
      throw const RealtimeProtocolException(
        'event type or sequence is invalid',
      );
    }
    if (sequence <= _lastSequence) {
      throw const RealtimeProtocolException('event sequence is not increasing');
    }
    _lastSequence = sequence;
    final details = parsed['details'] is Map
        ? Map<String, dynamic>.from(parsed['details'] as Map)
        : <String, dynamic>{};
    for (final key in const [
      'mime_type',
      'byte_length',
      'audio_sequence',
      'transport',
    ]) {
      if (parsed.containsKey(key)) details[key] = parsed[key];
    }
    final turnId = parsed['turn_id'] as String?;
    final requestId = parsed['request_id'] as String?;
    final transport = parsed['transport'];
    if (transport == 'binary') {
      final byteLength = details['byte_length'] ?? parsed['byte_length'];
      final mimeType = details['mime_type'] ?? parsed['mime_type'];
      final audioSequence =
          details['audio_sequence'] ?? parsed['audio_sequence'];
      if (byteLength is! int ||
          byteLength <= 0 ||
          mimeType is! String ||
          mimeType.isEmpty ||
          audioSequence is! int) {
        throw const RealtimeProtocolException(
          'binary audio metadata is incomplete',
        );
      }
      if (_pendingAudio != null) {
        throw const RealtimeProtocolException(
          'previous binary audio is unpaired',
        );
      }
      _pendingAudio = _PendingAudio(
        type: type,
        sequence: sequence,
        turnId: turnId,
        requestId: requestId,
        details: details,
        byteLength: byteLength,
        audioSequence: audioSequence,
      );
    }
    final event = _knownTypes.contains(type)
        ? RealtimeJsonEvent(
            type: type,
            sequence: sequence,
            turnId: turnId,
            requestId: requestId,
            details: details,
          )
        : RealtimeUnknownEvent(
            type: type,
            sequence: sequence,
            turnId: turnId,
            requestId: requestId,
            details: details,
          );
    return event;
  }

  RealtimeAudioEvent decodeBinary(Uint8List bytes) {
    final pending = _pendingAudio;
    if (pending == null) {
      throw const RealtimeProtocolException('unpaired binary audio frame');
    }
    if (bytes.length != pending.byteLength) {
      throw const RealtimeProtocolException(
        'binary audio length does not match metadata',
      );
    }
    _pendingAudio = null;
    return RealtimeAudioEvent(
      type: pending.type,
      sequence: pending.sequence,
      bytes: Uint8List.fromList(bytes),
      turnId: pending.turnId,
      requestId: pending.requestId,
      details: {...pending.details, 'audio_sequence': pending.audioSequence},
    );
  }

  void reset() {
    _lastSequence = 0;
    _pendingAudio = null;
  }

  static const _knownTypes = {
    'session.ready',
    'stream.ready',
    'asr.partial',
    'asr.final',
    'turn.started',
    'llm.delta',
    'tts.audio',
    'tts.audio.chunk',
    'turn.interrupted',
    'turn.completed',
    'turn.cancelled',
    'error',
    'session.expiring',
    'session.expired',
  };
}

class _PendingAudio {
  const _PendingAudio({
    required this.type,
    required this.sequence,
    required this.turnId,
    required this.requestId,
    required this.details,
    required this.byteLength,
    required this.audioSequence,
  });
  final String type;
  final int sequence;
  final String? turnId;
  final String? requestId;
  final Map<String, dynamic> details;
  final int byteLength;
  final int audioSequence;
}
