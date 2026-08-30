import 'package:dio/dio.dart';

import '../../../core/network/api_client.dart';
import '../../../core/storage/secure_store.dart';
import '../domain/auth_models.dart';

class AuthRepository {
  const AuthRepository(this.api, this.secureStore);
  final ApiClient api;
  final SecureStore secureStore;

  Future<Map<String, dynamic>> sendCode({
    required ContactChannel channel,
    required String value,
    required CodePurpose purpose,
    String? countryCode,
  }) => api.request(
    (dio) => dio.post(
      '/app/auth/code',
      options: Options(extra: {'skipAuth': true}),
      data: {
        'channel': channel.name,
        'value': value,
        'purpose': purpose.name,
        if (countryCode != null) 'countryCode': countryCode,
      },
    ),
    (data) => Map<String, dynamic>.from(data as Map),
  );

  Future<AuthSession> passwordLogin({
    required ContactChannel channel,
    required String value,
    required String password,
    String? countryCode,
  }) => _token(
    () => api.request(
      (dio) => dio.post(
        '/app/auth/password-login',
        options: Options(extra: {'skipAuth': true}),
        data: {
          'channel': channel.name,
          'value': value,
          'password': password,
          if (countryCode != null) 'countryCode': countryCode,
        },
      ),
      _decodeToken,
    ),
  );

  Future<AuthSession> codeLogin({
    required ContactChannel channel,
    required String value,
    required String code,
    String? countryCode,
  }) => _token(
    () => api.request(
      (dio) => dio.post(
        '/app/auth/code-login',
        options: Options(extra: {'skipAuth': true}),
        data: {
          'channel': channel.name,
          'value': value,
          'code': code,
          if (countryCode != null) 'countryCode': countryCode,
        },
      ),
      _decodeToken,
    ),
  );

  Future<AuthSession> register({
    required ContactChannel channel,
    required String value,
    required String code,
    required String password,
    String? countryCode,
  }) => _token(
    () => api.request(
      (dio) => dio.post(
        '/app/auth/register',
        options: Options(extra: {'skipAuth': true}),
        data: {
          'channel': channel.name,
          'value': value,
          'code': code,
          'password': password,
          if (countryCode != null) 'countryCode': countryCode,
        },
      ),
      _decodeToken,
    ),
  );

  Future<void> resetPassword({
    required ContactChannel channel,
    required String value,
    required String code,
    required String password,
    String? countryCode,
  }) => api.request(
    (dio) => dio.post(
      '/app/auth/reset-password',
      options: Options(extra: {'skipAuth': true}),
      data: {
        'channel': channel.name,
        'value': value,
        'code': code,
        'password': password,
        if (countryCode != null) 'countryCode': countryCode,
      },
    ),
    (_) {},
  );

  Future<void> logout() async {
    try {
      await api.request((dio) => dio.post('/app/auth/logout'), (_) {});
    } finally {
      await secureStore.clear();
    }
  }

  Future<AuthSession> _token(Future<AuthSession> Function() operation) =>
      operation();

  AuthSession _decodeToken(Object? value) {
    final map = Map<String, dynamic>.from(value as Map);
    final access = map['accessToken'] as String;
    final refresh = map['refreshToken'] as String;
    final accessExpiry = DateTime.parse(map['accessExpiresAt'] as String);
    final refreshExpiry = DateTime.parse(map['refreshExpiresAt'] as String);
    final userId = (map['userId'] ?? (map['user'] as Map?)?['id']).toString();
    final session = AuthSession(
      accessToken: access,
      refreshToken: refresh,
      userId: userId,
    );
    secureStore.writeSession(
      StoredSession(
        accessToken: access,
        refreshToken: refresh,
        accessExpiresAt: accessExpiry,
        refreshExpiresAt: refreshExpiry,
        userId: userId,
      ),
    );
    return session;
  }
}
