enum CallState {
  idle,
  preparing,
  connecting,
  listening,
  thinking,
  speaking,
  muted,
  reconnecting,
  ending,
  ended,
  failed,
}

class CallSnapshot {
  const CallSnapshot({
    required this.state,
    required this.elapsed,
    this.transcript = '',
    this.error,
  });
  final CallState state;
  final Duration elapsed;
  final String transcript;
  final String? error;
}
