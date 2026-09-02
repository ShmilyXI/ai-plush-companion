import 'dart:typed_data';

import 'package:ai_plush_companion/core/network/api_client.dart';
import 'package:ai_plush_companion/core/storage/secure_store.dart';
import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

typedef FakeResponseFactory = ResponseBody Function(RequestOptions options);

/// A tiny in-process adapter for repository contract tests.
class FakeHttpClientAdapter implements HttpClientAdapter {
  FakeHttpClientAdapter(this.factory);

  final FakeResponseFactory factory;
  final requests = <RequestOptions>[];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    requests.add(options);
    return factory(options);
  }

  @override
  void close({bool force = false}) {}
}

ResponseBody jsonResponse(String body, {int statusCode = 200}) {
  return ResponseBody.fromString(
    body,
    statusCode,
    headers: <String, List<String>>{
      Headers.contentTypeHeader: <String>['application/json'],
    },
  );
}

ApiClient testApiClient(FakeHttpClientAdapter adapter) {
  final dio = Dio(BaseOptions(baseUrl: 'https://api.example.test/xiaozhi'))
    ..httpClientAdapter = adapter;
  return ApiClient(
    baseUrl: Uri.parse('https://api.example.test/xiaozhi'),
    secureStore: SecureStore(storage: _MemorySecureStorage()),
    dio: dio,
  );
}

class _MemorySecureStorage extends FlutterSecureStorage {
  final _values = <String, String>{};

  @override
  Future<String?> read({
    required String key,
    AppleOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    AppleOptions? mOptions,
    WindowsOptions? wOptions,
  }) async => _values[key];

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
      _values.remove(key);
    } else {
      _values[key] = value;
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
    _values.remove(key);
  }
}
