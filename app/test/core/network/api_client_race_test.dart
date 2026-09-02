import 'dart:async';

import 'package:ai_plush_companion/core/network/api_client.dart';
import 'package:ai_plush_companion/core/storage/secure_store.dart';
import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';

class _MemoryStorage extends FlutterSecureStorage {
  final values = <String, String>{};

  @override
  Future<String?> read({
    required String key,
    AppleOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    AppleOptions? mOptions,
    WindowsOptions? wOptions,
  }) async => values[key];

  @override
  Future<void> write({
    required String key,
    required String? value,
    AppleOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    AppleOptions? mOptions,
    WindowsOptions? wOptions,
  }) async {
    if (value == null) {
      values.remove(key);
    } else {
      values[key] = value;
    }
  }

  @override
  Future<void> delete({
    required String key,
    AppleOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    AppleOptions? mOptions,
    WindowsOptions? wOptions,
  }) async {
    values.remove(key);
  }
}

StoredSession session(String access, String refresh, String userId) =>
    StoredSession(
      accessToken: access,
      refreshToken: refresh,
      accessExpiresAt: DateTime.now().subtract(const Duration(minutes: 1)),
      refreshExpiresAt: DateTime.now().add(const Duration(days: 1)),
      userId: userId,
    );

void main() {
  test('late refresh cannot overwrite a newer account session', () async {
    final storage = _MemoryStorage();
    final secureStore = SecureStore(storage: storage);
    await secureStore.writeSession(session('old-access', 'old-refresh', 'old'));
    final refreshStarted = Completer<void>();
    final releaseRefresh = Completer<void>();
    var protectedCalls = 0;
    final adapter = HttpClientAdapterMock((options) async {
      if (options.path.endsWith('/app/auth/refresh')) {
        if (!refreshStarted.isCompleted) refreshStarted.complete();
        await releaseRefresh.future;
        return ResponseBody.fromString(
          '{"code":0,"msg":"ok","data":{"accessToken":"refreshed-old",'
          '"refreshToken":"refreshed-refresh",'
          '"accessExpiresAt":"2099-01-01T00:00:00Z",'
          '"refreshExpiresAt":"2099-01-02T00:00:00Z","userId":"old"}}',
          200,
          headers: {
            Headers.contentTypeHeader: ['application/json'],
          },
        );
      }
      protectedCalls++;
      if (protectedCalls == 1) {
        return ResponseBody.fromString('{}', 401);
      }
      return ResponseBody.fromString(
        '{"code":0,"msg":"ok","data":{"ok":true}}',
        200,
        headers: {
          Headers.contentTypeHeader: ['application/json'],
        },
      );
    });
    final dio = Dio(BaseOptions(baseUrl: 'https://api.example.test/xiaozhi'))
      ..httpClientAdapter = adapter;
    final api = ApiClient(
      baseUrl: Uri.parse('https://api.example.test/xiaozhi'),
      secureStore: secureStore,
      dio: dio,
    );

    final request = api.request<Map<String, dynamic>>(
      (client) => client.get('/protected'),
      (value) => Map<String, dynamic>.from(value as Map),
    );
    await refreshStarted.future;
    await secureStore.writeSession(session('new-access', 'new-refresh', 'new'));
    releaseRefresh.complete();
    await request;

    expect((await secureStore.read())?.accessToken, 'new-access');
  });

  test(
    'failed refresh from an old account cannot clear a newer session',
    () async {
      final storage = _MemoryStorage();
      final secureStore = SecureStore(storage: storage);
      await secureStore.writeSession(
        session('old-access', 'old-refresh', 'old'),
      );
      final refreshStarted = Completer<void>();
      final releaseRefresh = Completer<void>();
      var protectedCalls = 0;
      var expiredCalls = 0;
      final adapter = HttpClientAdapterMock((options) async {
        if (options.path.endsWith('/app/auth/refresh')) {
          refreshStarted.complete();
          await releaseRefresh.future;
          return ResponseBody.fromString(
            '{"code":1,"msg":"expired","data":null}',
            401,
          );
        }
        protectedCalls++;
        if (protectedCalls == 1) return ResponseBody.fromString('{}', 401);
        return ResponseBody.fromString(
          '{"code":0,"msg":"ok","data":{"ok":true}}',
          200,
          headers: {
            Headers.contentTypeHeader: ['application/json'],
          },
        );
      });
      final dio = Dio(BaseOptions(baseUrl: 'https://api.example.test/xiaozhi'))
        ..httpClientAdapter = adapter;
      final api = ApiClient(
        baseUrl: Uri.parse('https://api.example.test/xiaozhi'),
        secureStore: secureStore,
        dio: dio,
        onAuthExpired: () => expiredCalls++,
      );

      final request = api.request<Map<String, dynamic>>(
        (client) => client.get('/protected'),
        (value) => Map<String, dynamic>.from(value as Map),
      );
      await refreshStarted.future;
      await secureStore.writeSession(
        session('new-access', 'new-refresh', 'new'),
      );
      releaseRefresh.complete();
      await request;

      expect(expiredCalls, 0);
      expect((await secureStore.read())?.accessToken, 'new-access');
    },
  );
}

class HttpClientAdapterMock implements HttpClientAdapter {
  HttpClientAdapterMock(this.factory);
  final Future<ResponseBody> Function(RequestOptions options) factory;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<List<int>>? requestStream,
    Future<void>? cancelFuture,
  ) => factory(options);

  @override
  void close({bool force = false}) {}
}
