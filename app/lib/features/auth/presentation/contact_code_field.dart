import 'dart:async';

import 'package:flutter/material.dart';

class ContactCodeField extends StatefulWidget {
  const ContactCodeField({
    super.key,
    required this.onSend,
    this.enabled = true,
  });
  final Future<bool> Function() onSend;
  final bool enabled;
  @override
  State<ContactCodeField> createState() => _ContactCodeFieldState();
}

class _ContactCodeFieldState extends State<ContactCodeField> {
  final controller = TextEditingController();
  Timer? timer;
  int remaining = 0;
  @override
  void dispose() {
    timer?.cancel();
    controller.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    if (remaining > 0 || !widget.enabled) return;
    final sent = await widget.onSend();
    if (!sent || !mounted) return;
    setState(() => remaining = 60);
    timer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      if (remaining <= 1) {
        timer?.cancel();
        setState(() => remaining = 0);
      } else {
        setState(() => remaining--);
      }
    });
  }

  @override
  Widget build(BuildContext context) => TextField(
    controller: controller,
    keyboardType: TextInputType.number,
    maxLength: 6,
    decoration: InputDecoration(
      labelText: '验证码',
      counterText: '',
      suffixIcon: TextButton(
        onPressed: _send,
        child: Text(remaining == 0 ? '获取验证码' : '${remaining}s'),
      ),
    ),
  );
}
