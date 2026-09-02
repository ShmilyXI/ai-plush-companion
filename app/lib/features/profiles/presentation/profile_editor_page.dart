import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/providers/core_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../domain/profile_models.dart';

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
  late final TextEditingController _address;
  late final TextEditingController _voiceIdController;
  late final TextEditingController _llmModelController;
  late final TextEditingController _ttsModelController;
  String _voice = '温柔女声';
  String? _voiceId;
  String _relationMode = 'friend';
  String _userAddress = '';
  double _ttsVolume = 1;
  double _ttsRate = 1;
  double _ttsPitch = 1;
  bool _chatHistoryTextOnly = true;
  int _boundDeviceCount = 0;
  final Map<String, bool> _capabilities = <String, bool>{
    'emotion': true,
    'weather': true,
    'web_search': false,
  };
  final Map<String, bool> _capabilityAvailability = <String, bool>{
    'emotion': true,
    'weather': true,
    'web_search': false,
  };
  List<Map<String, dynamic>> _capabilityOptions = const [];
  bool _memory = true;
  bool _dirty = false;
  bool _saving = false;
  bool _loadingTemplates = false;
  String? _error;
  List<Map<String, dynamic>> _templates = const [];
  List<Map<String, dynamic>> _voiceOptions = const [];
  String? _selectedTemplateId;
  int _voiceOptionsRequest = 0;
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
    _profileId = existing?.id ?? widget.profileId ?? '';
    _name = TextEditingController(text: existing?.name ?? '新角色');
    _summary = TextEditingController(text: existing?.summary ?? '由你亲手定义的陪伴角色');
    _personality = TextEditingController(
      text: existing?.personality ?? '温柔、真诚',
    );
    _prompt = TextEditingController(
      text: existing?.systemPrompt ?? '请以真诚、尊重的方式陪伴用户。',
    );
    _address = TextEditingController(text: existing?.userAddress ?? '');
    _voiceIdController = TextEditingController(
      text: existing?.ttsVoiceId ?? '',
    );
    _llmModelController = TextEditingController(
      text: existing?.llmModelId ?? '',
    );
    _ttsModelController = TextEditingController(
      text: existing?.ttsModelId ?? '',
    );
    _voice = existing?.voice ?? '温柔女声';
    _voiceId = existing?.ttsVoiceId;
    _relationMode = existing?.relationMode ?? 'friend';
    _userAddress = existing?.userAddress ?? '';
    _ttsVolume = existing?.ttsVolume ?? 1;
    _ttsRate = existing?.ttsRate ?? 1;
    _ttsPitch = existing?.ttsPitch ?? 1;
    _chatHistoryTextOnly = existing?.chatHistoryTextOnly ?? true;
    _boundDeviceCount = existing?.boundDeviceCount ?? 0;
    _memory = existing?.memoryEnabled ?? true;
    for (final controller in [
      _name,
      _summary,
      _personality,
      _prompt,
      _address,
      _voiceIdController,
      _llmModelController,
      _ttsModelController,
    ]) {
      controller.addListener(() => setState(() => _dirty = true));
    }
    if (_profileId.isNotEmpty && !ref.read(appConfigProvider).isDemo) {
      // The list response can omit fields needed by the editor. Fill the
      // draft from the authoritative detail response without changing the
      // active store until the user explicitly saves.
      _loadRemote(_profileId);
    } else if (_profileId.isEmpty && !ref.read(appConfigProvider).isDemo) {
      _loadTemplates();
    }
  }

  @override
  void dispose() {
    _name.dispose();
    _summary.dispose();
    _personality.dispose();
    _prompt.dispose();
    _address.dispose();
    _voiceIdController.dispose();
    _llmModelController.dispose();
    _ttsModelController.dispose();
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

  Future<void> _loadRemote(String id) async {
    try {
      final map = await ref.read(profileRepositoryProvider).getProfile(id);
      final profile = CompanionProfile.fromMap(map);
      List<Map<String, dynamic>> options = const [];
      try {
        options = await ref
            .read(profileRepositoryProvider)
            .listCapabilityOptions(id);
      } catch (_) {
        // Older server versions may not expose the capability catalog.
      }
      List<Map<String, dynamic>> voices = const [];
      try {
        voices = await ref
            .read(profileRepositoryProvider)
            .listVoiceOptions(profile.ttsModelId ?? '');
      } catch (_) {
        // Older manager-api versions may not expose the public voice catalog.
      }
      if (!mounted) return;
      if (_dirty) return;
      setState(() {
        _profileId = profile.id;
        _name.text = profile.name;
        _summary.text = profile.summary;
        _personality.text = profile.personality;
        _prompt.text = profile.systemPrompt;
        _voice = profile.voice;
        _voiceId = profile.ttsVoiceId;
        _voiceIdController.text = profile.ttsVoiceId ?? '';
        _llmModelController.text = profile.llmModelId ?? '';
        _ttsModelController.text = profile.ttsModelId ?? '';
        _relationMode = profile.relationMode;
        _userAddress = profile.userAddress ?? '';
        _address.text = _userAddress;
        _ttsVolume = profile.ttsVolume;
        _ttsRate = profile.ttsRate;
        _ttsPitch = profile.ttsPitch;
        _chatHistoryTextOnly = profile.chatHistoryTextOnly;
        _boundDeviceCount = profile.boundDeviceCount;
        _memory = profile.memoryEnabled;
        _capabilities
          ..clear()
          ..addEntries(profile.capabilities.map((id) => MapEntry(id, true)));
        _capabilityOptions = options;
        _voiceOptions = voices;
        for (final option in options) {
          final optionId = option['id']?.toString();
          if (optionId != null && optionId.isNotEmpty) {
            final available = _asBool(option['enabled'], fallback: true);
            _capabilityAvailability[optionId] = available;
            _capabilities[optionId] =
                available && profile.capabilities.contains(optionId);
          }
        }
        _dirty = false;
      });
    } catch (error) {
      if (mounted) setState(() => _error = _errorText(error));
    }
  }

  Future<void> _loadTemplates() async {
    if (mounted) setState(() => _loadingTemplates = true);
    try {
      final templates = await ref
          .read(profileRepositoryProvider)
          .listTemplates();
      if (!mounted) return;
      setState(() {
        _templates = templates;
        _selectedTemplateId = '';
        _loadingTemplates = false;
      });
    } catch (_) {
      if (mounted) setState(() => _loadingTemplates = false);
    }
  }

  Future<void> _save() async {
    final name = _name.text.trim();
    if (name.isEmpty || _saving) return;
    final store = ref.read(companionStoreProvider);
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      if (ref.read(appConfigProvider).isDemo) {
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
          store.updateProfile(_draftFrom(current));
        }
      } else {
        final creating = _profileId.isEmpty;
        var id = _profileId;
        if (id.isEmpty) {
          id = _selectedTemplateId == null || _selectedTemplateId!.isEmpty
              ? await ref.read(profileRepositoryProvider).createCustom(name)
              : await ref
                    .read(profileRepositoryProvider)
                    .createFromTemplate(_selectedTemplateId!, name);
        }
        var current = store.profiles.where((item) => item.id == id).firstOrNull;
        if (current == null) {
          try {
            current = CompanionProfile.fromMap(
              await ref.read(profileRepositoryProvider).getProfile(id),
            );
          } catch (_) {
            // The create endpoint may be backed by an older API that does
            // not expose an immediate detail response; local defaults below
            // still let the user finish the first save.
          }
        }
        if (current != null) {
          _voiceId ??= current.ttsVoiceId;
          if (_voiceIdController.text.trim().isEmpty) {
            _voiceIdController.text = current.ttsVoiceId ?? '';
          }
          if (_llmModelController.text.trim().isEmpty) {
            _llmModelController.text = current.llmModelId ?? '';
          }
          if (_ttsModelController.text.trim().isEmpty) {
            _ttsModelController.text = current.ttsModelId ?? '';
          }
        }
        final draft = _draftFrom(
          current ??
              CompanionProfile(
                id: id,
                name: name,
                summary: _summary.text.trim(),
                personality: _personality.text.trim(),
                systemPrompt: _prompt.text.trim(),
                voice: _voice,
                capabilities: const <String>{},
                memoryEnabled: _memory,
                source: ProfileSource.custom,
              ),
        );
        await ref
            .read(profileRepositoryProvider)
            .saveAndActivate(id, draft.toSavePayload());
        var effective = draft;
        try {
          effective = CompanionProfile.fromMap(
            await ref.read(profileRepositoryProvider).getProfile(id),
          );
        } catch (_) {
          // A successful save is still usable when the follow-up detail
          // request is temporarily unavailable.
        }
        store.updateProfile(effective);
        if (creating) store.selectProfile(id);
        _profileId = id;
      }
      if (!mounted) return;
      _dirty = false;
      context.pop();
    } catch (error) {
      if (mounted) setState(() => _error = _errorText(error));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  CompanionProfile _draftFrom(CompanionProfile current) => current.copyWith(
    name: _name.text.trim(),
    summary: _summary.text.trim(),
    personality: _personality.text.trim(),
    systemPrompt: _prompt.text.trim(),
    voice: _voice,
    ttsVoiceId: _voiceIdController.text.trim().isEmpty
        ? null
        : _voiceIdController.text.trim(),
    clearTtsVoiceId: _voiceIdController.text.trim().isEmpty,
    llmModelId: _llmModelController.text.trim(),
    ttsModelId: _ttsModelController.text.trim(),
    models: _modelBindingsFor(current),
    skills: _skillBindingsFor(current),
    relationMode: _relationMode,
    userAddress: _userAddress.trim(),
    ttsVolume: _ttsVolume,
    ttsRate: _ttsRate,
    ttsPitch: _ttsPitch,
    chatHistoryTextOnly: _chatHistoryTextOnly,
    memoryEnabled: _memory,
    capabilities: _capabilities.entries
        .where((entry) => entry.value)
        .map((entry) => entry.key)
        .toSet(),
    activeVersionNo: current.activeVersionNo + 1,
  );

  List<ProfileModelBinding> _modelBindingsFor(CompanionProfile current) {
    var bindings = List<ProfileModelBinding>.of(current.models);
    bindings = _replaceModelBinding(
      bindings,
      'LLM',
      _llmModelController.text.trim(),
      current.llmModelId,
    );
    bindings = _replaceModelBinding(
      bindings,
      'TTS',
      _ttsModelController.text.trim(),
      current.ttsModelId,
    );
    return bindings;
  }

  Future<void> _loadVoiceOptions(String modelId) async {
    final request = ++_voiceOptionsRequest;
    final clean = modelId.trim();
    if (clean.isEmpty || ref.read(appConfigProvider).isDemo) {
      if (mounted && request == _voiceOptionsRequest) {
        setState(() => _voiceOptions = const []);
      }
      return;
    }
    try {
      final options = await ref
          .read(profileRepositoryProvider)
          .listVoiceOptions(clean);
      if (!mounted || request != _voiceOptionsRequest) return;
      setState(() => _voiceOptions = options);
    } catch (_) {
      if (mounted && request == _voiceOptionsRequest) {
        setState(() => _voiceOptions = const []);
      }
    }
  }

  List<DropdownMenuItem<String>> _voiceItems() {
    final items = _voiceOptions
        .map((option) {
          final id = option['id']?.toString().trim() ?? '';
          if (id.isEmpty) return null;
          final name = option['name']?.toString().trim();
          return DropdownMenuItem<String>(
            value: id,
            child: Text(name == null || name.isEmpty ? id : name),
          );
        })
        .whereType<DropdownMenuItem<String>>()
        .toList(growable: true);
    final selectedId = _voiceIdController.text.trim();
    if (selectedId.isNotEmpty &&
        items.every((item) => item.value != selectedId)) {
      items.insert(
        0,
        DropdownMenuItem<String>(
          value: selectedId,
          child: Text(_voice.isEmpty ? selectedId : _voice),
        ),
      );
    }
    return items;
  }

  List<ProfileSkillBinding> _skillBindingsFor(CompanionProfile current) {
    const capabilityToSkill = {
      'weather': 'skill-weather',
      'web_search': 'skill-web-search',
    };
    final capabilitySkillIds = <String>{
      ...capabilityToSkill.values,
      'weather',
      'get_weather',
      'web_search',
    };
    final retained = current.skills
        .where((skill) => !capabilitySkillIds.contains(skill.skillId))
        .toList();
    for (final entry in _capabilities.entries) {
      final skillId = capabilityToSkill[entry.key];
      if (skillId == null) continue;
      final existing = current.skills
          .where((skill) => _canonicalCapability(skill.skillId) == entry.key)
          .firstOrNull;
      if (existing != null) {
        final nextEnabled = _capabilityAvailability[entry.key] == false
            ? existing.enabled
            : entry.value;
        retained.add(existing.copyWith(skillId: skillId, enabled: nextEnabled));
      } else if (entry.value) {
        retained.add(ProfileSkillBinding(skillId: skillId));
      }
    }
    return retained;
  }

  String? _canonicalCapability(String skillId) => switch (skillId) {
    'skill-weather' || 'get_weather' || 'weather' => 'weather',
    'skill-web-search' || 'web_search' => 'web_search',
    _ => null,
  };

  List<ProfileModelBinding> _replaceModelBinding(
    List<ProfileModelBinding> bindings,
    String modelType,
    String requestedId,
    String? originalId,
  ) {
    if (requestedId == originalId) return bindings;
    final index = bindings.indexWhere((item) => item.modelType == modelType);
    final binding = ProfileModelBinding(
      modelType: modelType,
      source: requestedId.isEmpty ? 'default' : 'global',
      resourceId: requestedId.isEmpty ? null : requestedId,
    );
    if (index < 0) return [...bindings, binding];
    return [...bindings]..[index] = binding;
  }

  String _errorText(Object error) {
    final text = error.toString();
    return text.startsWith('ApiException(') ? '保存失败，请稍后重试' : text;
  }

  bool _asBool(Object? value, {required bool fallback}) {
    if (value is bool) return value;
    if (value is num) return value != 0;
    if (value is String) {
      if (value == '1' || value.toLowerCase() == 'true') return true;
      if (value == '0' || value.toLowerCase() == 'false') return false;
    }
    return fallback;
  }

  List<Map<String, String>> get _visibleCapabilityOptions {
    if (_capabilityOptions.isNotEmpty) {
      return _capabilityOptions
          .map(
            (option) => <String, String>{
              'id': option['id']?.toString() ?? '',
              'name':
                  option['name']?.toString() ?? option['id']?.toString() ?? '',
              'description': option['description']?.toString() ?? '',
            },
          )
          .where((option) => option['id']!.isNotEmpty)
          .toList(growable: false);
    }
    return const [
      {'id': 'emotion', 'name': '情绪感知', 'description': '识别对话中的情绪变化'},
      {'id': 'weather', 'name': '天气', 'description': '回答指定城市的天气'},
      {'id': 'web_search', 'name': '联网搜索', 'description': '在需要时查询公开信息'},
    ];
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
              onPressed: _name.text.trim().isEmpty || _saving ? null : _save,
              child: const Text('保存'),
            ),
          ],
        ),
        body: ListView(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 36),
          children: [
            if (_error != null)
              Padding(
                padding: const EdgeInsets.fromLTRB(4, 0, 4, 10),
                child: Text(
                  _error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ),
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
                if (!editing &&
                    !ref.read(appConfigProvider).isDemo &&
                    (_loadingTemplates || _templates.isNotEmpty)) ...[
                  const SizedBox(height: 12),
                  DropdownButtonFormField<String>(
                    initialValue: _selectedTemplateId,
                    decoration: const InputDecoration(labelText: '起始角色'),
                    items:
                        _templates
                            .map(
                              (template) => DropdownMenuItem<String>(
                                value: template['id']?.toString(),
                                child: Text(
                                  template['name']?.toString() ?? '预设角色',
                                ),
                              ),
                            )
                            .toList()
                          ..insert(
                            0,
                            const DropdownMenuItem<String>(
                              value: '',
                              child: Text('从空白开始'),
                            ),
                          ),
                    onChanged: _loadingTemplates
                        ? null
                        : (value) => setState(() {
                            _selectedTemplateId = value;
                            _dirty = true;
                          }),
                  ),
                ],
                const SizedBox(height: 12),
                TextField(
                  controller: _summary,
                  maxLength: 80,
                  readOnly: true,
                  decoration: const InputDecoration(
                    labelText: '一句话介绍',
                    helperText: '由角色性格自动生成',
                  ),
                ),
                const SizedBox(height: 12),
                DropdownButtonFormField<String>(
                  initialValue: _relationMode,
                  decoration: const InputDecoration(labelText: '关系定位'),
                  items: <DropdownMenuItem<String>>[
                    const DropdownMenuItem(value: 'friend', child: Text('朋友')),
                    const DropdownMenuItem(value: 'lover', child: Text('亲密伴侣')),
                    if (_relationMode != 'friend' && _relationMode != 'lover')
                      DropdownMenuItem(
                        value: _relationMode,
                        child: Text(_relationMode),
                      ),
                  ],
                  onChanged: (value) => setState(() {
                    _relationMode = value ?? _relationMode;
                    _dirty = true;
                  }),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _address,
                  onChanged: (value) => _userAddress = value,
                  decoration: const InputDecoration(
                    labelText: '称呼或用户地址',
                    helperText: '可选，用于角色称呼你的方式',
                  ),
                ),
              ],
            ),
            _EditorSection(
              title: '声音',
              icon: Icons.record_voice_over_outlined,
              children: [
                TextField(
                  controller: _llmModelController,
                  decoration: const InputDecoration(labelText: '对话模型 ID'),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _ttsModelController,
                  onChanged: (value) {
                    _dirty = true;
                    unawaited(_loadVoiceOptions(value));
                  },
                  decoration: const InputDecoration(labelText: '语音模型 ID'),
                ),
                const SizedBox(height: 12),
                Builder(
                  builder: (context) {
                    final items = _voiceItems();
                    return DropdownButtonFormField<String>(
                      initialValue: _voiceIdController.text.trim().isEmpty
                          ? null
                          : _voiceIdController.text.trim(),
                      decoration: InputDecoration(
                        labelText: '已授权音色',
                        helperText: items.isEmpty ? '先填写语音模型 ID' : null,
                      ),
                      items: items,
                      onChanged: items.isEmpty
                          ? null
                          : (value) {
                              if (value == null) return;
                              final option = _voiceOptions
                                  .where(
                                    (item) => item['id']?.toString() == value,
                                  )
                                  .firstOrNull;
                              setState(() {
                                _voiceId = value;
                                _voiceIdController.text = value;
                                _voice = option?['name']?.toString() ?? value;
                                _dirty = true;
                              });
                            },
                    );
                  },
                ),
                TextField(
                  controller: _voiceIdController,
                  onChanged: (value) {
                    final clean = value.trim();
                    _voiceId = clean.isEmpty ? null : clean;
                    final option = _voiceOptions
                        .where((item) => item['id']?.toString() == clean)
                        .firstOrNull;
                    if (option != null) _voice = option['name'].toString();
                  },
                  decoration: const InputDecoration(
                    labelText: '音色 ID',
                    helperText: '使用后台已授权的音色 ID',
                  ),
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
                        value: _ttsRate.clamp(.5, 2).toDouble(),
                        min: .5,
                        max: 2,
                        onChanged: (value) => setState(() {
                          _ttsRate = value;
                          _dirty = true;
                        }),
                      ),
                    ),
                    Text('${(_ttsRate * 100).round()}%'),
                  ],
                ),
                Row(
                  children: [
                    const Text('音量'),
                    Expanded(
                      child: Slider(
                        value: _ttsVolume.clamp(0, 1.2).toDouble(),
                        min: 0,
                        max: 1.2,
                        onChanged: (value) => setState(() {
                          _ttsVolume = value;
                          _dirty = true;
                        }),
                      ),
                    ),
                    Text('${(_ttsVolume * 100).round()}%'),
                  ],
                ),
                Row(
                  children: [
                    const Text('音调'),
                    Expanded(
                      child: Slider(
                        value: _ttsPitch.clamp(.25, 2).toDouble(),
                        min: .25,
                        max: 2,
                        onChanged: (value) => setState(() {
                          _ttsPitch = value;
                          _dirty = true;
                        }),
                      ),
                    ),
                    Text('${(_ttsPitch * 100).round()}%'),
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
              children: _visibleCapabilityOptions
                  .map(
                    (option) => _CapabilityRow(
                      label: option['name']!,
                      description: option['description']!,
                      value: _capabilities[option['id']] ?? false,
                      enabled: _capabilityAvailability[option['id']] ?? true,
                      onChanged: (value) => setState(() {
                        _capabilities[option['id']!] = value;
                        _dirty = true;
                      }),
                    ),
                  )
                  .toList(growable: false),
            ),
            _EditorSection(
              title: '长期记忆',
              icon: Icons.memory_outlined,
              children: [
                SwitchListTile.adaptive(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('保留完整聊天记录'),
                  subtitle: const Text('关闭后只保留文字摘要'),
                  value: !_chatHistoryTextOnly,
                  onChanged: (value) => setState(() {
                    _chatHistoryTextOnly = !value;
                    _dirty = true;
                  }),
                ),
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
              _EditorSection(
                title: '绑定设备',
                icon: Icons.devices_other_outlined,
                children: [
                  Text(
                    _boundDeviceCount == 0
                        ? '当前没有绑定设备'
                        : '当前绑定 $_boundDeviceCount 台设备',
                    style: const TextStyle(color: AppTheme.mutedInk),
                  ),
                  const SizedBox(height: 6),
                  const Text(
                    '请在设备页面切换角色或解除绑定。',
                    style: TextStyle(fontSize: 12, color: AppTheme.mutedInk),
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
    context.push('/profiles/$_profileId/memories');
  }

  Future<void> _deleteProfile(BuildContext context) async {
    final store = ref.read(companionStoreProvider);
    var profile = store.profiles
        .where((item) => item.id == _profileId)
        .firstOrNull;
    if (profile == null && !ref.read(appConfigProvider).isDemo) {
      try {
        profile = CompanionProfile.fromMap(
          await ref.read(profileRepositoryProvider).getProfile(_profileId),
        );
      } catch (error) {
        if (context.mounted) {
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(SnackBar(content: Text(_errorText(error))));
        }
        return;
      }
    }
    if (!context.mounted) return;
    if (profile == null) return;
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
      try {
        if (ref.read(appConfigProvider).isDemo) {
          store.removeProfile(_profileId);
        } else {
          await ref.read(profileRepositoryProvider).deleteProfile(_profileId);
          store.replaceProfiles(
            store.profiles.where((item) => item.id != _profileId),
          );
        }
        if (context.mounted) context.pop();
      } catch (error) {
        if (context.mounted) {
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(SnackBar(content: Text(_errorText(error))));
        }
      }
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

class _CapabilityRow extends StatelessWidget {
  const _CapabilityRow({
    required this.label,
    required this.description,
    required this.value,
    required this.enabled,
    required this.onChanged,
  });
  final String label;
  final String description;
  final bool value;
  final bool enabled;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) => SwitchListTile.adaptive(
    contentPadding: EdgeInsets.zero,
    title: Text(label),
    subtitle: Text(description, style: const TextStyle(fontSize: 12)),
    value: value,
    onChanged: enabled ? onChanged : null,
  );
}
