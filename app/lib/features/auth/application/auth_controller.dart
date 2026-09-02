import 'dart:async';

import 'package:flutter/foundation.dart';

import '../data/auth_repository.dart';
import '../domain/auth_models.dart';

enum AuthStatus {
  signedOut,
  sendingCode,
  codeSent,
  authenticating,
  authenticated,
  failure,
}

class AuthController extends ChangeNotifier {
  AuthStatus status = AuthStatus.signedOut;
  String? errorMessage;
  String? challengeId;
  int retryAfterSeconds = 0;
  Timer? _retryTimer;
  int _requestId = 0;

  Future<void> sendCode(Future<String> Function() request) async {
    final requestId = ++_requestId;
    _retryTimer?.cancel();
    status = AuthStatus.sendingCode;
    errorMessage = null;
    challengeId = null;
    notifyListeners();
    try {
      final challenge = await request();
      if (requestId != _requestId) return;
      challengeId = challenge;
      _startRetryCountdown(60);
      status = AuthStatus.codeSent;
    } catch (error) {
      if (requestId != _requestId) return;
      status = AuthStatus.failure;
      errorMessage = error.toString();
    }
    notifyListeners();
  }

  Future<AuthCodeChallenge?> requestVerificationCode(
    AuthRepository repository, {
    required ContactChannel channel,
    required String value,
    required CodePurpose purpose,
    String? countryCode,
  }) async {
    final requestId = ++_requestId;
    _retryTimer?.cancel();
    status = AuthStatus.sendingCode;
    errorMessage = null;
    challengeId = null;
    notifyListeners();
    try {
      final challenge = await repository.requestCode(
        channel: channel,
        value: value,
        purpose: purpose,
        countryCode: countryCode,
      );
      if (requestId != _requestId) return null;
      challengeId = challenge.challengeId;
      _startRetryCountdown(challenge.retryAfterSeconds);
      status = AuthStatus.codeSent;
      notifyListeners();
      return challenge;
    } catch (error) {
      if (requestId != _requestId) return null;
      status = AuthStatus.failure;
      errorMessage = error.toString();
      notifyListeners();
      return null;
    }
  }

  Future<void> authenticate(Future<void> Function() request) async {
    status = AuthStatus.authenticating;
    errorMessage = null;
    notifyListeners();
    try {
      await request();
      status = AuthStatus.authenticated;
    } catch (error) {
      status = AuthStatus.failure;
      errorMessage = error.toString();
    }
    notifyListeners();
  }

  void signOut() {
    _requestId++;
    _retryTimer?.cancel();
    retryAfterSeconds = 0;
    status = AuthStatus.signedOut;
    errorMessage = null;
    challengeId = null;
    notifyListeners();
  }

  void _startRetryCountdown(int seconds) {
    _retryTimer?.cancel();
    retryAfterSeconds = seconds.clamp(0, 300).toInt();
    if (retryAfterSeconds == 0) return;
    _retryTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (retryAfterSeconds <= 1) {
        timer.cancel();
        retryAfterSeconds = 0;
      } else {
        retryAfterSeconds -= 1;
      }
      notifyListeners();
    });
  }

  @override
  void dispose() {
    _retryTimer?.cancel();
    super.dispose();
  }
}
