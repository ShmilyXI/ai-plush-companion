import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/providers/core_providers.dart';
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
                        ref
                            .read(companionStoreProvider)
                            .selectProfile(profile.id);
                        Navigator.pop(context);
                      },
                      onMemoryChanged: (value) => ref
                          .read(companionStoreProvider)
                          .toggleMemory(profile.id, value),
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

class ProfileAvatar extends StatelessWidget {
  const ProfileAvatar({super.key, required this.profile, this.size = 64});
  final CompanionProfile profile;
  final double size;

  @override
  Widget build(BuildContext context) =>
      _ProfileAvatar(profile: profile, size: size);
}

class _ProfileAvatar extends StatelessWidget {
  const _ProfileAvatar({required this.profile, required this.size});
  final CompanionProfile profile;
  final double size;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: profile.id.hashCode.isEven
            ? const Color(0xFFDDEBE4)
            : const Color(0xFFF6DFD8),
      ),
      alignment: Alignment.center,
      child: Text(
        profile.name.characters.first,
        style: TextStyle(
          fontSize: size * .38,
          fontWeight: FontWeight.w800,
          color: AppTheme.ink,
        ),
      ),
    );
  }
}
