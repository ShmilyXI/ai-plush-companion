import 'api_exception.dart';

class ApiResult {
  const ApiResult._();

  static T unwrap<T>(
    Map<String, dynamic> json,
    T Function(Object? data) decode,
  ) {
    final code = json['code'];
    final message = json['msg'];
    if (code is! int || message is! String || !json.containsKey('data')) {
      throw const ApiException.protocol('invalid api envelope');
    }
    if (code != 0) throw ApiException.business(code, message, json['data']);
    return decode(json['data']);
  }
}
