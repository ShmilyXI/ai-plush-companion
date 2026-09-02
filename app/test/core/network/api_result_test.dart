import 'package:ai_plush_companion/core/network/api_exception.dart';
import 'package:ai_plush_companion/core/network/api_result.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('unwraps the manager-api success envelope', () {
    final value = ApiResult.unwrap<int>({
      'code': 0,
      'msg': 'success',
      'data': 7,
    }, (data) => data as int);
    expect(value, 7);
  });

  test('keeps business error categories distinguishable', () {
    expect(
      () => ApiResult.unwrap<Object?>({
        'code': 409,
        'msg': 'contact_conflict',
        'data': null,
      }, (data) => data),
      throwsA(
        isA<ApiException>().having(
          (error) => error.isConflict,
          'conflict',
          isTrue,
        ),
      ),
    );
    expect(
      () => ApiResult.unwrap<Object?>({
        'code': 0,
        'msg': 'success',
      }, (data) => data),
      throwsA(isA<ApiException>()),
    );
  });
}
