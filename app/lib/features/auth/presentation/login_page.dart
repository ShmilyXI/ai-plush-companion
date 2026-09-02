import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/providers/core_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../domain/auth_models.dart';
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
  bool sendingCode = false;
  String? errorMessage;
  int retryAfterSeconds = 0;
  Timer? _cooldownTimer;
  @override
  void dispose() {
    _contact.dispose();
    _password.dispose();
    _code.dispose();
    _cooldownTimer?.cancel();
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
                        onPressed: sendingCode || retryAfterSeconds > 0
                            ? null
                            : _sendCode,
                        child: Text(
                          retryAfterSeconds > 0
                              ? '${retryAfterSeconds}s'
                              : '获取验证码',
                        ),
                      ),
                    ),
                  ],
                ),
              const SizedBox(height: 20),
              if (errorMessage != null) ...[
                Text(
                  errorMessage!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
                const SizedBox(height: 10),
              ],
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
    final value = _contact.text.trim();
    final channelValue = channel == 'phone'
        ? ContactChannel.phone
        : ContactChannel.email;
    final contactError = validateContact(channelValue, value);
    if (contactError != null) {
      _showError(contactError);
      return;
    }
    if (mode == 'password' && _password.text.isEmpty) {
      _showError('请输入密码');
      return;
    }
    if (mode == 'code' && !RegExp(r'^\d{6}$').hasMatch(_code.text.trim())) {
      _showError('请输入六位验证码');
      return;
    }
    setState(() => loading = true);
    errorMessage = null;
    try {
      if (ref.read(appConfigProvider).isDemo) {
        await Future<void>.delayed(const Duration(milliseconds: 180));
      } else {
        final repository = ref.read(authRepositoryProvider);
        if (mode == 'password') {
          await repository.passwordLogin(
            channel: channelValue,
            value: value,
            password: _password.text,
            countryCode: channelValue == ContactChannel.phone ? '+86' : null,
          );
        } else {
          await repository.codeLogin(
            channel: channelValue,
            value: value,
            code: _code.text.trim(),
            countryCode: channelValue == ContactChannel.phone ? '+86' : null,
          );
        }
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
    final value = _contact.text.trim();
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
                      purpose: CodePurpose.login,
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
