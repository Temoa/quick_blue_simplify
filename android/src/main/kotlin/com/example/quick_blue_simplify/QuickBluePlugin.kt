package com.example.quick_blue_simplify

import android.R.attr.value
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.*
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*


private const val TAG = "QuickBluePlugin"

/** QuickBluePlugin */
@SuppressLint("MissingPermission")
class QuickBluePlugin : FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler {
  /// The MethodChannel that will the communication between Flutter and native Android
  ///
  /// This local reference serves to register the plugin with the Flutter Engine and unregister it
  /// when the Flutter Engine is detached from the Activity
  private lateinit var method: MethodChannel
  private lateinit var eventScanResult: EventChannel
  private lateinit var messageConnector: BasicMessageChannel<Any>

  override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    method = MethodChannel(flutterPluginBinding.binaryMessenger, "quick_blue/method")
    eventScanResult = EventChannel(flutterPluginBinding.binaryMessenger, "quick_blue/event.scanResult")
    messageConnector = BasicMessageChannel(flutterPluginBinding.binaryMessenger, "quick_blue/message.connector", StandardMessageCodec.INSTANCE)

    method.setMethodCallHandler(this)
    eventScanResult.setStreamHandler(this)

    context = flutterPluginBinding.applicationContext
    mainThreadHandler = Handler(Looper.getMainLooper())
    bluetoothManager = flutterPluginBinding.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    val filterAdapter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
    context.registerReceiver(mBluetoothAdapterStateReceiver, filterAdapter)
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    lastScanCallback?.let { bluetoothManager.adapter.bluetoothLeScanner?.stopScan(it) }

    eventScanResult.setStreamHandler(null)
    method.setMethodCallHandler(null)

    context.unregisterReceiver(mBluetoothAdapterStateReceiver)
  }

  private lateinit var context: Context
  private lateinit var mainThreadHandler: Handler
  private lateinit var bluetoothManager: BluetoothManager

  private val knownGatts = mutableListOf<BluetoothGatt>()
  private val cacheBluetoothDevices = HashMap<String, BluetoothDevice>()

  private var lastScanCallback: MyScanCallback? = null

  private fun sendMessage(messageChannel: BasicMessageChannel<Any>, message: Map<String, Any>) {
    mainThreadHandler.post { messageChannel.send(message) }
  }

  override fun onMethodCall(call: MethodCall, result: Result) {
    when (call.method) {
      "isBluetoothAvailable" -> {
        result.success(bluetoothManager.adapter.isEnabled)
      }

      "startScan" -> {
        val filterServiceUUID = call.argument<String>("filterServiceUUID")
        lastScanCallback = getScanCallback()
        if (filterServiceUUID != null) {
          val scanFilter = ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(filterServiceUUID))).build()
          val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
          bluetoothManager.adapter.bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, lastScanCallback!!)
        } else {
          bluetoothManager.adapter.bluetoothLeScanner?.startScan(lastScanCallback!!)
        }
        result.success(null)
      }

      "stopScan" -> {
        lastScanCallback?.let { bluetoothManager.adapter.bluetoothLeScanner?.stopScan(it) }
        result.success(null)
      }

      "connect" -> {
        val deviceId = call.argument<String>("deviceId")!!
        val autoConnect = call.argument<Boolean>("autoConnect") ?: false
        if (knownGatts.find { it.device.address == deviceId } != null) {
          return result.success(null)
        }
        val remoteDevice = cacheBluetoothDevices[deviceId] ?: bluetoothManager.adapter.getRemoteDevice(deviceId)
        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          remoteDevice.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
          remoteDevice.connectGatt(context, autoConnect, gattCallback)
        }
        knownGatts.add(gatt)
        cacheBluetoothDevices[deviceId] = remoteDevice
        result.success(null)
        // TODO connecting
      }

      "disconnect" -> {
        val deviceId = call.argument<String>("deviceId")!!
        val gatt = knownGatts.find { it.device.address == deviceId }
          ?: return result.error("IllegalArgument", "Unknown deviceId: $deviceId", null)
        cleanConnection(gatt)
        result.success(null)
        //FIXME If `disconnect` is called before BluetoothGatt.STATE_CONNECTED
        // there will be no `disconnected` message any more
      }

      "discoverServices" -> {
        val deviceId = call.argument<String>("deviceId")!!
        val gatt = knownGatts.find { it.device.address == deviceId }
          ?: return result.error("IllegalArgument", "Unknown deviceId: $deviceId", null)
        gatt.discoverServices()
        result.success(null)
      }

      "setNotifiable" -> {
        val deviceId = call.argument<String>("deviceId")!!
        val service = call.argument<String>("service")!!
        val characteristic = call.argument<String>("characteristic")!!
        val bleInputProperty = call.argument<String>("bleInputProperty")!!
        val gatt = knownGatts.find { it.device.address == deviceId }
          ?: return result.error("IllegalArgument", "Unknown deviceId: $deviceId", null)
        gatt.setNotifiable(service to characteristic, bleInputProperty)
        result.success(null)
      }

      "requestMtu" -> {
        val deviceId = call.argument<String>("deviceId")!!
        val expectedMtu = call.argument<Int>("expectedMtu")!!
        val gatt = knownGatts.find { it.device.address == deviceId }
          ?: return result.error("IllegalArgument", "Unknown deviceId: $deviceId", null)
        gatt.requestMtu(expectedMtu)
        result.success(null)
      }

      "readValue" -> {
        val deviceId = call.argument<String>("deviceId")!!
        val service = call.argument<String>("service")!!
        val characteristic = call.argument<String>("characteristic")!!
        val gatt = knownGatts.find { it.device.address == deviceId }
          ?: return result.error("IllegalArgument", "Unknown deviceId: $deviceId", null)
        val readResult = gatt.getCharacteristic(service to characteristic)?.let {
          gatt.readCharacteristic(it)
        }
        if (readResult == true)
          result.success(null)
        else
          result.error("Characteristic unavailable", null, null)
      }

      "writeValue" -> {
        val deviceId = call.argument<String>("deviceId")!!
        val service = call.argument<String>("service")!!
        val characteristic = call.argument<String>("characteristic")!!
        val value = call.argument<ByteArray>("value")!!
        val bleOutputProperty = call.argument<String>("bleOutputProperty")!!

        val gatt = knownGatts.find { it.device.address == deviceId }
          ?: return result.error("IllegalArgument", "Unknown deviceId: $deviceId", null)

        val targetCharacteristic = gatt.getCharacteristic(service to characteristic)

        val writeType = if (bleOutputProperty == "withResponse") {
          BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
          BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        if (Build.VERSION.SDK_INT >= 33) {
          val rv = gatt.writeCharacteristic(targetCharacteristic, value, writeType)
          if (rv != BluetoothStatusCodes.SUCCESS) {
            result.error("writeCharacteristic", "gatt.writeCharacteristic() returned $rv", null)
            return
          }
        } else {
          if (!targetCharacteristic.setValue(value)) {
            result.error("writeCharacteristic", "characteristic.setValue() returned false", null);
            return
          }
          targetCharacteristic.writeType = writeType
          if (!gatt.writeCharacteristic(targetCharacteristic)) {
            result.error("writeCharacteristic", "gatt.writeCharacteristic() returned false", null);
            return
          }
        }
        result.success(null)
      }

      else -> {
        result.notImplemented()
      }
    }
  }

  private fun cleanConnection(gatt: BluetoothGatt) {
    knownGatts.remove(gatt)
    gatt.disconnect()
    gatt.close()
  }

  private fun getScanCallback(): MyScanCallback {
    return MyScanCallback()
  }

  inner class MyScanCallback : ScanCallback() {

    override fun onScanFailed(errorCode: Int) {
      Log.v(TAG, "onScanFailed: $errorCode")
    }

    override fun onScanResult(callbackType: Int, result: ScanResult) {
      Log.v(TAG, "onScanResult: $callbackType + $result")
      scanResultSink?.success(
        mapOf<String, Any>(
          "name" to (result.device.name ?: ""),
          "deviceId" to result.device.address,
          "manufacturerDataHead" to (result.manufacturerDataHead ?: byteArrayOf()),
          "allManufacturerDataHead" to (result.allManufacturerDataHead),
          "rssi" to result.rssi
        )
      )
    }

    override fun onBatchScanResults(results: MutableList<ScanResult>?) {
      Log.v(TAG, "onBatchScanResults: $results")
    }
  }

  private var scanResultSink: EventChannel.EventSink? = null

  override fun onListen(args: Any?, eventSink: EventChannel.EventSink?) {
    val map = args as? Map<String, Any> ?: return
    when (map["name"]) {
      "scanResult" -> scanResultSink = eventSink
    }
  }

  override fun onCancel(args: Any?) {
    val map = args as? Map<String, Any> ?: return
    when (map["name"]) {
      "scanResult" -> scanResultSink = null
    }
  }

  private val gattCallback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
      Log.v(TAG, "onConnectionStateChange: device(${gatt.device.address}) status($status), newState($newState)")
      if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
        sendMessage(
          messageConnector, mapOf(
            "deviceId" to gatt.device.address,
            "ConnectionState" to "connected"
          )
        )
      } else {
        cleanConnection(gatt)
        sendMessage(
          messageConnector, mapOf(
            "deviceId" to gatt.device.address,
            "ConnectionState" to "disconnected"
          )
        )
      }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
      Log.v(TAG, "onServicesDiscovered ${gatt.device.address} $status")
      if (status != BluetoothGatt.GATT_SUCCESS) return

      gatt.services?.forEach { service ->
        Log.v(TAG, "Service " + service.uuid)
        service.characteristics.forEach { characteristic ->
          Log.v(TAG, "    Characteristic ${characteristic.uuid}")
          characteristic.descriptors.forEach {
            Log.v(TAG, "        Descriptor ${it.uuid}")
          }
        }

        sendMessage(messageConnector, mapOf(
          "deviceId" to gatt.device.address,
          "ServiceState" to "discovered",
          "service" to service.uuid.toString(),
          "characteristics" to service.characteristics.map { it.uuid.toString() }
        ))
      }
    }

    override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
      if (status == BluetoothGatt.GATT_SUCCESS) {
        sendMessage(
          messageConnector, mapOf(
            "mtuConfig" to mtu
          )
        )
      }
    }

    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
      Log.v(TAG, "onCharacteristicRead ${characteristic.uuid}, ${characteristic.value.contentToString()}")
      sendMessage(
        messageConnector, mapOf(
          "deviceId" to gatt.device.address,
          "serviceId" to characteristic.service.uuid.toString(),
          "characteristicValue" to mapOf(
            "characteristic" to characteristic.uuid.toString(),
            "value" to characteristic.value
          )
        )
      )
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic, status: Int) {
      Log.v(TAG, "onCharacteristicWrite ${characteristic.uuid}, ${characteristic.value.contentToString()} $status")
      if (gatt == null) return
      sendMessage(
        messageConnector, mapOf(
          "deviceId" to gatt.device.address,
          "serviceId" to characteristic.service.uuid.toString(),
          "onCharacteristicWrite" to mapOf(
            "characteristic" to characteristic.uuid.toString(),
            "success" to (status == BluetoothGatt.GATT_SUCCESS),
          )
        )
      )
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
      Log.v(TAG, "onCharacteristicChanged ${characteristic.uuid}, ${characteristic.value.contentToString()}")
      sendMessage(
        messageConnector, mapOf(
          "deviceId" to gatt.device.address,
          "serviceId" to characteristic.service.uuid.toString(),
          "characteristicValue" to mapOf(
            "characteristic" to characteristic.uuid.toString(),
            "value" to characteristic.value
          )
        )
      )
    }
  }

  private val mBluetoothAdapterStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val action = intent.action

      // no change?
      if (action == null || BluetoothAdapter.ACTION_STATE_CHANGED != action) {
        return
      }
      val adapterState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)

      // disconnect all devices
      if (adapterState == BluetoothAdapter.STATE_TURNING_OFF ||
        adapterState == BluetoothAdapter.STATE_OFF
      ) {
        cacheBluetoothDevices.clear()
        for (gatt in knownGatts) {
          try {
            gatt.disconnect()
            sendMessage(
              messageConnector, mapOf(
                "deviceId" to gatt.device.address,
                "ConnectionState" to "disconnected"
              )
            )
          } catch (e: Exception) {
            //
          }
        }
        knownGatts.clear()
      }
    }
  }
}

val ScanResult.manufacturerDataHead: ByteArray?
  get() {
    val sparseArray = scanRecord?.manufacturerSpecificData ?: return null
    if (sparseArray.size() == 0) return null

    return sparseArray.keyAt(0).toShort().toByteArray() + sparseArray.valueAt(0)
  }

val ScanResult.allManufacturerDataHead: HashMap<Int, ByteArray>
  get() {
    val map = HashMap<Int, ByteArray>()
    val sparseArray = scanRecord?.manufacturerSpecificData ?: return map
    for (i in 0 until sparseArray.size()) {
      val key = sparseArray.keyAt(i)
      val value: ByteArray = sparseArray.valueAt(i)
      map[key] = value
    }
    return map
  }

fun Short.toByteArray(byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN): ByteArray =
  ByteBuffer.allocate(2 /*Short.SIZE_BYTES*/).order(byteOrder).putShort(this).array()

fun BluetoothGatt.getCharacteristic(serviceCharacteristic: Pair<String, String>): BluetoothGattCharacteristic =
  getService(UUID.fromString(serviceCharacteristic.first)).getCharacteristic(UUID.fromString(serviceCharacteristic.second))

private val DESC__CLIENT_CHAR_CONFIGURATION = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

fun BluetoothGatt.setNotifiable(serviceCharacteristic: Pair<String, String>, bleInputProperty: String) {
  val descriptor = getCharacteristic(serviceCharacteristic).getDescriptor(DESC__CLIENT_CHAR_CONFIGURATION)
  val (value, enable) = when (bleInputProperty) {
    "notification" -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE to true
    "indication" -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE to true
    else -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE to false
  }
  descriptor.value = value
  setCharacteristicNotification(descriptor.characteristic, enable) && writeDescriptor(descriptor)
}