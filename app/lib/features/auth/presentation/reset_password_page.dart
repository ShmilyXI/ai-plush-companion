import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/providers/core_providers.dart';
import 'contact_mode_toggle.dart';

class ResetPasswordPage extends ConsumerStatefulWidget {
  const ResetPasswordPage({super.key});
  @override
  ConsumerState<ResetPasswordPage> createState() => _ResetPasswordPageState();
}

class _ResetPasswordPageState extends ConsumerState<ResetPasswordPage> {
  final contact = TextEditingController();
  final code = TextEditingController();
  final password = TextEditingController();
  String channel = 'phone';
  @override
  void dispose() {
    contact.dispose();
    code.dispose();
    password.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('找回密码')),
    body: Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 430),
        child: ListView(
          padding: const EdgeInsets.all(24),
          children: [
            const Text(
              '重新设置密码',
              style: TextStyle(fontSize: 27, fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 22),
            ContactModeToggle(
              channel: channel,
              onChanged: (value) => setState(() => channel = value),
            ),
            const SizedBox(height: 14),
            TextField(
              controller: contact,
              decoration: InputDecoration(
                labelText: channel == 'phone' ? '手机号' : '邮箱',
              ),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: code,
                    keyboardType: TextInputType.number,
                    maxLength: 6,
                    decoration: const InputDecoration(
                      labelText: '验证码',
                      counterText: '',
                    ),
                  ),
                ),
                const SizedBox(width: 9),
                SizedBox(
                  width: 100,
                  height: 52,
                  child: OutlinedButton(
                    onPressed: () => ScaffoldMessenger.of(
                      context,
                    ).showSnackBar(const SnackBar(content: Text('验证码已发送，请查收'))),
                    child: const Text('获取验证码'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            TextField(
              controller: password,
              obscureText: true,
              decoration: const InputDecoration(labelText: '新密码'),
            ),
            const SizedBox(height: 22),
            FilledButton(
              onPressed: () {
                if (password.text.length < 6 || code.text.length != 6) {
                  ScaffoldMessenger.of(
                    context,
                  ).showSnackBar(const SnackBar(content: Text('请输入六位验证码和新密码')));
                  return;
                }
                final store = ref.read(companionStoreProvider);
                store.signIn();
                context.go('/chat');
              },
              child: const Text('保存新密码'),
            ),
          ],
        ),
      ),
    ),
  );
}
