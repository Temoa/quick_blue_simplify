import 'dart:io';

import 'package:permission_handler/permission_handler.dart';

class PermissionHandler {
  static Future<bool> requestBlePermission() async {
    if (Platform.isAndroid) {
      return await _requestBlePermissionAndroid();
    } else {
      return await _requestBlePermissionIOS();
    }
  }

  static Future<bool> _requestBlePermissionIOS() async {
    final isBleGranted = await Permission.bluetooth.request();
    return isBleGranted == PermissionStatus.granted;
  }

  static Future<bool> _requestBlePermissionAndroid() async {
    final Map<Permission, PermissionStatus> isBleGranted = await [
      Permission.bluetoothScan,
      Permission.bluetoothConnect,
    ].request();

    return isBleGranted[Permission.bluetoothScan] == PermissionStatus.granted && //
        isBleGranted[Permission.bluetoothConnect] == PermissionStatus.granted;
  }

  static Future<bool> checkLocationIsEnable() async {
    if (Platform.isAndroid) {
      return (await Permission.locationWhenInUse.serviceStatus.isEnabled) == true;
    } else {
      return true;
    }
  }

  static Future<bool> checkBleIsEnable() async {
    return (await Permission.bluetooth.serviceStatus.isEnabled) == true;
  }
}
