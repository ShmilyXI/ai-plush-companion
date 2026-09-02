import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/providers/core_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../domain/profile_models.dart';
import 'profile_selector_drawer.dart';

class ProfileListPage extends ConsumerStatefulWidget {
  const ProfileListPage({super.key});

  @override
  ConsumerState<ProfileListPage> createState() => _ProfileListPageState();
}

class _ProfileListPageState extends ConsumerState<ProfileListPage> {
  bool loading = false;
  String? error;

  @override
  void initState() {
    super.initState();
    if (!ref.read(appConfigProvider).isDemo) {
      unawaited(_load());
    }
  }

  Future<void> _load() async {
    if (mounted) setState(() => loading = true);
    try {
      final rows = await ref.read(profileRepositoryProvider).listProfiles();
      final profiles = rows
          .map((row) => CompanionProfile.fromMap(row))
          .where((profile) => !profile.deleted)
          .toList(growable: false);
      if (!mounted) return;
      _syncStore(profiles);
      setState(() {
        error = null;
        loading = false;
      });
    } catch (value) {
      if (!mounted) return;
      setState(() {
        error = value.toString();
        loading = false;
      });
    }
  }

  void _syncStore(List<CompanionProfile> profiles) {
    final store = ref.read(companionStoreProvider);
    store.replaceProfiles(profiles);
  }

  Future<void> _toggleMemory(CompanionProfile profile, bool value) async {
    final store = ref.read(companionStoreProvider);
    if (ref.read(appConfigProvider).isDemo) {
      store.toggleMemory(profile.id, value);
      return;
    }
    try {
      await ref
          .read(profileRepositoryProvider)
          .setMemoryEnabled(profile.id, value);
      if (mounted) store.toggleMemory(profile.id, value);
    } catch (value) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(_errorText(value))));
      }
    }
  }

  String _errorText(Object value) {
    final text = value.toString();
    return text.startsWith('ApiException(') ? '保存失败，请稍后重试' : text;
  }

  @override
  Widget build(BuildContext context) {
    final store = ref.watch(companionStoreProvider);
    return CustomScrollView(
      slivers: [
        SliverAppBar(
          pinned: true,
          title: const Text('角色'),
          actions: [
            IconButton(
              tooltip: '创建角色',
              onPressed: () => context.push('/profiles/new'),
              icon: const Icon(Icons.add),
            ),
          ],
        ),
        SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(18, 4, 18, 16),
            child: Text(
              '每个角色都有自己的声音、性格和长期记忆.',
              style: Theme.of(
                context,
              ).textTheme.bodyMedium?.copyWith(color: AppTheme.mutedInk),
            ),
          ),
        ),
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 30),
          sliver: SliverList.separated(
            itemCount: store.profiles.length,
            separatorBuilder: (_, __) => const SizedBox(height: 11),
            itemBuilder: (context, index) {
              final profile = store.profiles[index];
              return _ProfileListCard(
                profile: profile,
                selected: profile.id == store.selectedProfileId,
                onTap: () => context.push('/profiles/${profile.id}'),
                onMemory: (value) => _toggleMemory(profile, value),
              );
            },
          ),
        ),
        if (loading)
          const SliverToBoxAdapter(
            child: Padding(
              padding: EdgeInsets.only(bottom: 16),
              child: Center(child: CircularProgressIndicator()),
            ),
          ),
        if (error != null)
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(18, 0, 18, 20),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      '角色加载失败',
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                  ),
                  TextButton(onPressed: _load, child: const Text('重试')),
                ],
              ),
            ),
          ),
      ],
    );
  }
}

class _ProfileListCard extends StatelessWidget {
  const _ProfileListCard({
    required this.profile,
    required this.selected,
    required this.onTap,
    required this.onMemory,
  });

  final CompanionProfile profile;
  final bool selected;
  final VoidCallback onTap;
  final ValueChanged<bool> onMemory;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(18),
        child: Padding(
          padding: const EdgeInsets.all(15),
          child: Row(
            children: [
              ProfileAvatar(profile: profile, size: 58),
              const SizedBox(width: 13),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Flexible(
                          child: Text(
                            profile.name,
                            style: const TextStyle(
                              fontWeight: FontWeight.w800,
                              fontSize: 17,
                            ),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        if (selected) ...[
                          const SizedBox(width: 7),
                          const Text(
                            '当前使用',
                            style: TextStyle(
                              fontSize: 11,
                              color: AppTheme.accentDark,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                        ],
                      ],
                    ),
                    const SizedBox(height: 4),
                    Text(
                      profile.summary,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: AppTheme.mutedInk,
                        fontSize: 13,
                        height: 1.3,
                      ),
                    ),
                    const SizedBox(height: 10),
                    Row(
                      children: [
                        const Icon(
                          Icons.memory_outlined,
                          size: 15,
                          color: AppTheme.mutedInk,
                        ),
                        const SizedBox(width: 5),
                        Text(
                          profile.memoryEnabled ? '长期记忆开启' : '长期记忆关闭',
                          style: const TextStyle(
                            fontSize: 12,
                            color: AppTheme.mutedInk,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
              Switch(value: profile.memoryEnabled, onChanged: onMemory),
              const Icon(Icons.chevron_right, color: AppTheme.mutedInk),
            ],
          ),
        ),
      ),
    );
  }
}
