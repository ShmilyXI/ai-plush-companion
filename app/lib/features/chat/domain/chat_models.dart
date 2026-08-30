enum MessageAuthor { user, assistant, system }

class ChatMessage {
  const ChatMessage({
    required this.id,
    required this.text,
    required this.author,
    required this.createdAt,
    this.hasAudio = false,
    this.audioPlaying = false,
  });

  final String id;
  final String text;
  final MessageAuthor author;
  final DateTime createdAt;
  final bool hasAudio;
  final bool audioPlaying;

  ChatMessage copyWith({bool? hasAudio, bool? audioPlaying}) => ChatMessage(
    id: id,
    text: text,
    author: author,
    createdAt: createdAt,
    hasAudio: hasAudio ?? this.hasAudio,
    audioPlaying: audioPlaying ?? this.audioPlaying,
  );
}

class CompanionConversation {
  CompanionConversation({
    required this.id,
    required this.profileId,
    required this.title,
    required this.updatedAt,
    required List<ChatMessage> messages,
    this.source = 'app',
  }) : messages = List.unmodifiable(messages);

  final String id;
  final String profileId;
  final String title;
  final DateTime updatedAt;
  final List<ChatMessage> messages;
  final String source;

  CompanionConversation copyWith({
    String? title,
    DateTime? updatedAt,
    List<ChatMessage>? messages,
  }) => CompanionConversation(
    id: id,
    profileId: profileId,
    title: title ?? this.title,
    updatedAt: updatedAt ?? this.updatedAt,
    messages: messages ?? this.messages,
    source: source,
  );
}
