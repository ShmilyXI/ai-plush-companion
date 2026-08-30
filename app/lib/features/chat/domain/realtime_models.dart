import 'dart:typed_data';

sealed class RealtimeEvent {
  const RealtimeEvent({
    required this.type,
    required this.sequence,
    this.turnId,
    this.requestId,
    this.details = const {},
  });
  final String type;
  final int sequence;
  final String? turnId;
  final String? requestId;
  final Map<String, dynamic> details;
}

class RealtimeJsonEvent extends RealtimeEvent {
  const RealtimeJsonEvent({
    required super.type,
    required super.sequence,
    super.turnId,
    super.requestId,
    super.details,
  });
}

class RealtimeAudioEvent extends RealtimeEvent {
  const RealtimeAudioEvent({
    required super.type,
    required super.sequence,
    required this.bytes,
    super.turnId,
    super.requestId,
    super.details,
  });
  final Uint8List bytes;
}

class RealtimeUnknownEvent extends RealtimeJsonEvent {
  const RealtimeUnknownEvent({
    required super.type,
    required super.sequence,
    super.turnId,
    super.requestId,
    super.details,
  });
}

class RealtimeProtocolException implements Exception {
  const RealtimeProtocolException(this.message);
  final String message;
  @override
  String toString() => 'RealtimeProtocolException: $message';
}
