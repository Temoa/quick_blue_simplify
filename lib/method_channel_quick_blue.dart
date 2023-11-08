import 'dart:async';

import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart';
import 'package:logging/logging.dart';
import 'package:mutex/mutex.dart';

import 'quick_blue_platform_interface.dart';

class MethodChannelQuickBlue extends QuickBluePlatform {
  static const MethodChannel _method = MethodChannel('quick_blue/method');
  static const _event_scanResult = EventChannel('quick_blue/event.scanResult');
  static const _message_connector = BasicMessageChannel('quick_blue/message.connector', StandardMessageCodec());

  static final StreamController<dynamic> _writeMethodStream = StreamController.broadcast();
  final _writeLock = Mutex();

  MethodChannelQuickBlue() {
    _message_connector.setMessageHandler(_handleConnectorMessage);
  }

  QuickLogger? _logger;

  @override
  void setLogger(QuickLogger logger) {
    _logger = logger;
  }

  void _log(String message, {Level logLevel = Level.INFO}) {
    _logger?.log(logLevel, message);
  }

  @override
  Future<bool> isBluetoothAvailable() async {
    bool result = await _method.invokeMethod('isBluetoothAvailable');
    return result;
  }

  @override
  void startScan({String? filterServiceUUID}) {
    _method.invokeMethod('startScan', {
      "filterServiceUUID": filterServiceUUID,
    }).then((_) => debugPrint('startScan invokeMethod success'));
  }

  @override
  void stopScan() {
    _method.invokeMethod('stopScan').then((_) => debugPrint('stopScan invokeMethod success'));
  }

  final Stream<dynamic> _scanResultStream = _event_scanResult.receiveBroadcastStream({'name': 'scanResult'});

  @override
  Stream<dynamic> get scanResultStream => _scanResultStream;

  @override
  void connect(String deviceId, {bool autoConnect = false}) {
    _method.invokeMethod('connect', {
      'deviceId': deviceId,
      'autoConnect': autoConnect,
    }).then((_) => _log('connect invokeMethod success'));
  }

  @override
  void disconnect(String deviceId) {
    _method.invokeMethod('disconnect', {
      'deviceId': deviceId,
    }).then((_) => _log('disconnect invokeMethod success'));
  }

  @override
  void discoverServices(String deviceId) {
    _method.invokeMethod('discoverServices', {
      'deviceId': deviceId,
    }).then((_) => _log('discoverServices invokeMethod success'));
  }

  Future<void> _handleConnectorMessage(dynamic message) async {
    _log('_handleConnectorMessage $message', logLevel: Level.ALL);
    if (message['ConnectionState'] != null) {
      String deviceId = message['deviceId'];
      BlueConnectionState connectionState = BlueConnectionState.parse(message['ConnectionState']);
      onConnectionChanged?.call(deviceId, connectionState);
    } else if (message['ServiceState'] != null) {
      if (message['ServiceState'] == 'discovered') {
        String deviceId = message['deviceId'];
        String service = message['service'];
        List<String> characteristics = (message['characteristics'] as List).cast();
        onServiceDiscovered?.call(deviceId, service, characteristics);
      }
    } else if (message['characteristicValue'] != null) {
      String deviceId = message['deviceId'];
      String serviceId = message['serviceId'];
      var characteristicValue = message['characteristicValue'];
      String characteristic = characteristicValue['characteristic'];
      Uint8List value = Uint8List.fromList(characteristicValue['value']); // In case of _Uint8ArrayView
      onValueChanged?.call(deviceId, serviceId, characteristic, value);
    } else if (message['mtuConfig'] != null) {
      _mtuConfigController.add(message['mtuConfig']);
    } else if (message['onCharacteristicWrite'] != null) {
      _writeMethodStream.add(message);
    }
  }

  @override
  Future<void> setNotifiable(String deviceId, String service, String characteristic, BleInputProperty bleInputProperty) async {
    _method.invokeMethod('setNotifiable', {
      'deviceId': deviceId,
      'service': service,
      'characteristic': characteristic,
      'bleInputProperty': bleInputProperty.value,
    }).then((_) => _log('setNotifiable invokeMethod success'));
  }

  @override
  Future<void> readValue(String deviceId, String service, String characteristic) async {
    _method.invokeMethod('readValue', {
      'deviceId': deviceId,
      'service': service,
      'characteristic': characteristic,
    }).then((_) => _log('readValue invokeMethod success'));
  }

  @override
  Future<void> writeValue(String deviceId, String service, String characteristic, Uint8List value, BleOutputProperty bleOutputProperty) async {
    _method.invokeMethod('writeValue', {
      'deviceId': deviceId,
      'service': service,
      'characteristic': characteristic,
      'value': value,
      'bleOutputProperty': bleOutputProperty.value,
    }).then((_) {
      _log('writeValue invokeMethod success', logLevel: Level.ALL);
    }).catchError((onError) {
      // Characteristic sometimes unavailable on Android
      throw onError;
    });
  }

  // FIXME Close
  final _mtuConfigController = StreamController<int>.broadcast();

  @override
  Future<int> requestMtu(String deviceId, int expectedMtu) async {
    _method.invokeMethod('requestMtu', {
      'deviceId': deviceId,
      'expectedMtu': expectedMtu,
    }).then((_) => _log('requestMtu invokeMethod success'));
    return await _mtuConfigController.stream.first;
  }

  @override
  Future<bool> writeValueWithResponse(String deviceId, String service, String characteristic, Uint8List value) async {
    await _writeLock.acquire();
    try {
      var responseStream = _writeMethodStream.stream //
          .where((message) => message["deviceId"] == deviceId)
          .where((message) => message["serviceId"] == service)
          .where((message) => message["onCharacteristicWrite"]["characteristic"] == characteristic);

      Future<dynamic> futureResponse = responseStream.first;

      _method.invokeMethod("writeValue", {
        'deviceId': deviceId,
        'service': service,
        'characteristic': characteristic,
        'value': value,
        'bleOutputProperty': BleOutputProperty.withResponse.value,
      });

      print((await futureResponse)["onCharacteristicWrite"]["success"]);
      return (await futureResponse)["onCharacteristicWrite"]["success"] as bool;
    } finally {
      _writeLock.release();
    }
  }
}
