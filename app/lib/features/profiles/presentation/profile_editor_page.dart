import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/providers/core_providers.dart';
import '../../../core/theme/app_theme.dart';

class ProfileEditorPage extends ConsumerStatefulWidget {
  const ProfileEditorPage({super.key, this.profileId});
  final String? profileId;

  @override
  ConsumerState<ProfileEditorPage> createState() => _ProfileEditorPageState();
}

class _ProfileEditorPageState extends ConsumerState<ProfileEditorPage> {
  late final TextEditingController _name;
  late final TextEditingController _summary;
  late final TextEditingController _personality;
  late final TextEditingController _prompt;
  String _voice = '温柔女声';
  bool _memory = true;
  bool _dirty = false;
  late String _profileId;

  @override
  void initState() {
    super.initState();
    final store = ref.read(companionStoreProvider);
    final existing = widget.profileId == null
        ? null
        : store.profiles
              .where((profile) => profile.id == widget.profileId)
              .firstOrNull;
    _profileId = existing?.id ?? '';
    _name = TextEditingController(text: existing?.name ?? '新角色');
    _summary = TextEditingController(text: existing?.summary ?? '由你亲手定义的陪伴角色');
    _personality = TextEditingController(
      text: existing?.personality ?? '温柔、真诚',
    );
    _prompt = TextEditingController(
      text: existing?.systemPrompt ?? '请以真诚、尊重的方式陪伴用户。',
    );
    _voice = existing?.voice ?? '温柔女声';
    _memory = existing?.memoryEnabled ?? true;
    for (final controller in [_name, _summary, _personality, _prompt]) {
      controller.addListener(() => setState(() => _dirty = true));
    }
  }

  @override
  void dispose() {
    _name.dispose();
    _summary.dispose();
    _personality.dispose();
    _prompt.dispose();
    super.dispose();
  }

  Future<bool> _confirmLeave() async {
    if (!_dirty) return true;
    final result = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('还没有保存'),
        content: const Text('离开后本页的修改会丢失。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('留下'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('放弃修改'),
          ),
        ],
      ),
    );
    return result ?? false;
  }

  void _save() {
    final name = _name.text.trim();
    if (name.isEmpty) return;
    final store = ref.read(companionStoreProvider);
    if (_profileId.isEmpty) {
      store.createProfile(
        name: name,
        personality: _personality.text,
        prompt: _prompt.text,
      );
    } else {
      final current = store.profiles.firstWhere(
        (profile) => profile.id == _profileId,
      );
      store.updateProfile(
        current.copyWith(
          name: name,
          summary: _summary.text.trim(),
          personality: _personality.text.trim(),
          systemPrompt: _prompt.text.trim(),
          voice: _voice,
          memoryEnabled: _memory,
          activeVersionNo: current.activeVersionNo + 1,
        ),
      );
    }
    _dirty = false;
    if (mounted) context.pop();
  }

  @override
  Widget build(BuildContext context) {
    final editing = _profileId.isNotEmpty;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) async {
        if (didPop) return;
        if (await _confirmLeave() && context.mounted) context.pop();
      },
      child: Scaffold(
        appBar: AppBar(
          title: Text(editing ? '编辑角色' : '创建角色'),
          leading: IconButton(
            tooltip: '返回',
            onPressed: () async {
              if (await _confirmLeave() && context.mounted) context.pop();
            },
            icon: const Icon(Icons.arrow_back),
          ),
          actions: [
            TextButton(
              onPressed: _name.text.trim().isEmpty ? null : _save,
              child: const Text('保存'),
            ),
          ],
        ),
        body: ListView(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 36),
          children: [
            _EditorSection(
              title: '身份',
              icon: Icons.badge_outlined,
              children: [
                TextField(
                  controller: _name,
                  decoration: const InputDecoration(
                    labelText: '角色名称',
                    helperText: '这是聊天页和设备上显示的名称',
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _summary,
                  maxLength: 80,
                  decoration: const InputDecoration(labelText: '一句话介绍'),
                ),
              ],
            ),
            _EditorSection(
              title: '声音',
              icon: Icons.record_voice_over_outlined,
              children: [
                DropdownButtonFormField<String>(
                  initialValue: _voice,
                  decoration: const InputDecoration(labelText: '音色'),
                  items: const [
                    DropdownMenuItem(value: '温柔女声', child: Text('温柔女声')),
                    DropdownMenuItem(value: '清澈中性', child: Text('清澈中性')),
                    DropdownMenuItem(value: '沉稳男声', child: Text('沉稳男声')),
                  ],
                  onChanged: (value) => setState(() {
                    _voice = value ?? _voice;
                    _dirty = true;
                  }),
                ),
                const SizedBox(height: 14),
                const Text(
                  '语音参数',
                  style: TextStyle(fontWeight: FontWeight.w700),
                ),
                Row(
                  children: [
                    const Text('语速'),
                    Expanded(
                      child: Slider(
                        value: 1,
                        min: .5,
                        max: 1.5,
                        onChanged: null,
                      ),
                    ),
                    const Text('标准'),
                  ],
                ),
                Row(
                  children: [
                    const Text('音量'),
                    Expanded(
                      child: Slider(
                        value: 1,
                        min: .2,
                        max: 1.2,
                        onChanged: null,
                      ),
                    ),
                    const Text('标准'),
                  ],
                ),
              ],
            ),
            _EditorSection(
              title: '性格与提示词',
              icon: Icons.psychology_outlined,
              children: [
                TextField(
                  controller: _personality,
                  maxLines: 2,
                  decoration: const InputDecoration(labelText: '性格'),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _prompt,
                  maxLines: 6,
                  decoration: const InputDecoration(
                    labelText: '陪伴提示词',
                    alignLabelWithHint: true,
                  ),
                ),
              ],
            ),
            _EditorSection(
              title: '能力',
              icon: Icons.extension_outlined,
              children: const [
                _CapabilityRow(
                  label: '情绪感知',
                  description: '识别对话中的情绪变化',
                  enabled: true,
                ),
                _CapabilityRow(
                  label: '天气',
                  description: '回答指定城市的天气',
                  enabled: true,
                ),
                _CapabilityRow(
                  label: '联网搜索',
                  description: '在需要时查询公开信息',
                  enabled: false,
                ),
              ],
            ),
            _EditorSection(
              title: '长期记忆',
              icon: Icons.memory_outlined,
              children: [
                SwitchListTile.adaptive(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('允许角色记住重要信息'),
                  subtitle: const Text('关闭后不会召回或自动新增，已有内容仍可管理'),
                  value: _memory,
                  onChanged: (value) => setState(() {
                    _memory = value;
                    _dirty = true;
                  }),
                ),
                if (editing)
                  OutlinedButton.icon(
                    onPressed: () => _showMemory(context),
                    icon: const Icon(Icons.manage_search),
                    label: const Text('管理这个角色的记忆'),
                  ),
              ],
            ),
            if (editing)
              TextButton.icon(
                onPressed: () => _deleteProfile(context),
                icon: const Icon(Icons.delete_outline),
                label: const Text('删除角色'),
                style: TextButton.styleFrom(
                  foregroundColor: Theme.of(context).colorScheme.error,
                ),
              ),
          ],
        ),
      ),
    );
  }

  void _showMemory(BuildContext context) {
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (_) => const _MemorySheet(),
    );
  }

  Future<void> _deleteProfile(BuildContext context) async {
    final store = ref.read(companionStoreProvider);
    final profile = store.profiles.firstWhere((item) => item.id == _profileId);
    if (profile.boundDeviceCount > 0) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('角色仍绑定设备，暂时不能删除')));
      return;
    }
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('删除角色？'),
        content: const Text('历史会话会保留角色名称，角色记忆也不会被自动删除。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (confirmed == true) {
      store.updateProfile(profile.copyWith(deleted: true));
      if (context.mounted) context.pop();
    }
  }
}

class _EditorSection extends StatelessWidget {
  const _EditorSection({
    required this.title,
    required this.icon,
    required this.children,
  });
  final String title;
  final IconData icon;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 14),
    child: Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(15, 14, 15, 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon, size: 19, color: AppTheme.accentDark),
                const SizedBox(width: 8),
                Text(
                  title,
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 14),
            ...children,
          ],
        ),
      ),
    ),
  );
}

class _CapabilityRow extends StatefulWidget {
  const _CapabilityRow({
    required this.label,
    required this.description,
    required this.enabled,
  });
  final String label;
  final String description;
  final bool enabled;

  @override
  State<_CapabilityRow> createState() => _CapabilityRowState();
}

class _CapabilityRowState extends State<_CapabilityRow> {
  late bool value = widget.enabled;
  @override
  Widget build(BuildContext context) => SwitchListTile.adaptive(
    contentPadding: EdgeInsets.zero,
    title: Text(widget.label),
    subtitle: Text(widget.description, style: const TextStyle(fontSize: 12)),
    value: value,
    onChanged: (next) => setState(() => value = next),
  );
}

class _MemorySheet extends StatelessWidget {
  const _MemorySheet();
  @override
  Widget build(BuildContext context) => SafeArea(
    child: Padding(
      padding: const EdgeInsets.fromLTRB(20, 4, 20, 28),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            '记忆管理',
            style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 8),
          const Text(
            '这里会展示这个角色从互动中整理出的长期记忆。',
            style: TextStyle(color: AppTheme.mutedInk),
          ),
          const SizedBox(height: 18),
          ListTile(
            leading: const Icon(Icons.bookmark_outline),
            title: const Text('你喜欢在周末散步'),
            subtitle: const Text('最近更新 · 由对话自动整理'),
            trailing: IconButton(
              tooltip: '删除记忆',
              onPressed: () {},
              icon: const Icon(Icons.delete_outline),
            ),
          ),
          ListTile(
            leading: const Icon(Icons.bookmark_outline),
            title: const Text('偏好简短、直接的建议'),
            subtitle: const Text('最近更新 · 由对话自动整理'),
            trailing: IconButton(
              tooltip: '删除记忆',
              onPressed: () {},
              icon: const Icon(Icons.delete_outline),
            ),
          ),
          OutlinedButton.icon(
            onPressed: () {},
            icon: const Icon(Icons.delete_sweep_outlined),
            label: const Text('清空全部记忆'),
          ),
        ],
      ),
    ),
  );
}
