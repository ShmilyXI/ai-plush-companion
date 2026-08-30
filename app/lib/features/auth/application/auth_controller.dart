import 'package:flutter/foundation.dart';

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

  Future<void> sendCode(Future<String> Function() request) async {
    status = AuthStatus.sendingCode;
    errorMessage = null;
    notifyListeners();
    try {
      challengeId = await request();
      retryAfterSeconds = 60;
      status = AuthStatus.codeSent;
    } catch (error) {
      status = AuthStatus.failure;
      errorMessage = error.toString();
    }
    notifyListeners();
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
    status = AuthStatus.signedOut;
    challengeId = null;
    notifyListeners();
  }
}
