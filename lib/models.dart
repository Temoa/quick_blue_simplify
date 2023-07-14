import 'dart:typed_data';

class BlueConnectionState {
  static const disconnected = BlueConnectionState._('disconnected');
  static const connected = BlueConnectionState._('connected');

  final String value;

  const BlueConnectionState._(this.value);

  static BlueConnectionState parse(String value) {
    if (value == disconnected.value) {
      return disconnected;
    } else if (value == connected.value) {
      return connected;
    }
    throw ArgumentError.value(value);
  }
}

class BleInputProperty {
  static const disabled = BleInputProperty._('disabled');
  static const notification = BleInputProperty._('notification');
  static const indication = BleInputProperty._('indication');

  final String value;

  const BleInputProperty._(this.value);
}

class BleOutputProperty {
  static const withResponse = BleOutputProperty._('withResponse');
  static const withoutResponse = BleOutputProperty._('withoutResponse');

  final String value;

  const BleOutputProperty._(this.value);
}

final _empty = Uint8List.fromList(List.empty());

final _emptyMap = <dynamic, dynamic>{};

class BlueScanResult {
  String name;
  String deviceId;
  Uint8List? _manufacturerDataHead;
  Uint8List? _manufacturerData;
  Map<dynamic, dynamic>? _allManufacturerDataHead;
  int rssi;

  Uint8List get manufacturerDataHead => _manufacturerDataHead ?? _empty;

  Uint8List get manufacturerData => _manufacturerData ?? manufacturerDataHead;

  Map<dynamic, dynamic> get allManufacturerDataHead => _allManufacturerDataHead ?? _emptyMap;

  BlueScanResult.fromMap(map)
      : name = map['name'],
        deviceId = map['deviceId'],
        _manufacturerDataHead = map['manufacturerDataHead'],
        _manufacturerData = map['manufacturerData'],
        _allManufacturerDataHead = map['allManufacturerDataHead'],
        rssi = map['rssi'];

  Map toMap() => {
        'name': name,
        'deviceId': deviceId,
        'manufacturerDataHead': _manufacturerDataHead,
        'manufacturerData': _manufacturerData,
        'rssi': rssi,
      };
}
