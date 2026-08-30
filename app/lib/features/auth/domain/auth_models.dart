enum ContactChannel { phone, email }

enum CodePurpose { login, register, reset, bind }

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
