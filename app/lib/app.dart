import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

class CompanionApp extends StatelessWidget {
  const CompanionApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp.router(
    title: 'AI 陪伴',
    theme: ThemeData(
      colorScheme: ColorScheme.fromSeed(seedColor: Colors.teal),
      useMaterial3: true,
    ),
    routerConfig: GoRouter(
      initialLocation: '/health',
      routes: [
        GoRoute(
          path: '/health',
          builder: (context, state) => Scaffold(
            appBar: AppBar(title: Text('AI 陪伴')),
            body: Center(child: Text('服务正常')),
          ),
        ),
      ],
    ),
  );
}
