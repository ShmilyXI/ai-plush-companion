import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/providers/core_providers.dart';
import '../../../core/theme/app_theme.dart';

class MemoryPage extends ConsumerStatefulWidget {
  const MemoryPage({super.key, required this.profileId});
  final String profileId;

  @override
  ConsumerState<MemoryPage> createState() => _MemoryPageState();
}

class _MemoryPageState extends ConsumerState<MemoryPage> {
  final _items = <_MemoryDraft>[
    _MemoryDraft('m1', '你喜欢在周末散步', '最近更新'),
    _MemoryDraft('m2', '你偏好简短、直接的建议', '最近更新'),
  ];

  @override
  Widget build(BuildContext context) {
    final store = ref.watch(companionStoreProvider);
    final profile = store.profiles.firstWhere(
      (item) => item.id == widget.profileId,
      orElse: () => store.selectedProfile,
    );
    return Scaffold(
      appBar: AppBar(title: Text('${profile.name}的记忆')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 10, 16, 30),
        children: [
          Card(
            child: SwitchListTile.adaptive(
              contentPadding: const EdgeInsets.fromLTRB(16, 4, 10, 4),
              title: const Text(
                '允许 AI 使用长期记忆',
                style: TextStyle(fontWeight: FontWeight.w700),
              ),
              subtitle: Text(
                profile.memoryEnabled ? '新对话会召回并整理记忆' : '关闭后只保留当前会话上下文',
              ),
              value: profile.memoryEnabled,
              onChanged: (value) => ref
                  .read(companionStoreProvider)
                  .toggleMemory(profile.id, value),
            ),
          ),
          const SizedBox(height: 18),
          Row(
            children: [
              const Text(
                '已保存的记忆',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800),
              ),
              const Spacer(),
              Text(
                '${_items.length} 条',
                style: const TextStyle(color: AppTheme.mutedInk, fontSize: 13),
              ),
            ],
          ),
          const SizedBox(height: 9),
          if (_items.isEmpty)
            const Card(
              child: Padding(
                padding: EdgeInsets.all(22),
                child: Text('还没有整理出的记忆。'),
              ),
            )
          else
            ..._items.map(
              (item) => Card(
                margin: const EdgeInsets.only(bottom: 9),
                child: ListTile(
                  leading: const Icon(
                    Icons.bookmark_outline,
                    color: AppTheme.accentDark,
                  ),
                  title: Text(item.content),
                  subtitle: Text('${item.updatedAt} · 由对话整理'),
                  trailing: PopupMenuButton<String>(
                    tooltip: '记忆操作',
                    onSelected: (action) {
                      if (action == 'delete') {
                        setState(() => _items.remove(item));
                      }
                      if (action == 'edit') {
                        _edit(item);
                      }
                    },
                    itemBuilder: (_) => const [
                      PopupMenuItem(value: 'edit', child: Text('编辑')),
                      PopupMenuItem(value: 'delete', child: Text('删除')),
                    ],
                  ),
                ),
              ),
            ),
          const SizedBox(height: 12),
          OutlinedButton.icon(
            onPressed: _items.isEmpty ? null : _clear,
            icon: const Icon(Icons.delete_sweep_outlined),
            label: const Text('清空全部记忆'),
          ),
          const SizedBox(height: 8),
          const Text(
            '关闭开关不会删除已有记忆。你仍然可以在这里编辑或清空。',
            style: TextStyle(color: AppTheme.mutedInk, fontSize: 12),
          ),
        ],
      ),
    );
  }

  Future<void> _edit(_MemoryDraft item) async {
    final controller = TextEditingController(text: item.content);
    final value = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('编辑记忆'),
        content: TextField(controller: controller, maxLines: 3),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, controller.text),
            child: const Text('保存'),
          ),
        ],
      ),
    );
    controller.dispose();
    if (value != null && value.trim().isNotEmpty && mounted) {
      setState(() => item.content = value.trim());
    }
  }

  Future<void> _clear() async {
    final yes = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('清空全部记忆？'),
        content: const Text('这不会删除聊天记录。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('清空'),
          ),
        ],
      ),
    );
    if (yes == true && mounted) setState(_items.clear);
  }
}

class _MemoryDraft {
  _MemoryDraft(this.id, this.content, this.updatedAt);
  final String id;
  String content;
  final String updatedAt;
}
