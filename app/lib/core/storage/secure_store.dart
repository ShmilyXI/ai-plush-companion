import 'dart:async';

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
  Future<void> _mutationTail = Future<void>.value();
  static const _access = 'app.access_token';
  static const _refresh = 'app.refresh_token';
  static const _accessExpiry = 'app.access_expires_at';
  static const _refreshExpiry = 'app.refresh_expires_at';
  static const _userId = 'app.user_id';

  Future<void> writeSession(StoredSession session) async {
    await _withMutation(() => _writeSession(session));
  }

  /// Writes [next] only when the persisted session still matches [expected].
  ///
  /// Refresh responses can arrive after a user has signed in as another
  /// account.  The compare and write must share the same mutation queue so a
  /// late response cannot overwrite that newer session.
  Future<bool> writeSessionIfCurrent(
    StoredSession expected,
    StoredSession next,
  ) {
    return _withMutation(() async {
      final current = await read();
      if (!_sameSession(current, expected)) return false;
      await _writeSession(next);
      return true;
    });
  }

  /// Clears the session only when it still belongs to [expected].
  Future<bool> clearIfCurrent(StoredSession expected) {
    return _withMutation(() async {
      final current = await read();
      if (!_sameSession(current, expected)) return false;
      await _clear();
      return true;
    });
  }

  Future<void> _writeSession(StoredSession session) async {
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
    await _withMutation(_clear);
  }

  Future<void> _clear() async {
    await Future.wait([
      _storage.delete(key: _access),
      _storage.delete(key: _refresh),
      _storage.delete(key: _accessExpiry),
      _storage.delete(key: _refreshExpiry),
      _storage.delete(key: _userId),
    ]);
  }

  Future<T> _withMutation<T>(Future<T> Function() action) async {
    final previous = _mutationTail;
    final gate = Completer<void>();
    _mutationTail = gate.future;
    await previous;
    try {
      return await action();
    } finally {
      gate.complete();
    }
  }

  static bool _sameSession(StoredSession? left, StoredSession right) {
    return left != null &&
        left.accessToken == right.accessToken &&
        left.refreshToken == right.refreshToken &&
        left.userId == right.userId &&
        left.accessExpiresAt == right.accessExpiresAt &&
        left.refreshExpiresAt == right.refreshExpiresAt;
  }
}
