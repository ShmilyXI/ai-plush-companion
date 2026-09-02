import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/providers/core_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../../profiles/domain/profile_models.dart';
import '../../profiles/presentation/profile_selector_drawer.dart';

class DevicesPage extends ConsumerStatefulWidget {
  const DevicesPage({super.key});

  @override
  ConsumerState<DevicesPage> createState() => _DevicesPageState();
}

class _DevicesPageState extends ConsumerState<DevicesPage> {
  bool loading = false;
  String? error;

  @override
  void initState() {
    super.initState();
    if (!ref.read(appConfigProvider).isDemo) unawaited(_load());
  }

  Future<void> _load() async {
    if (mounted) setState(() => loading = true);
    try {
      final rows = await ref.read(deviceRepositoryProvider).list();
      final devices = rows
          .map((row) => CompanionDevice.fromMap(row))
          .toList(growable: false);
      if (!mounted) return;
      final store = ref.read(companionStoreProvider);
      store.replaceDevices(devices);
      setState(() {
        loading = false;
        error = null;
      });
    } catch (value) {
      if (!mounted) return;
      setState(() {
        loading = false;
        error = _errorText(value);
      });
    }
  }

  String _errorText(Object value) {
    final text = value.toString();
    return text.startsWith('ApiException(') ? '设备加载失败，请稍后重试' : text;
  }

  @override
  Widget build(BuildContext context) {
    final store = ref.watch(companionStoreProvider);
    return CustomScrollView(
      slivers: [
        SliverAppBar(
          pinned: true,
          title: const Text('设备'),
          actions: [
            IconButton(
              tooltip: '添加设备',
              onPressed: () => context.push('/devices/provisioning'),
              icon: const Icon(Icons.add),
            ),
          ],
        ),
        SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(18, 4, 18, 16),
            child: Text(
              '让硬件和你的陪伴角色保持连接.',
              style: Theme.of(
                context,
              ).textTheme.bodyMedium?.copyWith(color: AppTheme.mutedInk),
            ),
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
                      error!,
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
        if (store.devices.isEmpty)
          const SliverFillRemaining(
            hasScrollBody: false,
            child: _EmptyDevices(),
          )
        else
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 28),
            sliver: SliverList.separated(
              itemCount: store.devices.length,
              separatorBuilder: (_, __) => const SizedBox(height: 12),
              itemBuilder: (context, index) =>
                  _DeviceCard(device: store.devices[index]),
            ),
          ),
      ],
    );
  }
}

class _EmptyDevices extends StatelessWidget {
  const _EmptyDevices();

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
              Icons.devices_other,
              size: 34,
              color: AppTheme.accentDark,
            ),
          ),
          const SizedBox(height: 16),
          const Text(
            '还没有连接设备',
            style: TextStyle(fontWeight: FontWeight.w800, fontSize: 20),
          ),
          const SizedBox(height: 7),
          const Text(
            '点击右上角加号，跟着向导把硬件连到 Wi-Fi。',
            textAlign: TextAlign.center,
            style: TextStyle(color: AppTheme.mutedInk),
          ),
        ],
      ),
    ),
  );
}

class _DeviceCard extends ConsumerWidget {
  const _DeviceCard({required this.device});

  final CompanionDevice device;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final store = ref.watch(companionStoreProvider);
    final profile =
        store.profiles
            .where((item) => item.id == device.profileId)
            .firstOrNull ??
        store.selectedProfile;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  width: 44,
                  height: 44,
                  decoration: BoxDecoration(
                    color: device.online
                        ? AppTheme.sage
                        : const Color(0xFFF0F1EF),
                    borderRadius: BorderRadius.circular(13),
                  ),
                  child: Icon(
                    device.online ? Icons.wifi : Icons.wifi_off,
                    color: device.online
                        ? const Color(0xFF3A7564)
                        : AppTheme.mutedInk,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        device.alias,
                        style: const TextStyle(
                          fontSize: 17,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                      const SizedBox(height: 3),
                      Text(
                        '${device.board} · ${device.macAddress}',
                        style: const TextStyle(
                          color: AppTheme.mutedInk,
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                ),
                PopupMenuButton<String>(
                  tooltip: '设备操作',
                  onSelected: (action) {
                    if (action == 'unbind') _unbind(context, ref);
                    if (action == 'rename') _rename(context, ref);
                    if (action == 'provision') {
                      context.push('/devices/provisioning');
                    }
                  },
                  itemBuilder: (_) => const [
                    PopupMenuItem(value: 'rename', child: Text('修改名称')),
                    PopupMenuItem(value: 'provision', child: Text('重新配网')),
                    PopupMenuItem(value: 'unbind', child: Text('解绑设备')),
                  ],
                ),
              ],
            ),
            const SizedBox(height: 14),
            Row(
              children: [
                Container(
                  width: 8,
                  height: 8,
                  decoration: BoxDecoration(
                    color: device.online
                        ? const Color(0xFF42A17E)
                        : AppTheme.mutedInk,
                    shape: BoxShape.circle,
                  ),
                ),
                const SizedBox(width: 7),
                Text(
                  device.online ? '在线' : '等待设备上线',
                  style: const TextStyle(
                    fontSize: 12,
                    color: AppTheme.mutedInk,
                  ),
                ),
                const Spacer(),
                Text(
                  '角色：${profile.name}',
                  style: const TextStyle(
                    fontSize: 12,
                    color: AppTheme.mutedInk,
                  ),
                ),
              ],
            ),
            const Divider(height: 25),
            Row(
              children: [
                Expanded(
                  child: _DeviceSlider(
                    label: '音量',
                    icon: Icons.volume_up_outlined,
                    value: device.volume.toDouble(),
                    onChanged: (value) => _setCommand(
                      context,
                      ref,
                      device,
                      'volume',
                      value.round(),
                    ),
                  ),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: _DeviceSlider(
                    label: '亮度',
                    icon: Icons.wb_sunny_outlined,
                    value: device.brightness.toDouble(),
                    onChanged: (value) => _setCommand(
                      context,
                      ref,
                      device,
                      'brightness',
                      value.round(),
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            OutlinedButton.icon(
              onPressed: () => _chooseProfile(context, ref),
              icon: const Icon(Icons.auto_awesome_outlined),
              label: const Text('更换陪伴角色'),
            ),
          ],
        ),
      ),
    );
  }

  void _chooseProfile(BuildContext context, WidgetRef ref) {
    final profiles = ref.read(companionStoreProvider).profiles;
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (_) => SizedBox(
        height: 390,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            const Text(
              '设备使用的角色',
              style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 10),
            ...profiles.map(
              (profile) => ListTile(
                leading: ProfileAvatar(profile: profile, size: 42),
                title: Text(profile.name),
                subtitle: Text(profile.summary),
                trailing: profile.id == device.profileId
                    ? const Icon(Icons.check_circle, color: AppTheme.accentDark)
                    : null,
                onTap: () => _setProfile(context, ref, profile.id),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _setCommand(
    BuildContext context,
    WidgetRef ref,
    CompanionDevice device,
    String command,
    int value,
  ) async {
    final store = ref.read(companionStoreProvider);
    final current =
        store.devices.where((item) => item.id == device.id).firstOrNull ??
        device;
    final previous = current;
    final updated = command == 'volume'
        ? current.copyWith(volume: value)
        : current.copyWith(brightness: value);
    store.updateDevice(updated);
    if (ref.read(appConfigProvider).isDemo) return;
    try {
      await ref
          .read(deviceRepositoryProvider)
          .command(device.id, command, value);
    } catch (error) {
      if (!context.mounted) return;
      final current = store.devices
          .where((item) => item.id == device.id)
          .firstOrNull;
      final stillPending =
          current != null &&
          (command == 'volume'
              ? current.volume == value
              : current.brightness == value);
      if (stillPending) store.updateDevice(previous);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(_errorText(error))));
    }
  }

  Future<void> _setProfile(
    BuildContext context,
    WidgetRef ref,
    String profileId,
  ) async {
    final store = ref.read(companionStoreProvider);
    if (profileId == device.profileId) {
      Navigator.pop(context);
      return;
    }
    try {
      if (!ref.read(appConfigProvider).isDemo) {
        await ref
            .read(deviceRepositoryProvider)
            .setProfile(device.id, profileId);
      }
      if (context.mounted) {
        final current =
            store.devices.where((item) => item.id == device.id).firstOrNull ??
            device;
        store.updateDevice(current.copyWith(profileId: profileId));
        Navigator.pop(context);
      }
    } catch (error) {
      if (context.mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(_errorText(error))));
      }
    }
  }

  Future<void> _rename(BuildContext context, WidgetRef ref) async {
    final controller = TextEditingController(text: device.alias);
    final alias = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('修改设备名称'),
        content: TextField(controller: controller, maxLength: 64),
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
    final clean = alias?.trim() ?? '';
    if (clean.isEmpty) return;
    try {
      if (!ref.read(appConfigProvider).isDemo) {
        await ref.read(deviceRepositoryProvider).update(device.id, {
          'alias': clean,
        });
      }
      if (context.mounted) {
        final store = ref.read(companionStoreProvider);
        final current =
            store.devices.where((item) => item.id == device.id).firstOrNull ??
            device;
        store.updateDevice(current.copyWith(alias: clean));
      }
    } catch (error) {
      if (context.mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(_errorText(error))));
      }
    }
  }

  Future<void> _unbind(BuildContext context, WidgetRef ref) async {
    final yes = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('解绑设备？'),
        content: const Text('解绑后需要重新配网才能使用。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('解绑'),
          ),
        ],
      ),
    );
    if (yes != true) return;
    try {
      if (!ref.read(appConfigProvider).isDemo) {
        await ref.read(deviceRepositoryProvider).unbind(device.id);
      }
      if (context.mounted) {
        ref.read(companionStoreProvider).removeDevice(device.id);
      }
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
    return text.startsWith('ApiException(') ? '设备操作失败，请稍后重试' : text;
  }
}

class _DeviceSlider extends StatelessWidget {
  const _DeviceSlider({
    required this.label,
    required this.icon,
    required this.value,
    required this.onChanged,
  });

  final String label;
  final IconData icon;
  final double value;
  final ValueChanged<double> onChanged;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Row(
        children: [
          Icon(icon, size: 16, color: AppTheme.mutedInk),
          const SizedBox(width: 5),
          Text(
            label,
            style: const TextStyle(fontSize: 12, color: AppTheme.mutedInk),
          ),
          const Spacer(),
          Text(
            '${value.round()}%',
            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700),
          ),
        ],
      ),
      Slider(value: value, min: 0, max: 100, onChanged: onChanged),
    ],
  );
}
