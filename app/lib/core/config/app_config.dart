import 'package:flutter/foundation.dart';

class AppConfig {
  const AppConfig({required this.apiBaseUrl, this.runtimeWsOrigin});

  final Uri apiBaseUrl;
  final Uri? runtimeWsOrigin;

  factory AppConfig.fromEnvironment() {
    final rawApiBaseUrl = const String.fromEnvironment('API_BASE_URL');
    if (rawApiBaseUrl.isEmpty) throw StateError('API_BASE_URL is required');
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
        (wsOrigin == null || !wsOrigin.hasScheme || wsOrigin.host.isEmpty)) {
      throw StateError('RUNTIME_WS_ORIGIN must be a valid absolute URL');
    }
    return AppConfig(apiBaseUrl: apiBaseUrl, runtimeWsOrigin: wsOrigin);
  }
}
