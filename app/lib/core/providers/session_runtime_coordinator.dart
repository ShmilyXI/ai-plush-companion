import 'dart:async';

/// Holds account-scoped runtime cleanup callbacks without making the API
/// client depend on the chat or call providers.
class SessionRuntimeCoordinator {
  Future<void> Function()? closeChat;
  Future<void> Function()? endCall;

  Future<void> expire() async {
    final closeChat = this.closeChat;
    final endCall = this.endCall;
    // Detach both account-scoped transports before the caller clears the
    // store. A late realtime event then has no live controller to write into.
    await Future.wait<void>([
      if (closeChat != null) _run(closeChat),
      if (endCall != null) _run(endCall),
    ]);
  }

  Future<void> _run(Future<void> Function() callback) async {
    try {
      await callback();
    } catch (_) {
      // Expiry must still clear account state when a platform transport has
      // already failed during cleanup.
    }
  }
}
