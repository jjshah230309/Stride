import SwiftUI
import CoreLocation

/// Tap the map to lay down a route. Points can be joined in a straight line or
/// snapped onto real paths using MapKit's directions.
struct RouteBuilderView: View {
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var session: RecordingSession
    @Environment(\.dismiss) private var dismiss

    @State private var waypoints: [Coord] = []
    @State private var rendered: [Coord] = []
    @State private var name = ""
    @State private var sport: SportType = .run
    @State private var snapToPaths = true
    @State private var isSnapping = false

    private var units: UnitSystem { store.settings.units }

    private var distance: Double {
        Geo.totalDistance(rendered.count > 1 ? rendered : waypoints)
    }

    /// Elevation borrowed from your own recorded tracks near each point — the only
    /// terrain data available without a network service.
    private var elevations: [Double] {
        let path = rendered.count > 1 ? rendered : waypoints
        guard !path.isEmpty, let routeBox = BoundingBox(path) else { return [] }
        let searchBox = routeBox.expanded(byMeters: 120)

        var samples: [(Coord, Double)] = []
        for activity in store.activities.prefix(60) where activity.hasGPS {
            guard let box = activity.boundingBox,
                  box.expanded(byMeters: 200).intersects(routeBox) else { continue }
            let track = Store.loadTrack(activity.id)
            // Every fourth sample is plenty for looking up terrain height.
            var i = 0
            while i < track.count {
                let point = track[i]
                if point.coord.isValid && searchBox.contains(point.coord) {
                    samples.append((point.coord, point.alt))
                }
                i += 4
            }
            if samples.count > 8000 { break }
        }
        guard !samples.isEmpty else { return [] }

        return path.map { target in
            var best = Double.greatestFiniteMagnitude
            var value = 0.0
            for (coord, alt) in samples {
                let d = Geo.fastDistance(coord, target)
                if d < best { best = d; value = alt }
                if best < 12 { break }
            }
            return best < 60 ? value : 0
        }
    }

    /// Where to open the map: your current position, else the last place you trained.
    private var mapCenter: Coord? {
        if let live = session.currentCoord { return live }
        let recent: Activity? = store.activities.first
        return recent?.startCoord
    }

    var body: some View {
        VStack(spacing: 0) {
            EditableMap(waypoints: $waypoints,
                        renderedPath: rendered,
                        style: MapStyleOption(rawValue: store.settings.display.mapStyle) ?? .standard,
                        initialCenter: mapCenter)
                .overlay(alignment: .topTrailing) {
                    VStack(spacing: 8) {
                        controlButton("arrow.uturn.backward") { undo() }
                        controlButton("trash") { waypoints.removeAll(); rendered.removeAll() }
                    }
                    .padding(12)
                }

            VStack(spacing: 12) {
                HStack(spacing: 0) {
                    StatTile(label: "Distance", value: Fmt.distance(distance, units),
                             unit: units.distanceUnit, size: 20, alignment: .center)
                    StatTile(label: "Points", value: "\(waypoints.count)", size: 20, alignment: .center)
                    StatTile(label: "Est. time",
                             value: Fmt.durationCompact(estimate),
                             size: 20, alignment: .center)
                }
                TextField("Route name", text: $name)
                    .textFieldStyle(.roundedBorder)
                Picker("Sport", selection: $sport) {
                    ForEach(SportType.allCases.filter { !$0.isIndoor }) { Text($0.shortName).tag($0) }
                }
                .pickerStyle(.menu)
                .frame(maxWidth: .infinity, alignment: .leading)
                Toggle("Snap to paths and roads", isOn: $snapToPaths)
                    .font(.system(size: 14))
                    .onChange(of: snapToPaths) { resnap() }
                if isSnapping {
                    ProgressView().controlSize(.small)
                }
                Text(waypoints.isEmpty
                     ? "Tap the map to drop your first point."
                     : "Tap to add more points. Snapping uses Apple Maps directions and needs a connection.")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(16)
        }
        .navigationTitle("Build Route")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button("Cancel") { dismiss() }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("Save") { save() }
                    .fontWeight(.semibold)
                    .disabled(waypoints.count < 2)
            }
        }
        .onChange(of: waypoints) { resnap() }
    }

    private func controlButton(_ symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .semibold))
                .frame(width: 38, height: 38)
                .background(.ultraThinMaterial)
                .clipShape(Circle())
        }
    }

    private var estimate: TimeInterval {
        guard distance > 0 else { return 0 }
        let speed = sport.isRide ? 6.5 : 2.8
        return distance / speed
    }

    private func undo() {
        guard !waypoints.isEmpty else { return }
        waypoints.removeLast()
    }

    private func resnap() {
        guard waypoints.count > 1 else {
            rendered = waypoints
            return
        }
        guard snapToPaths else {
            rendered = waypoints
            return
        }
        isSnapping = true
        RouteSnapper.snap(waypoints: waypoints, walking: !sport.isRide) { path in
            DispatchQueue.main.async {
                rendered = path
                isSnapping = false
            }
        }
    }

    private func save() {
        let path = rendered.count > 1 ? rendered : waypoints
        let profile = elevations
        let change = profile.isEmpty ? (gain: 0.0, loss: 0.0) : Smooth.elevationChange(profile, threshold: 2)
        let route = SavedRoute(name: name.isEmpty ? "Route \(store.routes.count + 1)" : name,
                               sport: sport,
                               path: path,
                               elevations: profile,
                               distance: Geo.totalDistance(path),
                               elevationGain: change.gain)
        store.addRoute(route)
        dismiss()
    }
}
