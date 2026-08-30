class ApiException implements Exception {
  const ApiException.protocol(this.message)
    : code = null,
      statusCode = null,
      data = null;
  const ApiException.business(this.code, this.message, [this.data])
    : statusCode = null;
  const ApiException.transport(this.message, {this.statusCode, this.data})
    : code = null;

  final int? code;
  final int? statusCode;
  final String message;
  final Object? data;

  bool get isUnauthorized => statusCode == 401 || code == 401;
  bool get isConflict => statusCode == 409 || code == 409;
  bool get isMemoryDisabled =>
      message == 'memory_disabled' || data == 'memory_disabled';

  @override
  String toString() => 'ApiException($code/$statusCode): $message';
}
