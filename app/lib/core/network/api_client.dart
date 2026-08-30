import 'dart:async';

import 'package:dio/dio.dart';

import '../storage/secure_store.dart';
import 'api_exception.dart';
import 'api_result.dart';

typedef AuthExpiredCallback = void Function();

class ApiClient {
  ApiClient({
    required Uri baseUrl,
    required SecureStore secureStore,
    Dio? dio,
    this.onAuthExpired,
  }) : _secureStore = secureStore,
       _dio =
           dio ??
           Dio(
             BaseOptions(
               baseUrl: baseUrl.toString(),
               connectTimeout: const Duration(seconds: 12),
               receiveTimeout: const Duration(seconds: 30),
             ),
           ) {
    _dio.interceptors.add(
      InterceptorsWrapper(onRequest: _onRequest, onError: _onError),
    );
  }

  final Dio _dio;
  final SecureStore _secureStore;
  final AuthExpiredCallback? onAuthExpired;
  Future<StoredSession?>? _refreshing;

  Dio get dio => _dio;

  Future<void> _onRequest(
    RequestOptions options,
    RequestInterceptorHandler handler,
  ) async {
    if (options.extra['skipAuth'] == true) {
      return handler.next(options);
    }
    final session = await _secureStore.read();
    if (session != null && session.accessToken.isNotEmpty) {
      options.headers['Authorization'] = 'Bearer ${session.accessToken}';
    }
    handler.next(options);
  }

  Future<void> _onError(
    DioException error,
    ErrorInterceptorHandler handler,
  ) async {
    if (error.response?.statusCode != 401 ||
        error.requestOptions.extra['retried'] == true ||
        error.requestOptions.extra['skipAuth'] == true) {
      return handler.next(error);
    }
    final session = await _secureStore.read();
    if (session == null) {
      onAuthExpired?.call();
      return handler.next(error);
    }
    final refreshed = await _refresh(session);
    if (refreshed == null) {
      await _secureStore.clear();
      onAuthExpired?.call();
      return handler.next(error);
    }
    final request = error.requestOptions;
    request.extra['retried'] = true;
    request.headers['Authorization'] = 'Bearer ${refreshed.accessToken}';
    try {
      final response = await _dio.fetch(request);
      handler.resolve(response);
    } on DioException catch (retryError) {
      handler.next(retryError);
    }
  }

  Future<StoredSession?> _refresh(StoredSession current) {
    return _refreshing ??= _performRefresh(
      current,
    ).whenComplete(() => _refreshing = null);
  }

  Future<StoredSession?> _performRefresh(StoredSession current) async {
    try {
      final response = await _dio.post(
        '/app/auth/refresh',
        data: {'refreshToken': current.refreshToken},
        options: Options(extra: {'skipAuth': true}),
      );
      final data = ApiResult.unwrap<Map<String, dynamic>>(
        Map<String, dynamic>.from(response.data as Map),
        (value) => Map<String, dynamic>.from(value as Map),
      );
      final next = StoredSession(
        accessToken: data['accessToken'] as String,
        refreshToken: data['refreshToken'] as String,
        accessExpiresAt: DateTime.parse(data['accessExpiresAt'] as String),
        refreshExpiresAt: DateTime.parse(data['refreshExpiresAt'] as String),
        userId: (data['userId'] ?? data['user']?['id']).toString(),
      );
      await _secureStore.writeSession(next);
      return next;
    } catch (_) {
      return null;
    }
  }

  Future<T> request<T>(
    Future<Response<dynamic>> Function(Dio dio) call,
    T Function(Object? data) decode,
  ) async {
    try {
      final response = await call(_dio);
      final map = Map<String, dynamic>.from(response.data as Map);
      return ApiResult.unwrap(map, decode);
    } on ApiException {
      rethrow;
    } on DioException catch (error) {
      throw ApiException.transport(
        error.message ?? 'network request failed',
        statusCode: error.response?.statusCode,
        data: error.response?.data,
      );
    }
  }
}
