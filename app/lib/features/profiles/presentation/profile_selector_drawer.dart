import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/providers/core_providers.dart';
import '../../../core/storage/secure_store.dart';
import '../../../core/theme/app_theme.dart';
import '../domain/profile_models.dart';

class ProfileSelectorDrawer extends ConsumerWidget {
  const ProfileSelectorDrawer({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final store = ref.watch(companionStoreProvider);
    return Material(
      color: AppTheme.canvas,
      child: SafeArea(
        child: SizedBox(
          width: MediaQuery.sizeOf(context).width * .88,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 18, 14, 4),
                child: Row(
                  children: [
                    const Text(
                      '陪伴角色',
                      style: TextStyle(
                        fontSize: 22,
                        fontWeight: FontWeight.w800,
                        color: AppTheme.ink,
                      ),
                    ),
                    const Spacer(),
                    IconButton(
                      tooltip: '关闭角色选择',
                      onPressed: () => Navigator.pop(context),
                      icon: const Icon(Icons.close),
                    ),
                  ],
                ),
              ),
              const Padding(
                padding: EdgeInsets.fromLTRB(20, 0, 20, 16),
                child: Text(
                  '选择一个角色开始新的对话',
                  style: TextStyle(color: AppTheme.mutedInk, fontSize: 13),
                ),
              ),
              Expanded(
                child: ListView.separated(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
                  itemCount: store.profiles.length + 1,
                  separatorBuilder: (_, __) => const SizedBox(height: 10),
                  itemBuilder: (context, index) {
                    if (index == store.profiles.length) {
                      return OutlinedButton.icon(
                        onPressed: () {
                          Navigator.pop(context);
                          context.push('/profiles/new');
                        },
                        icon: const Icon(Icons.add),
                        label: const Text('创建自己的角色'),
                      );
                    }
                    final profile = store.profiles[index];
                    final selected = profile.id == store.selectedProfileId;
                    return _ProfileChoiceCard(
                      profile: profile,
                      selected: selected,
                      onTap: () {
                        unawaited(
                          ref
                              .read(chatControllerProvider)
                              .startNewForProfile(profile.id),
                        );
                        Navigator.pop(context);
                      },
                      onMemoryChanged: (value) =>
                          _toggleMemory(context, ref, profile, value),
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

  Future<void> _toggleMemory(
    BuildContext context,
    WidgetRef ref,
    CompanionProfile profile,
    bool value,
  ) async {
    final store = ref.read(companionStoreProvider);
    if (ref.read(appConfigProvider).isDemo) {
      store.toggleMemory(profile.id, value);
      return;
    }
    try {
      await ref
          .read(profileRepositoryProvider)
          .setMemoryEnabled(profile.id, value);
      if (context.mounted) store.toggleMemory(profile.id, value);
    } catch (error) {
      if (context.mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(_errorText(error))));
      }
    }
  }

  String _errorText(Object error) {
    final text = error.toString();
    return text.startsWith('ApiException(') ? '记忆设置保存失败，请稍后重试' : text;
  }
}

class _ProfileChoiceCard extends StatelessWidget {
  const _ProfileChoiceCard({
    required this.profile,
    required this.selected,
    required this.onTap,
    required this.onMemoryChanged,
  });
  final CompanionProfile profile;
  final bool selected;
  final VoidCallback onTap;
  final ValueChanged<bool> onMemoryChanged;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: selected ? AppTheme.sage : AppTheme.surface,
      borderRadius: BorderRadius.circular(17),
      child: InkWell(
        borderRadius: BorderRadius.circular(17),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            children: [
              Row(
                children: [
                  _ProfileAvatar(profile: profile, size: 52),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Text(
                              profile.name,
                              style: const TextStyle(
                                fontWeight: FontWeight.w800,
                                fontSize: 16,
                              ),
                            ),
                            if (selected) ...[
                              const SizedBox(width: 7),
                              const Icon(
                                Icons.check_circle,
                                size: 17,
                                color: AppTheme.accentDark,
                              ),
                            ],
                          ],
                        ),
                        const SizedBox(height: 3),
                        Text(
                          profile.summary,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontSize: 12,
                            color: AppTheme.mutedInk,
                            height: 1.35,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
              const Divider(height: 22),
              Row(
                children: [
                  const Icon(
                    Icons.memory_outlined,
                    size: 17,
                    color: AppTheme.mutedInk,
                  ),
                  const SizedBox(width: 7),
                  const Expanded(
                    child: Text(
                      '长期记忆',
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                  Switch(
                    value: profile.memoryEnabled,
                    onChanged: onMemoryChanged,
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class ProfileAvatar extends ConsumerWidget {
  const ProfileAvatar({super.key, required this.profile, this.size = 64});
  final CompanionProfile profile;
  final double size;

  @override
  Widget build(BuildContext context, WidgetRef ref) =>
      _ProfileAvatar(profile: profile, size: size);
}

class _ProfileAvatar extends ConsumerStatefulWidget {
  const _ProfileAvatar({required this.profile, required this.size});
  final CompanionProfile profile;
  final double size;

  @override
  ConsumerState<_ProfileAvatar> createState() => _ProfileAvatarState();
}

class _ProfileAvatarState extends ConsumerState<_ProfileAvatar> {
  Future<StoredSession?>? _sessionFuture;

  @override
  void initState() {
    super.initState();
    _sessionFuture = ref.read(secureStoreProvider).read();
  }

  @override
  Widget build(BuildContext context) {
    final avatarUri = _resolveAvatarUri(
      widget.profile.avatarUrl,
      ref.watch(appConfigProvider).apiBaseUrl,
    );
    final canLoadAvatar = avatarUri != null;
    final fallback = _fallback();
    if (!canLoadAvatar) return fallback;
    final parsed = Uri.tryParse(widget.profile.avatarUrl ?? '');
    final isRelative = parsed != null && !parsed.hasScheme;
    if (isRelative) {
      return FutureBuilder<StoredSession?>(
        future: _sessionFuture,
        builder: (context, snapshot) {
          final session = snapshot.data;
          if (session == null) return fallback;
          return _image(avatarUri, session.accessToken, fallback);
        },
      );
    }
    return _image(avatarUri, null, fallback);
  }

  Widget _image(Uri uri, String? accessToken, Widget fallback) {
    return Image.network(
      uri.toString(),
      width: widget.size,
      height: widget.size,
      fit: BoxFit.cover,
      headers: accessToken == null
          ? null
          : {'Authorization': 'Bearer $accessToken'},
      errorBuilder: (_, __, ___) => fallback,
    );
  }

  Widget _fallback() {
    return Container(
      width: widget.size,
      height: widget.size,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: widget.profile.id.hashCode.isEven
            ? const Color(0xFFDDEBE4)
            : const Color(0xFFF6DFD8),
      ),
      alignment: Alignment.center,
      clipBehavior: Clip.antiAlias,
      child: Text(
        widget.profile.name.characters.first,
        style: TextStyle(
          fontSize: widget.size * .38,
          fontWeight: FontWeight.w800,
          color: AppTheme.ink,
        ),
      ),
    );
  }

  static Uri? _resolveAvatarUri(String? raw, Uri apiBaseUrl) {
    if (raw == null || raw.trim().isEmpty) return null;
    final value = raw.trim();
    final parsed = Uri.tryParse(value);
    if (parsed == null) return null;
    if (parsed.hasScheme) {
      return const {'http', 'https'}.contains(parsed.scheme.toLowerCase())
          ? parsed
          : null;
    }
    if (!value.startsWith('/') || value.contains('..')) return null;
    final basePath = apiBaseUrl.path.endsWith('/')
        ? apiBaseUrl.path
        : '${apiBaseUrl.path}/';
    return apiBaseUrl.replace(
      path: '$basePath${value.substring(1)}',
      query: parsed.hasQuery ? parsed.query : null,
      fragment: parsed.hasFragment ? parsed.fragment : null,
    );
  }
}
