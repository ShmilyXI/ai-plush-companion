import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/providers/core_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../domain/auth_models.dart';
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
  bool sendingCode = false;
  String? errorMessage;
  int retryAfterSeconds = 0;
  Timer? _cooldownTimer;
  @override
  void dispose() {
    contact.dispose();
    code.dispose();
    password.dispose();
    confirm.dispose();
    _cooldownTimer?.cancel();
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
                    onPressed: sendingCode || retryAfterSeconds > 0
                        ? null
                        : _sendCode,
                    child: Text(
                      retryAfterSeconds > 0 ? '${retryAfterSeconds}s' : '获取验证码',
                    ),
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
            if (errorMessage != null) ...[
              Text(
                errorMessage!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
              const SizedBox(height: 10),
            ],
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
    final value = contact.text.trim();
    final channelValue = channel == 'phone'
        ? ContactChannel.phone
        : ContactChannel.email;
    final contactError = validateContact(channelValue, value);
    if (contactError != null) {
      _showError(contactError);
      return;
    }
    if (!RegExp(r'^\d{6}$').hasMatch(code.text.trim())) {
      _showError('请输入六位验证码');
      return;
    }
    final passwordError = validateNewPassword(password.text);
    if (passwordError != null || password.text != confirm.text) {
      _showError(passwordError ?? '两次输入的密码不一致');
      return;
    }
    setState(() => loading = true);
    try {
      if (ref.read(appConfigProvider).isDemo) {
        await Future<void>.delayed(const Duration(milliseconds: 180));
      } else {
        await ref
            .read(authRepositoryProvider)
            .register(
              channel: channelValue,
              value: value,
              code: code.text.trim(),
              password: password.text,
              countryCode: channelValue == ContactChannel.phone ? '+86' : null,
            );
      }
      if (!mounted) return;
      final store = ref.read(companionStoreProvider);
      store.signIn();
      if (!ref.read(appConfigProvider).isDemo) {
        await store.bootstrap(
          profileRepository: ref.read(profileRepositoryProvider),
          deviceRepository: ref.read(deviceRepositoryProvider),
          conversationRepository: ref.read(conversationRepositoryProvider),
          preferences: ref.read(preferencesStoreProvider),
        );
      }
      if (mounted) context.go(store.profiles.isEmpty ? '/onboarding' : '/chat');
    } catch (error) {
      if (mounted) _showError(_errorText(error));
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> _sendCode() async {
    final value = contact.text.trim();
    final channelValue = channel == 'phone'
        ? ContactChannel.phone
        : ContactChannel.email;
    final contactError = validateContact(channelValue, value);
    if (contactError != null) {
      _showError(contactError);
      return;
    }
    setState(() {
      sendingCode = true;
      errorMessage = null;
    });
    try {
      final seconds = ref.read(appConfigProvider).isDemo
          ? 60
          : (await ref
                    .read(authRepositoryProvider)
                    .requestCode(
                      channel: channelValue,
                      value: value,
                      purpose: CodePurpose.register,
                      countryCode: channelValue == ContactChannel.phone
                          ? '+86'
                          : null,
                    ))
                .retryAfterSeconds;
      if (!mounted) return;
      _startCooldown(seconds);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('验证码已发送，请查收')));
    } catch (error) {
      if (mounted) _showError(_errorText(error));
    } finally {
      if (mounted) setState(() => sendingCode = false);
    }
  }

  void _startCooldown(int seconds) {
    _cooldownTimer?.cancel();
    setState(() => retryAfterSeconds = seconds.clamp(0, 300));
    if (retryAfterSeconds == 0) return;
    _cooldownTimer = Timer(Duration(seconds: retryAfterSeconds), () {
      if (mounted) setState(() => retryAfterSeconds = 0);
    });
  }

  void _showError(String message) {
    if (!mounted) return;
    setState(() => errorMessage = message);
  }

  String _errorText(Object error) {
    final text = error.toString();
    return text.startsWith('ApiException(') ? '请求失败，请稍后重试' : text;
  }
}
