import Foundation

// Stand-ins for the Apple-only types the pure sources mention. They carry no behaviour.

struct Color {}

enum Theme {
    static let runColor = Color()
    static let hikeColor = Color()
    static let rideColor = Color()
}

struct CLLocationCoordinate2D {
    var latitude: Double
    var longitude: Double
}

// LiveSegmentEngine is an ObservableObject with @Published state; on Linux there is no Combine.
protocol ObservableObject: AnyObject {}

@propertyWrapper
struct Published<Value> {
    var wrappedValue: Value
    init(wrappedValue: Value) { self.wrappedValue = wrappedValue }
}
