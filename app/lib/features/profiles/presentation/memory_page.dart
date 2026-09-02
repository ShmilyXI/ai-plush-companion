import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/providers/core_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../data/profile_repository.dart';

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
  bool _loading = false;
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    if (!ref.read(appConfigProvider).isDemo) {
      _items.clear();
      unawaited(_load());
    }
  }

  Future<void> _load() async {
    if (mounted) setState(() => _loading = true);
    try {
      final view = await ref
          .read(profileMemoryRepositoryProvider)
          .list(widget.profileId);
      if (!mounted) return;
      setState(() {
        _items
          ..clear()
          ..addAll(view.items.map(_MemoryDraft.fromItem));
        _loading = false;
        _error = null;
      });
      final profile = ref
          .read(companionStoreProvider)
          .profiles
          .where((item) => item.id == widget.profileId)
          .firstOrNull;
      if (profile != null && profile.memoryEnabled != view.enabled) {
        ref
            .read(companionStoreProvider)
            .toggleMemory(widget.profileId, view.enabled);
      }
    } catch (error) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = _errorText(error);
        });
      }
    }
  }

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
              onChanged: _busy
                  ? null
                  : (value) => _toggleMemory(profile.id, value),
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
          if (_loading)
            const Padding(
              padding: EdgeInsets.all(24),
              child: Center(child: CircularProgressIndicator()),
            )
          else if (_error != null)
            Card(
              child: ListTile(
                title: const Text('记忆加载失败'),
                subtitle: Text(_error!),
                trailing: TextButton(onPressed: _load, child: const Text('重试')),
              ),
            )
          else if (_items.isEmpty)
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
                  subtitle: Text('${item.updatedAt} · ${item.sourceLabel}'),
                  trailing: PopupMenuButton<String>(
                    tooltip: '记忆操作',
                    enabled: !_busy,
                    onSelected: (action) {
                      if (action == 'delete') unawaited(_delete(item));
                      if (action == 'edit') unawaited(_edit(item));
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
            onPressed: _items.isEmpty || _busy ? null : _clear,
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

  Future<void> _toggleMemory(String profileId, bool value) async {
    final store = ref.read(companionStoreProvider);
    if (ref.read(appConfigProvider).isDemo) {
      store.toggleMemory(profileId, value);
      return;
    }
    setState(() => _busy = true);
    try {
      await ref
          .read(profileRepositoryProvider)
          .setMemoryEnabled(profileId, value);
      if (mounted) store.toggleMemory(profileId, value);
    } catch (error) {
      if (mounted) _showError(_errorText(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
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
    final content = value?.trim() ?? '';
    if (content.isEmpty || !mounted) return;
    setState(() => _busy = true);
    try {
      if (!ref.read(appConfigProvider).isDemo) {
        await ref
            .read(profileMemoryRepositoryProvider)
            .update(widget.profileId, item.id, content);
      }
      if (mounted) {
        setState(() {
          item.content = content;
          item.updatedAt = '刚刚';
        });
      }
    } catch (error) {
      if (mounted) _showError(_errorText(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _delete(_MemoryDraft item) async {
    setState(() => _busy = true);
    try {
      if (!ref.read(appConfigProvider).isDemo) {
        await ref
            .read(profileMemoryRepositoryProvider)
            .delete(widget.profileId, item.id);
      }
      if (mounted) setState(() => _items.remove(item));
    } catch (error) {
      if (mounted) _showError(_errorText(error));
    } finally {
      if (mounted) setState(() => _busy = false);
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
    if (yes != true || !mounted) return;
    setState(() => _busy = true);
    try {
      if (!ref.read(appConfigProvider).isDemo) {
        await ref.read(profileMemoryRepositoryProvider).clear(widget.profileId);
      }
      if (mounted) setState(_items.clear);
    } catch (error) {
      if (mounted) _showError(_errorText(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _showError(String message) {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }

  String _errorText(Object error) {
    final text = error.toString();
    return text.startsWith('ApiException(') ? '操作失败，请稍后重试' : text;
  }
}

class _MemoryDraft {
  _MemoryDraft(
    this.id,
    this.content,
    this.updatedAt, [
    this.sourceLabel = '由对话整理',
  ]);

  factory _MemoryDraft.fromItem(ProfileMemoryItem item) => _MemoryDraft(
    item.id,
    item.content,
    item.updatedAt?.toLocal().toString() ?? '最近更新',
    item.sourceDeviceName == null ? '由对话整理' : '来自${item.sourceDeviceName}',
  );

  final String id;
  String content;
  String updatedAt;
  String sourceLabel;
}
