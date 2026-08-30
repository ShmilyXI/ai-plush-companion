import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/providers/core_providers.dart';
import '../../../core/theme/app_theme.dart';
import 'contact_mode_toggle.dart';

class LoginPage extends ConsumerStatefulWidget {
  const LoginPage({super.key});
  @override
  ConsumerState<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends ConsumerState<LoginPage> {
  final _contact = TextEditingController();
  final _password = TextEditingController();
  final _code = TextEditingController();
  String channel = 'phone';
  String mode = 'password';
  bool loading = false;
  @override
  void dispose() {
    _contact.dispose();
    _password.dispose();
    _code.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 430),
          child: ListView(
            padding: const EdgeInsets.fromLTRB(24, 46, 24, 30),
            shrinkWrap: true,
            children: [
              Container(
                width: 68,
                height: 68,
                alignment: Alignment.center,
                decoration: const BoxDecoration(
                  color: AppTheme.sage,
                  shape: BoxShape.circle,
                ),
                child: const Icon(
                  Icons.auto_awesome,
                  color: AppTheme.accentDark,
                  size: 32,
                ),
              ),
              const SizedBox(height: 20),
              const Text(
                '欢迎回来',
                style: TextStyle(fontSize: 30, fontWeight: FontWeight.w800),
              ),
              const SizedBox(height: 7),
              const Text(
                '和你的陪伴角色继续聊下去。',
                style: TextStyle(color: AppTheme.mutedInk),
              ),
              const SizedBox(height: 27),
              ContactModeToggle(
                channel: channel,
                onChanged: (value) => setState(() => channel = value),
              ),
              const SizedBox(height: 13),
              SegmentedButton<String>(
                segments: const [
                  ButtonSegment(value: 'password', label: Text('密码登录')),
                  ButtonSegment(value: 'code', label: Text('验证码登录')),
                ],
                selected: {mode},
                onSelectionChanged: (value) =>
                    setState(() => mode = value.first),
              ),
              const SizedBox(height: 18),
              TextField(
                controller: _contact,
                keyboardType: channel == 'phone'
                    ? TextInputType.phone
                    : TextInputType.emailAddress,
                decoration: InputDecoration(
                  labelText: channel == 'phone' ? '手机号' : '邮箱',
                  hintText: channel == 'phone' ? '请输入手机号' : 'name@example.com',
                ),
              ),
              const SizedBox(height: 12),
              if (mode == 'password')
                TextField(
                  controller: _password,
                  obscureText: true,
                  decoration: const InputDecoration(labelText: '密码'),
                )
              else
                Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _code,
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
                        onPressed: () => _showCodeSent(context),
                        child: const Text('获取验证码'),
                      ),
                    ),
                  ],
                ),
              const SizedBox(height: 20),
              FilledButton(
                onPressed: loading ? null : _login,
                child: loading
                    ? const SizedBox.square(
                        dimension: 20,
                        child: CircularProgressIndicator(
                          color: Colors.white,
                          strokeWidth: 2,
                        ),
                      )
                    : const Text('登录'),
              ),
              const SizedBox(height: 12),
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  TextButton(
                    onPressed: () => context.push('/register'),
                    child: const Text('注册账号'),
                  ),
                  const Text('·', style: TextStyle(color: AppTheme.mutedInk)),
                  TextButton(
                    onPressed: () => context.push('/reset-password'),
                    child: const Text('找回密码'),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    ),
  );
  Future<void> _login() async {
    if (_contact.text.trim().isEmpty) return;
    setState(() => loading = true);
    await Future<void>.delayed(const Duration(milliseconds: 350));
    final store = ref.read(companionStoreProvider);
    store.signIn();
    if (mounted) context.go('/chat');
  }

  void _showCodeSent(BuildContext context) {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(const SnackBar(content: Text('验证码已发送，请查收')));
  }
}
