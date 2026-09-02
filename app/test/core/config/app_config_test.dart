import 'package:ai_plush_companion/core/config/app_config.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('rewrites the runtime websocket origin and preserves the route', () {
    final config = AppConfig(
      apiBaseUrl: Uri.parse('https://api.example.test/xiaozhi'),
      runtimeWsOrigin: Uri.parse('https://edge.example.test:9443'),
    );

    expect(
      config.resolveRuntimeStreamUrl(
        Uri.parse('ws://10.0.0.8:8000/xiaozhi/v1/?session=abc'),
      ),
      Uri.parse('wss://edge.example.test:9443/xiaozhi/v1/?session=abc'),
    );
  });

  test('keeps the server runtime URL when no override is configured', () {
    final url = Uri.parse('wss://runtime.example.test/stream');
    final config = AppConfig(apiBaseUrl: Uri.parse('https://api.example.test'));

    expect(config.resolveRuntimeStreamUrl(url), url);
  });
}
