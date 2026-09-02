import '../../../core/network/api_client.dart';
import '../../auth/domain/auth_models.dart';

class AccountSummary {
  const AccountSummary({required this.user, required this.verifiedChannels});

  final AccountUser user;
  final List<ContactChannel> verifiedChannels;

  factory AccountSummary.fromMap(Map<String, dynamic> map) {
    final rawUser = map['user'];
    if (rawUser is! Map) throw const FormatException('账号响应缺少用户信息');
    final user = AccountUser.fromMap(Map<String, dynamic>.from(rawUser));
    final channels =
        (map['verifiedChannels'] is Iterable
                ? map['verifiedChannels'] as Iterable
                : const <Object>[])
            .map((value) => value.toString().toLowerCase())
            .map(
              (value) => value == 'phone'
                  ? ContactChannel.phone
                  : ContactChannel.email,
            )
            .toSet()
            .toList(growable: false);
    return AccountSummary(user: user, verifiedChannels: channels);
  }
}

class AccountUser {
  const AccountUser({
    required this.id,
    required this.displayName,
    required this.username,
    this.avatarUrl,
  });

  final String id;
  final String displayName;
  final String username;
  final String? avatarUrl;

  factory AccountUser.fromMap(Map<String, dynamic> map) {
    final id = map['id']?.toString();
    final username = map['username']?.toString();
    if (id == null || id.isEmpty || username == null || username.isEmpty) {
      throw const FormatException('账号响应缺少用户标识');
    }
    return AccountUser(
      id: id,
      displayName: map['displayName']?.toString() ?? '陪伴用户',
      username: username,
      avatarUrl: map['avatarUrl']?.toString(),
    );
  }
}

class AccountRepository {
  const AccountRepository(this.api);

  final ApiClient api;

  Future<AccountSummary> get() => api.request(
    (dio) => dio.get('/app/account'),
    (data) => AccountSummary.fromMap(Map<String, dynamic>.from(data as Map)),
  );

  Future<void> bindContact({
    required ContactChannel channel,
    required String value,
    required String code,
    String? countryCode,
  }) => api.request<Object?>(
    (dio) => dio.post(
      '/app/account/contacts',
      data: {
        'channel': channel.name,
        'value': value,
        'code': code,
        if (countryCode != null) 'countryCode': countryCode,
      },
    ),
    (_) => null,
  );

  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) => api.request<Object?>(
    (dio) => dio.put(
      '/app/account/password',
      data: {'currentPassword': currentPassword, 'newPassword': newPassword},
    ),
    (_) => null,
  );
}
