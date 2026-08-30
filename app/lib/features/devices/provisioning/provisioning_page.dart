import 'package:flutter/material.dart';
import 'package:dio/dio.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../../../core/theme/app_theme.dart';
import 'device_portal_delegate.dart';
import 'provisioning_controller.dart';
import 'provisioning_models.dart';

class ProvisioningPage extends StatefulWidget {
  const ProvisioningPage({super.key});

  @override
  State<ProvisioningPage> createState() => _ProvisioningPageState();
}

class _ProvisioningPageState extends State<ProvisioningPage> {
  final controller = ProvisioningController();
  final code = TextEditingController();
  bool showPortal = false;

  @override
  void initState() {
    super.initState();
    controller.addListener(_refresh);
    controller.begin();
  }

  @override
  void dispose() {
    controller.removeListener(_refresh);
    controller.dispose();
    code.dispose();
    super.dispose();
  }

  void _refresh() => setState(() {});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('添加设备')),
      body: _body(controller.state),
    );
  }

  Widget _body(ProvisioningState state) {
    switch (state.step) {
      case ProvisioningStep.hotspotInstructions:
        return _step(
          '连接设备热点',
          '设备会自动打开临时 Wi-Fi。请在系统设置中连接扫描到的目标热点。',
          Icons.wifi_tethering,
          FilledButton.icon(
            onPressed: controller.openPortal,
            icon: const Icon(Icons.open_in_browser),
            label: const Text('我已连接热点'),
          ),
        );
      case ProvisioningStep.portal:
        if (showPortal) return _portalView();
        return _step(
          '配置家庭 Wi-Fi',
          '打开设备配网页面，填写家里的 Wi-Fi 信息并提交。',
          Icons.router_outlined,
          FilledButton(
            onPressed: () => setState(() => showPortal = true),
            child: const Text('打开配网页面'),
          ),
        );
      case ProvisioningStep.waitingForDevice:
        return _step(
          '等待设备完成配置',
          '提交后等待设备重启。完成页关闭前会先发送退出请求。',
          Icons.sync,
          FilledButton(
            onPressed: controller.readyForActivation,
            child: const Text('设备已完成'),
          ),
        );
      case ProvisioningStep.activationCode:
        return Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text(
                '输入六位激活码',
                style: TextStyle(fontSize: 24, fontWeight: FontWeight.w800),
              ),
              const SizedBox(height: 8),
              const Text(
                '激活码用于把这台设备绑定到当前账号。',
                style: TextStyle(color: AppTheme.mutedInk),
              ),
              const SizedBox(height: 22),
              TextField(
                controller: code,
                keyboardType: TextInputType.number,
                maxLength: 6,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 24, letterSpacing: 7),
                onChanged: controller.setActivationCode,
                decoration: const InputDecoration(labelText: '六位激活码'),
              ),
              if (state.lastError != null)
                Text(
                  state.lastError!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              const SizedBox(height: 14),
              FilledButton(
                onPressed: () {
                  if (controller.beginBinding(null)) controller.succeed();
                },
                child: const Text('绑定设备'),
              ),
            ],
          ),
        );
      case ProvisioningStep.binding:
        return _step(
          '正在绑定',
          '正在确认设备归属…',
          Icons.link,
          const CircularProgressIndicator(),
        );
      case ProvisioningStep.success:
        return _step(
          '设备已添加',
          '现在可以在设备页管理它。',
          Icons.check_circle_outline,
          FilledButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('完成'),
          ),
        );
      default:
        return _step(
          '添加设备',
          '可以随时重新开始配网。',
          Icons.devices_other,
          FilledButton(
            onPressed: () => controller.begin(),
            child: const Text('重新开始'),
          ),
        );
    }
  }

  Widget _portalView() {
    final webController = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.disabled)
      ..setNavigationDelegate(
        NavigationDelegate(
          onNavigationRequest: DevicePortalDelegate(
            onPortalSucceeded: controller.portalSucceeded,
            onExitRequested: () async {
              try {
                await Dio().get('http://192.168.4.1/exit');
              } catch (_) {
                // The device can close the hotspot immediately after /exit.
              }
            },
          ).handleNavigation,
        ),
      )
      ..loadRequest(Uri.parse('http://192.168.4.1/'));
    return WebViewWidget(controller: webController);
  }

  Widget _step(String title, String description, IconData icon, Widget action) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 52, color: AppTheme.accentDark),
            const SizedBox(height: 20),
            Text(
              title,
              style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 9),
            Text(
              description,
              textAlign: TextAlign.center,
              style: const TextStyle(color: AppTheme.mutedInk, height: 1.4),
            ),
            const SizedBox(height: 24),
            SizedBox(width: double.infinity, child: action),
          ],
        ),
      ),
    );
  }
}
