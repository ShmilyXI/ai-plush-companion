import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/providers/core_providers.dart';
import '../../../core/theme/app_theme.dart';
import 'contact_mode_toggle.dart';

class RegisterPage extends ConsumerStatefulWidget {
  const RegisterPage({super.key});
  @override
  ConsumerState<RegisterPage> createState() => _RegisterPageState();
}

class _RegisterPageState extends ConsumerState<RegisterPage> {
  final contact = TextEditingController();
  final code = TextEditingController();
  final password = TextEditingController();
  final confirm = TextEditingController();
  String channel = 'phone';
  bool loading = false;
  @override
  void dispose() {
    contact.dispose();
    code.dispose();
    password.dispose();
    confirm.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('注册账号')),
    body: Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 430),
        child: ListView(
          padding: const EdgeInsets.all(24),
          children: [
            const Text(
              '创建你的陪伴空间',
              style: TextStyle(fontSize: 27, fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 8),
            const Text(
              '手机号或邮箱都可以注册，密码用于之后登录。',
              style: TextStyle(color: AppTheme.mutedInk),
            ),
            const SizedBox(height: 24),
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
                    maxLength: 6,
                    keyboardType: TextInputType.number,
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
              decoration: const InputDecoration(labelText: '设置密码'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: confirm,
              obscureText: true,
              decoration: const InputDecoration(labelText: '确认密码'),
            ),
            const SizedBox(height: 22),
            FilledButton(
              onPressed: loading ? null : _register,
              child: loading
                  ? const SizedBox.square(
                      dimension: 20,
                      child: CircularProgressIndicator(
                        color: Colors.white,
                        strokeWidth: 2,
                      ),
                    )
                  : const Text('创建账号'),
            ),
            const SizedBox(height: 10),
            TextButton(
              onPressed: () => context.pop(),
              child: const Text('已有账号，返回登录'),
            ),
          ],
        ),
      ),
    ),
  );
  Future<void> _register() async {
    if (password.text != confirm.text || contact.text.trim().isEmpty) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('请检查联系方式和两次密码')));
      return;
    }
    setState(() => loading = true);
    await Future<void>.delayed(const Duration(milliseconds: 350));
    final store = ref.read(companionStoreProvider);
    store.signIn();
    if (mounted) context.go('/onboarding');
  }
}
