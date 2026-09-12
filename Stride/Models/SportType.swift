import Foundation
import SwiftUI

enum SportType: String, Codable, CaseIterable, Identifiable {
    case run
    case trailRun
    case treadmill
    case walk
    case hike
    case ride
    case gravelRide
    case mountainBike
    case eBike
    case indoorRide

    var id: String { rawValue }

    var name: String {
        switch self {
        case .run: return "Run"
        case .trailRun: return "Trail Run"
        case .treadmill: return "Treadmill Run"
        case .walk: return "Walk"
        case .hike: return "Hike"
        case .ride: return "Ride"
        case .gravelRide: return "Gravel Ride"
        case .mountainBike: return "Mountain Bike Ride"
        case .eBike: return "E-Bike Ride"
        case .indoorRide: return "Indoor Ride"
        }
    }

    var shortName: String {
        switch self {
        case .trailRun: return "Trail"
        case .mountainBike: return "MTB"
        case .gravelRide: return "Gravel"
        case .indoorRide: return "Indoor"
        case .eBike: return "E-Bike"
        default: return name
        }
    }

    var symbol: String {
        switch self {
        case .run, .trailRun, .treadmill: return "figure.run"
        case .walk: return "figure.walk"
        case .hike: return "figure.hiking"
        case .ride, .gravelRide, .eBike: return "bicycle"
        case .mountainBike: return "bicycle"
        case .indoorRide: return "figure.indoor.cycle"
        }
    }

    var isRun: Bool {
        self == .run || self == .trailRun || self == .treadmill
    }

    var isRide: Bool {
        self == .ride || self == .gravelRide || self == .mountainBike || self == .eBike || self == .indoorRide
    }

    var isFoot: Bool {
        isRun || self == .walk || self == .hike
    }

    var isIndoor: Bool {
        self == .treadmill || self == .indoorRide
    }

    /// Runs and walks are read in pace; rides are read in speed.
    var usesPace: Bool { isFoot }

    var gearKind: GearKind { isRide ? .bike : .shoes }

    /// Speed below which we consider the athlete stopped, in m/s.
    var autoPauseThreshold: Double { isRide ? 1.2 : 0.5 }

    /// Speeds above this are GPS noise, in m/s.
    var maxPlausibleSpeed: Double { isRide ? 30.0 : 9.0 }

    var accentColor: Color {
        switch self {
        case .run, .trailRun, .treadmill: return Theme.runColor
        case .walk, .hike: return Theme.hikeColor
        default: return Theme.rideColor
        }
    }

    /// Rough MET value at moderate effort, used when heart rate is unavailable.
    var baseMET: Double {
        switch self {
        case .run, .trailRun, .treadmill: return 9.8
        case .walk: return 3.8
        case .hike: return 6.0
        case .ride, .gravelRide, .indoorRide: return 8.0
        case .mountainBike: return 8.5
        case .eBike: return 5.0
        }
    }

    static var runGroup: [SportType] { [.run, .trailRun, .treadmill, .walk, .hike] }
    static var rideGroup: [SportType] { [.ride, .gravelRide, .mountainBike, .eBike, .indoorRide] }
}
