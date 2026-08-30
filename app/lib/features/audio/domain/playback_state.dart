enum PlaybackStatus { idle, loading, playing, paused, completed, failed }

class PlaybackItem {
  const PlaybackItem({
    required this.id,
    required this.bytes,
    required this.mimeType,
    this.sourceUrl,
  });
  final String id;
  final List<int> bytes;
  final String mimeType;
  final String? sourceUrl;
}
