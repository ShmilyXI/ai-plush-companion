enum ContactChannel { phone, email }

enum CodePurpose { login, register, reset, bind }

String? validateContact(ContactChannel channel, String value) {
  final clean = value.trim();
  if (clean.isEmpty) {
    return '请输入${channel == ContactChannel.phone ? '手机号' : '邮箱'}';
  }
  if (channel == ContactChannel.phone &&
      !RegExp(r'^\+?[0-9][0-9 -]{5,18}$').hasMatch(clean)) {
    return '请输入有效的手机号';
  }
  if (channel == ContactChannel.email &&
      !RegExp(r'^[^\s@]+@[^\s@]+\.[^\s@]+$').hasMatch(clean)) {
    return '请输入有效的邮箱';
  }
  return null;
}

String? validateNewPassword(String value) {
  if (value.length < 6) return '密码至少六位';
  if (!RegExp(r'[a-z]').hasMatch(value) ||
      !RegExp(r'[A-Z]').hasMatch(value) ||
      !RegExp(r'[0-9]').hasMatch(value)) {
    return '密码需要包含大小写字母和数字';
  }
  return null;
}

class AuthCodeChallenge {
  const AuthCodeChallenge({
    required this.challengeId,
    required this.expiresAt,
    required this.retryAfterSeconds,
  });

  final String challengeId;
  final DateTime? expiresAt;
  final int retryAfterSeconds;

  factory AuthCodeChallenge.fromMap(Map<String, dynamic> map) {
    final challengeId = map['challengeId']?.toString();
    if (challengeId == null || challengeId.isEmpty) {
      throw const FormatException('验证码请求缺少 challengeId');
    }
    return AuthCodeChallenge(
      challengeId: challengeId,
      expiresAt: DateTime.tryParse(map['expiresAt']?.toString() ?? ''),
      retryAfterSeconds: _asInt(map['retryAfterSeconds']) ?? 60,
    );
  }

  static int? _asInt(Object? value) {
    if (value is int) return value;
    return int.tryParse(value?.toString() ?? '');
  }
}

class AuthSession {
  const AuthSession({
    required this.accessToken,
    required this.refreshToken,
    required this.userId,
  });
  final String accessToken;
  final String refreshToken;
  final String userId;
}
