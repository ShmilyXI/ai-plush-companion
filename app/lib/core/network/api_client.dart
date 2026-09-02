import 'dart:async';

import 'package:dio/dio.dart';

import '../storage/secure_store.dart';
import 'api_exception.dart';
import 'api_result.dart';

typedef AuthExpiredCallback = FutureOr<void> Function();

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
  final Map<String, Future<StoredSession?>> _refreshing = {};

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
      await onAuthExpired?.call();
      return handler.next(error);
    }
    final refreshed = await _refresh(session);
    if (refreshed == null) {
      final current = await _secureStore.read();
      if (current != null && !_sameSession(current, session)) {
        await _replay(error, current, handler);
        return;
      }
      if (await _secureStore.clearIfCurrent(session)) {
        await onAuthExpired?.call();
      } else {
        // The account may have changed between the read above and the
        // compare-and-clear. Re-read before surfacing the original 401 so a
        // concurrent login is not mistaken for an expired session.
        final latest = await _secureStore.read();
        if (latest != null && !_sameSession(latest, session)) {
          await _replay(error, latest, handler);
          return;
        }
      }
      return handler.next(error);
    }
    final latest = await _secureStore.read();
    await _replay(
      error,
      latest != null && !_sameSession(latest, refreshed) ? latest : refreshed,
      handler,
    );
  }

  Future<void> _replay(
    DioException error,
    StoredSession session,
    ErrorInterceptorHandler handler,
  ) async {
    final request = error.requestOptions;
    request.extra['retried'] = true;
    request.headers['Authorization'] = 'Bearer ${session.accessToken}';
    try {
      final response = await _dio.fetch(request);
      handler.resolve(response);
    } on DioException catch (retryError) {
      handler.next(retryError);
    }
  }

  Future<StoredSession?> _refresh(StoredSession current) async {
    final key = _sessionKey(current);
    final existing = _refreshing[key];
    if (existing != null) return existing;
    final future = _performRefresh(current);
    _refreshing[key] = future;
    try {
      return await future;
    } finally {
      if (identical(_refreshing[key], future)) {
        _refreshing.remove(key);
      }
    }
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
      if (await _secureStore.writeSessionIfCurrent(current, next)) {
        return next;
      }
      // A newer login or refresh won the compare-and-set.  Use that session
      // for the one retry instead of returning the stale refresh response.
      return await _secureStore.read();
    } catch (_) {
      return null;
    }
  }

  static String _sessionKey(StoredSession session) {
    return '${session.userId}\u0000${session.refreshToken}';
  }

  static bool _sameSession(StoredSession left, StoredSession right) {
    return left.accessToken == right.accessToken &&
        left.refreshToken == right.refreshToken &&
        left.userId == right.userId &&
        left.accessExpiresAt == right.accessExpiresAt &&
        left.refreshExpiresAt == right.refreshExpiresAt;
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
