import 'package:flutter/material.dart';

class ContactModeToggle extends StatelessWidget {
  const ContactModeToggle({
    super.key,
    required this.channel,
    required this.onChanged,
  });
  final String channel;
  final ValueChanged<String> onChanged;
  @override
  Widget build(BuildContext context) => SegmentedButton<String>(
    segments: const [
      ButtonSegment(
        value: 'phone',
        label: Text('手机号'),
        icon: Icon(Icons.phone_outlined),
      ),
      ButtonSegment(
        value: 'email',
        label: Text('邮箱'),
        icon: Icon(Icons.mail_outline),
      ),
    ],
    selected: {channel},
    onSelectionChanged: (value) => onChanged(value.first),
  );
}
