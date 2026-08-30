import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/providers/core_providers.dart';
import '../../../core/theme/app_theme.dart';

class SessionDrawer extends ConsumerWidget {
  const SessionDrawer({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final store = ref.watch(companionStoreProvider);
    final sessions = store.selectedProfileConversations;
    return Material(
      color: AppTheme.canvas,
      child: SafeArea(
        child: SizedBox(
          width: MediaQuery.sizeOf(context).width * .84,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 18, 14, 12),
                child: Row(
                  children: [
                    const Text(
                      '会话',
                      style: TextStyle(
                        fontSize: 22,
                        fontWeight: FontWeight.w800,
                        color: AppTheme.ink,
                      ),
                    ),
                    const Spacer(),
                    IconButton(
                      tooltip: '关闭会话列表',
                      onPressed: () => Navigator.of(context).pop(),
                      icon: const Icon(Icons.close),
                    ),
                  ],
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: FilledButton.icon(
                  onPressed: () {
                    ref.read(companionStoreProvider).newConversation();
                    Navigator.of(context).pop();
                  },
                  icon: const Icon(Icons.add, size: 19),
                  label: const Text('开启新会话'),
                ),
              ),
              const SizedBox(height: 16),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                child: Text(
                  '${store.selectedProfile.name}的对话',
                  style: const TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                    color: AppTheme.mutedInk,
                  ),
                ),
              ),
              const SizedBox(height: 6),
              Expanded(
                child: sessions.isEmpty
                    ? const Center(
                        child: Text(
                          '还没有会话',
                          style: TextStyle(color: AppTheme.mutedInk),
                        ),
                      )
                    : ListView.separated(
                        padding: const EdgeInsets.fromLTRB(12, 4, 12, 24),
                        itemCount: sessions.length,
                        separatorBuilder: (_, __) => const SizedBox(height: 3),
                        itemBuilder: (context, index) {
                          final session = sessions[index];
                          final selected =
                              session.id == store.currentConversationId;
                          return ListTile(
                            selected: selected,
                            selectedTileColor: AppTheme.sage,
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(12),
                            ),
                            leading: Icon(
                              selected
                                  ? Icons.chat_bubble
                                  : Icons.chat_bubble_outline,
                              size: 19,
                            ),
                            title: Text(
                              session.title,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                            subtitle: Text(
                              _relative(session.updatedAt),
                              style: const TextStyle(fontSize: 12),
                            ),
                            trailing: PopupMenuButton<String>(
                              tooltip: '会话操作',
                              onSelected: (action) async {
                                if (action == 'delete') {
                                  ref
                                      .read(companionStoreProvider)
                                      .deleteConversation(session.id);
                                } else if (action == 'rename') {
                                  final controller = TextEditingController(
                                    text: session.title,
                                  );
                                  final value = await showDialog<String>(
                                    context: context,
                                    builder: (dialogContext) => AlertDialog(
                                      title: const Text('重命名会话'),
                                      content: TextField(
                                        controller: controller,
                                        autofocus: true,
                                        maxLength: 80,
                                      ),
                                      actions: [
                                        TextButton(
                                          onPressed: () =>
                                              Navigator.pop(dialogContext),
                                          child: const Text('取消'),
                                        ),
                                        FilledButton(
                                          onPressed: () => Navigator.pop(
                                            dialogContext,
                                            controller.text,
                                          ),
                                          child: const Text('保存'),
                                        ),
                                      ],
                                    ),
                                  );
                                  if (value != null) {
                                    ref
                                        .read(companionStoreProvider)
                                        .renameConversation(session.id, value);
                                  }
                                }
                              },
                              itemBuilder: (_) => const [
                                PopupMenuItem(
                                  value: 'rename',
                                  child: Text('重命名'),
                                ),
                                PopupMenuItem(
                                  value: 'delete',
                                  child: Text('删除会话'),
                                ),
                              ],
                            ),
                            onTap: () {
                              ref
                                  .read(companionStoreProvider)
                                  .selectConversation(session.id);
                              Navigator.of(context).pop();
                            },
                          );
                        },
                      ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  String _relative(DateTime value) {
    final age = DateTime.now().difference(value);
    if (age.inMinutes < 1) return '刚刚';
    if (age.inHours < 1) return '${age.inMinutes} 分钟前';
    if (age.inDays < 1) return '${age.inHours} 小时前';
    return '${age.inDays} 天前';
  }
}
