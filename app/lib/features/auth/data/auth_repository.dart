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
  }) {
    return api.request<Map<String, dynamic>>(
      (dio) => dio.post(
        '/app/auth/code',
        options: Options(extra: {'skipAuth': true}),
        data: {
          'channel': channel.name,
          'value': _contactValue(channel, value, countryCode),
          'purpose': purpose.name,
          if (countryCode != null) 'countryCode': countryCode,
        },
      ),
      _map,
    );
  }

  Future<AuthCodeChallenge> requestCode({
    required ContactChannel channel,
    required String value,
    required CodePurpose purpose,
    String? countryCode,
  }) async {
    final response = await sendCode(
      channel: channel,
      value: value,
      purpose: purpose,
      countryCode: countryCode,
    );
    return AuthCodeChallenge.fromMap(response);
  }

  Future<AuthSession> passwordLogin({
    required ContactChannel channel,
    required String value,
    required String password,
    String? countryCode,
  }) {
    return _requestToken(
      (dio) => dio.post(
        '/app/auth/password-login',
        options: Options(extra: {'skipAuth': true}),
        data: {
          'channel': channel.name,
          'value': _contactValue(channel, value, countryCode),
          'password': password,
          if (countryCode != null) 'countryCode': countryCode,
        },
      ),
    );
  }

  Future<AuthSession> codeLogin({
    required ContactChannel channel,
    required String value,
    required String code,
    String? countryCode,
  }) {
    return _requestToken(
      (dio) => dio.post(
        '/app/auth/code-login',
        options: Options(extra: {'skipAuth': true}),
        data: {
          'channel': channel.name,
          'value': _contactValue(channel, value, countryCode),
          'code': code,
          if (countryCode != null) 'countryCode': countryCode,
        },
      ),
    );
  }

  Future<AuthSession> register({
    required ContactChannel channel,
    required String value,
    required String code,
    required String password,
    String? countryCode,
  }) {
    return _requestToken(
      (dio) => dio.post(
        '/app/auth/register',
        options: Options(extra: {'skipAuth': true}),
        data: {
          'channel': channel.name,
          'value': _contactValue(channel, value, countryCode),
          'code': code,
          'password': password,
          if (countryCode != null) 'countryCode': countryCode,
        },
      ),
    );
  }

  Future<AuthSession> refresh(String refreshToken) async {
    try {
      return await _requestToken(
        (dio) => dio.post(
          '/app/auth/refresh',
          options: Options(extra: {'skipAuth': true}),
          data: {'refreshToken': refreshToken},
        ),
      );
    } catch (_) {
      await secureStore.clear();
      rethrow;
    }
  }

  Future<void> bindContact({
    required ContactChannel channel,
    required String value,
    required String code,
    String? countryCode,
  }) async {
    await api.request<Object?>(
      (dio) => dio.post(
        '/app/account/contacts',
        data: {
          'channel': channel.name,
          'value': _contactValue(channel, value, countryCode),
          'code': code,
          if (countryCode != null) 'countryCode': countryCode,
        },
      ),
      (_) => null,
    );
  }

  Future<void> resetPassword({
    required ContactChannel channel,
    required String value,
    required String code,
    required String password,
    String? countryCode,
  }) {
    return api.request<Object?>(
      (dio) => dio.post(
        '/app/auth/reset-password',
        options: Options(extra: {'skipAuth': true}),
        data: {
          'channel': channel.name,
          'value': _contactValue(channel, value, countryCode),
          'code': code,
          'password': password,
          if (countryCode != null) 'countryCode': countryCode,
        },
      ),
      (_) => null,
    );
  }

  Future<void> logout() async {
    try {
      await api.request<Object?>(
        (dio) => dio.post('/app/auth/logout'),
        (_) => null,
      );
    } finally {
      await secureStore.clear();
    }
  }

  Future<AuthSession> _requestToken(
    Future<Response<dynamic>> Function(Dio dio) call,
  ) async {
    final map = await api.request(call, _map);
    final access = _requiredString(map, 'accessToken');
    final refresh = _requiredString(map, 'refreshToken');
    final accessExpiry = _requiredDate(map, 'accessExpiresAt');
    final refreshExpiry = _requiredDate(map, 'refreshExpiresAt');
    final rawUserId = map['userId'] ?? (map['user'] as Map?)?['id'];
    if (rawUserId == null || rawUserId.toString().isEmpty) {
      throw const FormatException('认证响应缺少用户标识');
    }
    final userId = rawUserId.toString();
    await secureStore.writeSession(
      StoredSession(
        accessToken: access,
        refreshToken: refresh,
        accessExpiresAt: accessExpiry,
        refreshExpiresAt: refreshExpiry,
        userId: userId,
      ),
    );
    return AuthSession(
      accessToken: access,
      refreshToken: refresh,
      userId: userId,
    );
  }

  static Map<String, dynamic> _map(Object? data) {
    if (data is! Map) throw const FormatException('认证响应格式无效');
    return Map<String, dynamic>.from(data);
  }

  static String _requiredString(Map<String, dynamic> map, String key) {
    final value = map[key];
    if (value is! String || value.isEmpty) {
      throw FormatException('认证响应缺少 $key');
    }
    return value;
  }

  static DateTime _requiredDate(Map<String, dynamic> map, String key) {
    final value = DateTime.tryParse(map[key]?.toString() ?? '');
    if (value == null) throw FormatException('认证响应缺少 $key');
    return value;
  }

  static String _contactValue(
    ContactChannel channel,
    String value,
    String? countryCode,
  ) {
    final clean = value.trim();
    if (channel == ContactChannel.email) return clean.toLowerCase();
    final phone = clean.replaceAll(RegExp(r'[\s()\-]'), '');
    if (phone.startsWith('+')) return phone;
    final rawPrefix = countryCode?.trim();
    if (rawPrefix == null || rawPrefix.isEmpty) return phone;
    final prefix = rawPrefix.startsWith('+') ? rawPrefix : '+$rawPrefix';
    return '$prefix${phone.replaceFirst(RegExp(r'^0+'), '')}';
  }
}
