import 'package:flutter/material.dart';
import 'package:quick_blue_simplify/quick_blue.dart';
import 'package:quick_blue_simplify_example/permission_handler.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  List<BlueScanResult> _scanResults = [];

  @override
  void initState() {
    super.initState();
    _onBleScan();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      theme: ThemeData(useMaterial3: true),
      home: Scaffold(
        appBar: AppBar(
          title: const Text('DEMO'),
        ),
        body: Padding(
          padding: const EdgeInsets.all(16.0),
          child: Column(
            children: [
              ElevatedButton(
                onPressed: () {
                  _checkPermission();
                },
                child: const Text('搜索'),
              ),
              const SizedBox(height: 8),
              _hostSearchResultWidget(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _hostSearchResultWidget() {
    return Expanded(
      child: ListView.builder(
        shrinkWrap: true,
        itemCount: _scanResults.length,
        itemBuilder: (context, index) {
          final BlueScanResult scanResult = _scanResults[index];
          return Card(
            child: ListTile(
              title: Text(scanResult.name),
              subtitle: Text(scanResult.deviceId),
            ),
          );
        },
      ),
    );
  }

  void _onBleScan() {
    QuickBlue.scanResultStream.listen((result) {
      final temp = [..._scanResults];
      temp.add(result);
      setState(() {
        _scanResults = temp;
      });
    });
  }

  void _startBleScan() async {
    QuickBlue.startScan();
    Future.delayed(const Duration(seconds: 3), () {
      _stopBleScan();
    });
  }

  void _stopBleScan() async {
    QuickBlue.stopScan();
  }

  Future<void> _checkPermission() async {
    final granted = await PermissionHandler.requestBlePermission();
    if (granted) {
      final isLocationEnable = await PermissionHandler.checkLocationIsEnable();
      if (isLocationEnable) {
        final isBleEnable = await PermissionHandler.checkBleIsEnable();
        if (isBleEnable) {
          _startBleScan();
        } else {
          print('需要开启蓝牙');
        }
      } else {
        print('需要开启定位');
      }
    } else {
      print('需要相关权限');
    }
  }
}
