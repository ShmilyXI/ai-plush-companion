import 'package:flutter/widgets.dart';

/// A small generated-localization-compatible facade for the first Chinese
/// release. All feature copy is kept here so another ARB can replace it later.
class AppLocalizations {
  const AppLocalizations();

  static const delegate = _AppLocalizationsDelegate();

  static AppLocalizations of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations) ??
        const AppLocalizations();
  }

  String get appName => '拾光陪伴';
  String get chat => '聊天';
  String get devices => '设备';
  String get profiles => '角色';
  String get account => '我的';
  String get sessions => '会话';
  String get newConversation => '新会话';
  String get memory => '长期记忆';
  String get autoPlay => '自动播放回复';
  String get send => '发送';
  String get holdToTalk => '按住说话';
  String get call => '语音通话';
  String get serviceReady => '服务正常';
}

class _AppLocalizationsDelegate
    extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  bool isSupported(Locale locale) => locale.languageCode == 'zh';

  @override
  Future<AppLocalizations> load(Locale locale) async =>
      const AppLocalizations();

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}
