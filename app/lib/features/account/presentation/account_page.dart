import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/providers/core_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../../auth/domain/auth_models.dart';
import '../data/account_repository.dart';

class AccountPage extends ConsumerStatefulWidget {
  const AccountPage({super.key});

  @override
  ConsumerState<AccountPage> createState() => _AccountPageState();
}

class _AccountPageState extends ConsumerState<AccountPage> {
  AccountSummary? _account;
  bool _loading = false;
  bool _changingPassword = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    if (!ref.read(appConfigProvider).isDemo) unawaited(_load());
  }

  Future<void> _load() async {
    if (mounted) setState(() => _loading = true);
    try {
      final account = await ref.read(accountRepositoryProvider).get();
      if (!mounted) return;
      setState(() {
        _account = account;
        _loading = false;
        _error = null;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = _errorText(error);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final store = ref.watch(companionStoreProvider);
    final account = _account;
    final displayName = account?.user.displayName ?? '林间用户';
    final username = account?.user.username ?? 'user@example.com';
    final channels = account?.verifiedChannels ?? const <ContactChannel>[];
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
      children: [
        Row(
          children: [
            const Text(
              '我的',
              style: TextStyle(
                fontSize: 29,
                fontWeight: FontWeight.w800,
                color: AppTheme.ink,
              ),
            ),
            const Spacer(),
            IconButton(
              tooltip: '刷新账号',
              onPressed: _loading ? null : _load,
              icon: const Icon(Icons.refresh),
            ),
          ],
        ),
        if (_loading) const LinearProgressIndicator(minHeight: 2),
        if (_error != null)
          Padding(
            padding: const EdgeInsets.only(top: 10),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    _error!,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                ),
                TextButton(onPressed: _load, child: const Text('重试')),
              ],
            ),
          ),
        const SizedBox(height: 18),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(17),
            child: Row(
              children: [
                _UserAvatar(name: displayName),
                const SizedBox(width: 13),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        displayName,
                        style: const TextStyle(
                          fontSize: 17,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        username,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: AppTheme.mutedInk,
                          fontSize: 13,
                        ),
                      ),
                    ],
                  ),
                ),
                if (account != null)
                  const Icon(
                    Icons.verified_outlined,
                    color: AppTheme.accentDark,
                  ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 14),
        Card(
          child: Column(
            children: [
              ListTile(
                leading: const Icon(Icons.volume_up_outlined),
                title: const Text('自动播放回复'),
                subtitle: const Text('新消息到达时播放语音'),
                trailing: Switch(
                  value: store.autoPlay,
                  onChanged: (value) {
                    store.setAutoPlay(value);
                    unawaited(
                      ref.read(preferencesStoreProvider).writeAutoPlay(value),
                    );
                  },
                ),
              ),
              const Divider(height: 1, indent: 56),
              ListTile(
                leading: const Icon(Icons.shield_outlined),
                title: const Text('隐私与数据'),
                subtitle: const Text('管理角色记忆和聊天记录'),
                trailing: const Icon(Icons.chevron_right),
                onTap: () {
                  final profile = store.selectedProfile;
                  if (profile.id != 'profile-empty') {
                    context.push('/profiles/${profile.id}/memories');
                  }
                },
              ),
            ],
          ),
        ),
        const SizedBox(height: 14),
        Card(
          child: Column(
            children: [
              _ContactTile(
                icon: Icons.phone_outlined,
                title: '手机号',
                value: channels.contains(ContactChannel.phone) ? '已绑定' : '未绑定',
              ),
              const Divider(height: 1, indent: 56),
              _ContactTile(
                icon: Icons.mail_outline,
                title: '邮箱',
                value: channels.contains(ContactChannel.email)
                    ? (account?.user.username.contains('@') == true
                          ? account!.user.username
                          : '已绑定')
                    : '未绑定',
              ),
              const Divider(height: 1, indent: 56),
              ListTile(
                leading: const Icon(Icons.lock_outline),
                title: const Text('修改密码'),
                trailing: const Icon(Icons.chevron_right),
                onTap: _changingPassword ? null : _changePassword,
              ),
            ],
          ),
        ),
        const SizedBox(height: 22),
        OutlinedButton.icon(
          onPressed: _logout,
          icon: const Icon(Icons.logout),
          label: const Text('退出登录'),
        ),
      ],
    );
  }

  Future<void> _logout() async {
    await ref.read(chatControllerProvider).close();
    await ref.read(callControllerProvider).end();
    try {
      if (!ref.read(appConfigProvider).isDemo) {
        await ref.read(authRepositoryProvider).logout();
      } else {
        await ref.read(secureStoreProvider).clear();
      }
    } catch (_) {
      await ref.read(secureStoreProvider).clear();
    } finally {
      if (mounted) {
        ref.read(companionStoreProvider).signOut();
        context.go('/login');
      }
    }
  }

  Future<void> _changePassword() async {
    final current = TextEditingController();
    final next = TextEditingController();
    final confirm = TextEditingController();
    final values = await showDialog<(String, String)?>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('修改密码'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: current,
              obscureText: true,
              decoration: const InputDecoration(labelText: '当前密码'),
            ),
            TextField(
              controller: next,
              obscureText: true,
              decoration: const InputDecoration(labelText: '新密码'),
            ),
            TextField(
              controller: confirm,
              obscureText: true,
              decoration: const InputDecoration(labelText: '确认新密码'),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () {
              if (next.text.length >= 6 && next.text == confirm.text) {
                Navigator.pop(dialogContext, (current.text, next.text));
              }
            },
            child: const Text('保存'),
          ),
        ],
      ),
    );
    current.dispose();
    next.dispose();
    confirm.dispose();
    if (values == null || !mounted) return;
    if (ref.read(appConfigProvider).isDemo) return;
    setState(() => _changingPassword = true);
    try {
      await ref
          .read(accountRepositoryProvider)
          .changePassword(currentPassword: values.$1, newPassword: values.$2);
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('密码已更新，请重新登录')));
      }
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(_errorText(error))));
      }
    } finally {
      if (mounted) setState(() => _changingPassword = false);
    }
  }

  String _errorText(Object error) {
    final text = error.toString();
    return text.startsWith('ApiException(') ? '请求失败，请稍后重试' : text;
  }
}

class _UserAvatar extends StatelessWidget {
  const _UserAvatar({required this.name});

  final String name;

  @override
  Widget build(BuildContext context) => Container(
    width: 56,
    height: 56,
    decoration: const BoxDecoration(
      color: AppTheme.sage,
      shape: BoxShape.circle,
    ),
    alignment: Alignment.center,
    child: Text(
      name.characters.first,
      style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800),
    ),
  );
}

class _ContactTile extends StatelessWidget {
  const _ContactTile({
    required this.icon,
    required this.title,
    required this.value,
  });

  final IconData icon;
  final String title;
  final String value;

  @override
  Widget build(BuildContext context) =>
      ListTile(leading: Icon(icon), title: Text(title), subtitle: Text(value));
}
