import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/providers/core_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../domain/chat_models.dart';
import '../../profiles/presentation/profile_selector_drawer.dart';
import 'message_composer.dart';
import 'session_drawer.dart';

class ChatPage extends ConsumerWidget {
  const ChatPage({super.key});

  void _openLeftDrawer(BuildContext context) {
    showGeneralDialog<void>(
      context: context,
      barrierDismissible: true,
      barrierLabel: '关闭会话列表',
      barrierColor: Colors.black.withValues(alpha: .28),
      transitionDuration: const Duration(milliseconds: 220),
      pageBuilder: (_, __, ___) =>
          const Align(alignment: Alignment.centerLeft, child: SessionDrawer()),
      transitionBuilder: (_, animation, __, child) => SlideTransition(
        position: Tween(begin: const Offset(-1, 0), end: Offset.zero).animate(
          CurvedAnimation(parent: animation, curve: Curves.easeOutCubic),
        ),
        child: child,
      ),
    );
  }

  void _openRightDrawer(BuildContext context) {
    showGeneralDialog<void>(
      context: context,
      barrierDismissible: true,
      barrierLabel: '关闭角色选择',
      barrierColor: Colors.black.withValues(alpha: .28),
      transitionDuration: const Duration(milliseconds: 220),
      pageBuilder: (_, __, ___) => const Align(
        alignment: Alignment.centerRight,
        child: ProfileSelectorDrawer(),
      ),
      transitionBuilder: (_, animation, __, child) => SlideTransition(
        position: Tween(begin: const Offset(1, 0), end: Offset.zero).animate(
          CurvedAnimation(parent: animation, curve: Curves.easeOutCubic),
        ),
        child: child,
      ),
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final store = ref.watch(companionStoreProvider);
    final chat = ref.watch(chatControllerProvider);
    final conversation = store.currentConversation;
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(10, 8, 10, 4),
          child: Row(
            children: [
              IconButton(
                tooltip: '展开会话列表',
                onPressed: () => _openLeftDrawer(context),
                icon: const Icon(Icons.menu),
              ),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      store.selectedProfile.name,
                      style: const TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w800,
                        color: AppTheme.ink,
                      ),
                    ),
                    Text(
                      conversation.title,
                      style: const TextStyle(
                        fontSize: 12,
                        color: AppTheme.mutedInk,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
              IconButton(
                tooltip: '语音通话',
                onPressed: () => context.push('/call'),
                icon: const Icon(Icons.phone_in_talk_outlined),
              ),
              IconButton(
                tooltip: '选择陪伴角色',
                onPressed: () => _openRightDrawer(context),
                icon: const Icon(Icons.auto_awesome),
              ),
            ],
          ),
        ),
        Container(
          margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
          decoration: BoxDecoration(
            color: AppTheme.sage,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Row(
            children: [
              const Icon(
                Icons.lock_outline,
                size: 15,
                color: AppTheme.mutedInk,
              ),
              const SizedBox(width: 7),
              Expanded(
                child: Text(
                  store.selectedProfile.memoryEnabled
                      ? '长期记忆已开启 · AI 陪伴在线'
                      : '仅在当前对话中记忆 · AI 陪伴在线',
                  style: const TextStyle(
                    fontSize: 12,
                    color: AppTheme.mutedInk,
                  ),
                ),
              ),
              Text(
                store.autoPlay ? '自动播放' : '手动播放',
                style: const TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                  color: AppTheme.ink,
                ),
              ),
            ],
          ),
        ),
        if (chat.error != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 2, 16, 4),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text(
                chat.error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
          ),
        Expanded(
          child: conversation.messages.isEmpty
              ? _EmptyChat(profileName: store.selectedProfile.name)
              : ListView.builder(
                  padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
                  reverse: false,
                  itemCount: conversation.messages.length,
                  itemBuilder: (context, index) => _MessageBubble(
                    message: conversation.messages[index],
                    onAudio: () => unawaited(
                      ref
                          .read(chatControllerProvider)
                          .toggleAudio(conversation.messages[index].id),
                    ),
                  ),
                ),
        ),
        MessageComposer(audioSender: chat.voiceSender),
      ],
    );
  }
}

class _EmptyChat extends StatelessWidget {
  const _EmptyChat({required this.profileName});
  final String profileName;

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(28),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 76,
            height: 76,
            decoration: const BoxDecoration(
              color: AppTheme.sage,
              shape: BoxShape.circle,
            ),
            child: const Icon(
              Icons.auto_awesome,
              size: 34,
              color: AppTheme.accentDark,
            ),
          ),
          const SizedBox(height: 18),
          Text(
            '和$profileName聊聊',
            style: const TextStyle(
              fontSize: 22,
              fontWeight: FontWeight.w800,
              color: AppTheme.ink,
            ),
          ),
          const SizedBox(height: 7),
          const Text(
            '今天发生了什么？从一句话开始就好。',
            textAlign: TextAlign.center,
            style: TextStyle(color: AppTheme.mutedInk),
          ),
        ],
      ),
    ),
  );
}

class _MessageBubble extends StatelessWidget {
  const _MessageBubble({required this.message, required this.onAudio});
  final ChatMessage message;
  final VoidCallback onAudio;

  @override
  Widget build(BuildContext context) {
    final isUser = message.author.toString().endsWith('user');
    return Align(
      alignment: isUser ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        constraints: BoxConstraints(
          maxWidth: MediaQuery.sizeOf(context).width * .82,
        ),
        margin: const EdgeInsets.only(bottom: 12),
        padding: const EdgeInsets.fromLTRB(15, 12, 10, 10),
        decoration: BoxDecoration(
          color: isUser ? AppTheme.ink : AppTheme.surface,
          borderRadius: BorderRadius.only(
            topLeft: const Radius.circular(17),
            topRight: const Radius.circular(17),
            bottomLeft: Radius.circular(isUser ? 17 : 5),
            bottomRight: Radius.circular(isUser ? 5 : 17),
          ),
          border: isUser ? null : Border.all(color: const Color(0xFFE1E7E2)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Align(
              alignment: Alignment.centerLeft,
              child: Text(
                message.text,
                style: TextStyle(
                  color: isUser ? Colors.white : AppTheme.ink,
                  height: 1.45,
                  fontSize: 15,
                ),
              ),
            ),
            if (!isUser && message.hasAudio)
              Padding(
                padding: const EdgeInsets.only(top: 5),
                child: IconButton(
                  visualDensity: VisualDensity.compact,
                  tooltip: message.audioPlaying ? '暂停语音' : '播放语音',
                  onPressed: onAudio,
                  icon: Icon(
                    message.audioPlaying
                        ? Icons.pause_circle
                        : Icons.volume_up_outlined,
                    size: 20,
                    color: AppTheme.accentDark,
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
