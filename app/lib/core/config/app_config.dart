import 'package:flutter/foundation.dart';

class AppConfig {
  const AppConfig({
    required this.apiBaseUrl,
    this.runtimeWsOrigin,
    this.isDemo = false,
  });

  final Uri apiBaseUrl;
  final Uri? runtimeWsOrigin;
  final bool isDemo;

  /// Replaces the authority advertised by manager-api while retaining the
  /// runtime route and query parameters. Runtime URLs may contain an
  /// internal host, whereas the configured origin is the client-reachable
  /// WebSocket entry point.
  Uri resolveRuntimeStreamUrl(Uri streamUrl) {
    return rewriteRuntimeStreamUrl(streamUrl, runtimeWsOrigin);
  }

  static Uri rewriteRuntimeStreamUrl(Uri streamUrl, Uri? origin) {
    if (origin == null) return streamUrl;
    final originScheme = origin.scheme.toLowerCase();
    if (origin.host.isEmpty ||
        !origin.hasScheme ||
        !const {'http', 'https', 'ws', 'wss'}.contains(originScheme)) {
      throw StateError('RUNTIME_WS_ORIGIN must be a valid absolute URL');
    }
    final scheme = {'https', 'wss'}.contains(originScheme) ? 'wss' : 'ws';
    return Uri(
      scheme: scheme,
      host: origin.host,
      port: origin.hasPort ? origin.port : null,
      path: streamUrl.path,
      query: streamUrl.hasQuery ? streamUrl.query : null,
      fragment: streamUrl.hasFragment ? streamUrl.fragment : null,
    );
  }

  factory AppConfig.fromEnvironment() {
    final rawApiBaseUrl = const String.fromEnvironment('API_BASE_URL');
    if (rawApiBaseUrl.isEmpty) {
      if (kReleaseMode) throw StateError('API_BASE_URL is required');
      return AppConfig(
        apiBaseUrl: Uri.parse('http://127.0.0.1:8080/xiaozhi'),
        isDemo: true,
      );
    }
    final apiBaseUrl = Uri.tryParse(rawApiBaseUrl);
    if (apiBaseUrl == null ||
        !apiBaseUrl.hasScheme ||
        apiBaseUrl.host.isEmpty) {
      throw StateError('API_BASE_URL must be a valid absolute URL');
    }
    if (kReleaseMode && apiBaseUrl.scheme != 'https') {
      throw StateError('API_BASE_URL must use HTTPS in release builds');
    }
    final rawWsOrigin = const String.fromEnvironment('RUNTIME_WS_ORIGIN');
    final wsOrigin = rawWsOrigin.isEmpty ? null : Uri.tryParse(rawWsOrigin);
    if (rawWsOrigin.isNotEmpty &&
        (wsOrigin == null ||
            !wsOrigin.hasScheme ||
            wsOrigin.host.isEmpty ||
            !const {
              'http',
              'https',
              'ws',
              'wss',
            }.contains(wsOrigin.scheme.toLowerCase()))) {
      throw StateError('RUNTIME_WS_ORIGIN must be a valid absolute URL');
    }
    return AppConfig(apiBaseUrl: apiBaseUrl, runtimeWsOrigin: wsOrigin);
  }
}
