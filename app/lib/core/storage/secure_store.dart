import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class StoredSession {
  const StoredSession({
    required this.accessToken,
    required this.refreshToken,
    required this.accessExpiresAt,
    required this.refreshExpiresAt,
    required this.userId,
  });
  final String accessToken;
  final String refreshToken;
  final DateTime accessExpiresAt;
  final DateTime refreshExpiresAt;
  final String userId;
}

class SecureStore {
  SecureStore({FlutterSecureStorage? storage})
    : _storage = storage ?? const FlutterSecureStorage();
  final FlutterSecureStorage _storage;
  static const _access = 'app.access_token';
  static const _refresh = 'app.refresh_token';
  static const _accessExpiry = 'app.access_expires_at';
  static const _refreshExpiry = 'app.refresh_expires_at';
  static const _userId = 'app.user_id';

  Future<void> writeSession(StoredSession session) async {
    await Future.wait([
      _storage.write(key: _access, value: session.accessToken),
      _storage.write(key: _refresh, value: session.refreshToken),
      _storage.write(
        key: _accessExpiry,
        value: session.accessExpiresAt.toIso8601String(),
      ),
      _storage.write(
        key: _refreshExpiry,
        value: session.refreshExpiresAt.toIso8601String(),
      ),
      _storage.write(key: _userId, value: session.userId),
    ]);
  }

  Future<StoredSession?> read() async {
    final values = await Future.wait([
      _storage.read(key: _access),
      _storage.read(key: _refresh),
      _storage.read(key: _accessExpiry),
      _storage.read(key: _refreshExpiry),
      _storage.read(key: _userId),
    ]);
    if (values.any((value) => value == null || value.isEmpty)) return null;
    final accessExpiry = DateTime.tryParse(values[2]!);
    final refreshExpiry = DateTime.tryParse(values[3]!);
    if (accessExpiry == null || refreshExpiry == null) return null;
    return StoredSession(
      accessToken: values[0]!,
      refreshToken: values[1]!,
      accessExpiresAt: accessExpiry,
      refreshExpiresAt: refreshExpiry,
      userId: values[4]!,
    );
  }

  Future<void> clear() async {
    await Future.wait([
      _storage.delete(key: _access),
      _storage.delete(key: _refresh),
      _storage.delete(key: _accessExpiry),
      _storage.delete(key: _refreshExpiry),
      _storage.delete(key: _userId),
    ]);
  }
}
