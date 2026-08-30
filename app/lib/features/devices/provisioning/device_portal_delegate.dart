import 'package:webview_flutter/webview_flutter.dart';

class DevicePortalDelegate {
  DevicePortalDelegate({required this.onPortalSucceeded, this.onExitRequested});
  final void Function() onPortalSucceeded;
  final Future<void> Function()? onExitRequested;
  bool _completionObserved = false;
  bool _completionNotified = false;
  Future<void>? _completionFuture;

  NavigationDecision handleNavigation(NavigationRequest request) {
    final uri = Uri.tryParse(request.url);
    if (uri == null || uri.scheme != 'http' || uri.host != '192.168.4.1') {
      return NavigationDecision.prevent;
    }
    if (uri.path == '/done.html') {
      _completionObserved = true;
      _completionFuture ??= _completeAfterExit();
    }
    return NavigationDecision.navigate;
  }

  Future<void> finish() async {
    if (!_completionObserved) return;
    await (_completionFuture ??= _completeAfterExit());
  }

  Future<void> _completeAfterExit() async {
    if (_completionNotified) return;
    await onExitRequested?.call();
    _completionNotified = true;
    onPortalSucceeded();
  }
}
