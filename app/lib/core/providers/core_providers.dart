import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'companion_store.dart';

final companionStoreProvider = ChangeNotifierProvider<CompanionStore>((ref) {
  return CompanionStore();
});
