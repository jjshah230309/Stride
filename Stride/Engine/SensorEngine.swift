import Foundation
import CoreMotion
import CoreBluetooth
import Combine

/// The barometer and the pedometer. The barometer gives far better elevation than
/// GPS altitude; the pedometer gives running cadence without a watch.
final class MotionEngine: ObservableObject {

    @Published var relativeAltitude: Double = 0        // metres since start
    @Published var cadence: Double = 0                 // steps per minute (both feet)
    @Published var pedometerDistance: Double = 0       // metres, iPhone's own estimate
    @Published var hasBarometer: Bool = CMAltimeter.isRelativeAltitudeAvailable()

    private let altimeter = CMAltimeter()
    private let pedometer = CMPedometer()
    private var altimeterRunning = false
    private var pedometerRunning = false

    func start(trackSteps: Bool) {
        if CMAltimeter.isRelativeAltitudeAvailable() && !altimeterRunning {
            altimeterRunning = true
            altimeter.startRelativeAltitudeUpdates(to: .main) { [weak self] data, _ in
                guard let data else { return }
                self?.relativeAltitude = data.relativeAltitude.doubleValue
            }
        }
        if trackSteps && CMPedometer.isCadenceAvailable() && !pedometerRunning {
            pedometerRunning = true
            pedometer.startUpdates(from: Date()) { [weak self] data, _ in
                guard let data else { return }
                DispatchQueue.main.async {
                    if let c = data.currentCadence {
                        // CMPedometer reports steps per second for one leg pair; ×60 gives spm.
                        self?.cadence = c.doubleValue * 60
                    }
                    if let d = data.distance {
                        self?.pedometerDistance = d.doubleValue
                    }
                }
            }
        }
    }

    func stop() {
        if altimeterRunning {
            altimeter.stopRelativeAltitudeUpdates()
            altimeterRunning = false
        }
        if pedometerRunning {
            pedometer.stopUpdates()
            pedometerRunning = false
        }
        cadence = 0
    }

    func reset() {
        relativeAltitude = 0
        pedometerDistance = 0
    }
}

/// Reads any standard Bluetooth heart rate strap or armband. The Heart Rate
/// Service is a published GATT profile, so Polar, Garmin, Wahoo and the rest all work.
final class HeartRateMonitor: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {

    @Published var heartRate: Double = 0
    @Published var isConnected: Bool = false
    @Published var deviceName: String = ""
    @Published var isScanning: Bool = false
    @Published var discovered: [(id: UUID, name: String)] = []
    @Published var batteryLevel: Int?
    @Published var bluetoothReady: Bool = false

    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?

    private let heartRateService = CBUUID(string: "180D")
    private let heartRateCharacteristic = CBUUID(string: "2A37")
    private let batteryService = CBUUID(string: "180F")
    private let batteryCharacteristic = CBUUID(string: "2A19")

    private var peripherals: [UUID: CBPeripheral] = [:]

    /// The last device you paired, remembered between launches.
    private let lastDeviceKey = "stride.lastHeartRateDevice"

    func startIfNeeded() {
        if central == nil {
            central = CBCentralManager(delegate: self, queue: nil)
        }
    }

    func scan() {
        startIfNeeded()
        guard let central, central.state == .poweredOn else { return }
        discovered.removeAll()
        peripherals.removeAll()
        isScanning = true
        central.scanForPeripherals(withServices: [heartRateService], options: nil)
        DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in
            self?.stopScan()
        }
    }

    func stopScan() {
        central?.stopScan()
        isScanning = false
    }

    func connect(_ id: UUID) {
        guard let p = peripherals[id] else { return }
        UserDefaults.standard.set(id.uuidString, forKey: lastDeviceKey)
        peripheral = p
        p.delegate = self
        central?.connect(p, options: nil)
        stopScan()
    }

    func disconnect() {
        if let peripheral { central?.cancelPeripheralConnection(peripheral) }
        peripheral = nil
        isConnected = false
        heartRate = 0
        deviceName = ""
    }

    func forgetDevice() {
        UserDefaults.standard.removeObject(forKey: lastDeviceKey)
        disconnect()
    }

    /// Reconnect to the remembered strap without the user doing anything.
    func reconnectLast() {
        startIfNeeded()
        guard let central, central.state == .poweredOn else { return }
        guard let saved = UserDefaults.standard.string(forKey: lastDeviceKey),
              let uuid = UUID(uuidString: saved) else { return }
        let known = central.retrievePeripherals(withIdentifiers: [uuid])
        if let p = known.first {
            peripherals[uuid] = p
            connect(uuid)
        } else {
            scan()
        }
    }

    // MARK: - Central delegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        bluetoothReady = central.state == .poweredOn
        if central.state == .poweredOn {
            reconnectLast()
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let name = peripheral.name ?? (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? "Heart Rate Monitor"
        peripherals[peripheral.identifier] = peripheral
        if !discovered.contains(where: { $0.id == peripheral.identifier }) {
            discovered.append((peripheral.identifier, name))
        }
        // Auto-connect if this is the strap we used last time.
        if let saved = UserDefaults.standard.string(forKey: lastDeviceKey),
           saved == peripheral.identifier.uuidString {
            connect(peripheral.identifier)
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        isConnected = true
        deviceName = peripheral.name ?? "Heart Rate Monitor"
        peripheral.discoverServices([heartRateService, batteryService])
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        isConnected = false
        heartRate = 0
        // Straps drop out; try to pick them back up.
        central.connect(peripheral, options: nil)
    }

    // MARK: - Peripheral delegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        for service in peripheral.services ?? [] {
            if service.uuid == heartRateService {
                peripheral.discoverCharacteristics([heartRateCharacteristic], for: service)
            } else if service.uuid == batteryService {
                peripheral.discoverCharacteristics([batteryCharacteristic], for: service)
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        for characteristic in service.characteristics ?? [] {
            if characteristic.uuid == heartRateCharacteristic {
                peripheral.setNotifyValue(true, for: characteristic)
            } else if characteristic.uuid == batteryCharacteristic {
                peripheral.readValue(for: characteristic)
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value else { return }
        if characteristic.uuid == heartRateCharacteristic {
            if let bpm = Self.parseHeartRate(data) {
                DispatchQueue.main.async { self.heartRate = Double(bpm) }
            }
        } else if characteristic.uuid == batteryCharacteristic, let first = data.first {
            DispatchQueue.main.async { self.batteryLevel = Int(first) }
        }
    }

    /// The first byte's low bit says whether the value is 8-bit or 16-bit.
    static func parseHeartRate(_ data: Data) -> Int? {
        let bytes = [UInt8](data)
        guard bytes.count > 1 else { return nil }
        let is16Bit = (bytes[0] & 0x01) == 0x01
        if is16Bit {
            guard bytes.count > 2 else { return nil }
            return Int(bytes[1]) | (Int(bytes[2]) << 8)
        }
        return Int(bytes[1])
    }
}
