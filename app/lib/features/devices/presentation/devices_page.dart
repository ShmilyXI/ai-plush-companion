import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/providers/core_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../../profiles/domain/profile_models.dart';
import '../../profiles/presentation/profile_selector_drawer.dart';

class DevicesPage extends ConsumerWidget {
  const DevicesPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final store = ref.watch(companionStoreProvider);
    return CustomScrollView(
      slivers: [
        SliverAppBar(
          pinned: true,
          title: const Text('设备'),
          actions: [
            IconButton(
              tooltip: '添加设备',
              onPressed: () => _showProvisioning(context, ref),
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

  void _showProvisioning(BuildContext context, WidgetRef ref) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (sheetContext) => _ProvisioningSheet(
        onComplete: () {
          ref.read(companionStoreProvider).addDemoDevice();
          Navigator.pop(sheetContext);
        },
      ),
    );
  }
}

class _EmptyDevices extends StatelessWidget {
  const _EmptyDevices();

  @override
  Widget build(BuildContext context) {
    return Center(
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
}

class _DeviceCard extends ConsumerWidget {
  const _DeviceCard({required this.device});

  final CompanionDevice device;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final store = ref.watch(companionStoreProvider);
    final profile = store.profiles.firstWhere(
      (item) => item.id == device.profileId,
      orElse: () => store.profiles.first,
    );
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
                  },
                  itemBuilder: (_) => const [
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
                    onChanged: (value) => ref
                        .read(companionStoreProvider)
                        .updateDevice(device.copyWith(volume: value.round())),
                  ),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: _DeviceSlider(
                    label: '亮度',
                    icon: Icons.wb_sunny_outlined,
                    value: device.brightness.toDouble(),
                    onChanged: (value) => ref
                        .read(companionStoreProvider)
                        .updateDevice(
                          device.copyWith(brightness: value.round()),
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
                onTap: () {
                  ref
                      .read(companionStoreProvider)
                      .updateDevice(device.copyWith(profileId: profile.id));
                  Navigator.pop(context);
                },
              ),
            ),
          ],
        ),
      ),
    );
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
    if (yes == true) ref.read(companionStoreProvider).removeDevice(device.id);
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

class _ProvisioningSheet extends StatefulWidget {
  const _ProvisioningSheet({required this.onComplete});

  final VoidCallback onComplete;

  @override
  State<_ProvisioningSheet> createState() => _ProvisioningSheetState();
}

class _ProvisioningSheetState extends State<_ProvisioningSheet> {
  int step = 0;
  final code = TextEditingController();

  @override
  void dispose() {
    code.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final titles = ['连接设备热点', '配置家庭 Wi-Fi', '输入激活码', '完成'];
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 4, 20, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              '添加设备',
              style: Theme.of(
                context,
              ).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 5),
            Text(
              titles[step],
              style: const TextStyle(color: AppTheme.mutedInk),
            ),
            const SizedBox(height: 20),
            LinearProgressIndicator(
              value: (step + 1) / titles.length,
              borderRadius: BorderRadius.circular(4),
              minHeight: 6,
            ),
            const SizedBox(height: 24),
            if (step == 0) ...[
              const Icon(
                Icons.wifi_tethering,
                size: 46,
                color: AppTheme.accentDark,
              ),
              const SizedBox(height: 12),
              const Text(
                '让设备自动打开配网热点，然后在系统 Wi-Fi 设置中连接它。',
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 18),
              OutlinedButton.icon(
                onPressed: () {},
                icon: const Icon(Icons.settings),
                label: const Text('打开 Wi-Fi 设置'),
              ),
            ] else if (step == 1) ...[
              const Icon(
                Icons.router_outlined,
                size: 46,
                color: AppTheme.accentDark,
              ),
              const SizedBox(height: 12),
              const Text(
                '已连接设备热点。打开设备页面填写家里的 Wi-Fi 信息。',
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 18),
              FilledButton.icon(
                onPressed: () => setState(() => step = 2),
                icon: const Icon(Icons.open_in_browser),
                label: const Text('打开配网页面'),
              ),
            ] else if (step == 2) ...[
              const Text(
                '设备完成配网后，在机身或包装上找到六位激活码。',
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 14),
              TextField(
                controller: code,
                maxLength: 6,
                keyboardType: TextInputType.number,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  fontSize: 24,
                  letterSpacing: 7,
                  fontWeight: FontWeight.w800,
                ),
                decoration: const InputDecoration(labelText: '六位激活码'),
              ),
              const SizedBox(height: 12),
              FilledButton.icon(
                onPressed: code.text.length == 6
                    ? () => setState(() => step = 3)
                    : null,
                icon: const Icon(Icons.link),
                label: const Text('绑定设备'),
              ),
            ] else ...[
              const Icon(
                Icons.check_circle,
                size: 52,
                color: Color(0xFF3A7564),
              ),
              const SizedBox(height: 12),
              const Text('设备已添加，可以开始陪伴了。', textAlign: TextAlign.center),
              const SizedBox(height: 18),
              FilledButton(
                onPressed: widget.onComplete,
                child: const Text('完成'),
              ),
            ],
            const SizedBox(height: 16),
            if (step < 2)
              FilledButton(
                onPressed: () => setState(() => step++),
                child: Text(step == 0 ? '我已连接热点' : '我已完成配置'),
              ),
          ],
        ),
      ),
    );
  }
}
