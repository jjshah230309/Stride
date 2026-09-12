import SwiftUI

struct RoutesView: View {
    @EnvironmentObject private var store: AppStore
    @State private var filter: Filter = .routes
    @State private var showBuilder = false

    enum Filter: String, CaseIterable, Identifiable {
        case routes, segments, starred, heatmap
        var id: String { rawValue }
        var name: String {
            switch self {
            case .routes: return "Routes"
            case .segments: return "Segments"
            case .starred: return "Starred"
            case .heatmap: return "Heatmap"
            }
        }
    }

    private var units: UnitSystem { store.settings.units }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header
                    if filter == .heatmap {
                        heatmapCard
                    } else {
                        featureCard
                    }
                    chips
                    list
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 28)
            }
            .background(Theme.pageBackground)
            .navigationBarHidden(true)
            .sheet(isPresented: $showBuilder) {
                NavigationStack { RouteBuilderView() }
            }
        }
    }

    private var header: some View {
        ScreenHeader(kicker: "Explore", title: "Routes") {
            CircleIconButton(symbol: "plus") { showBuilder = true }
        }
        .padding(.top, 8)
    }

    // MARK: - Feature map

    /// The route or segment shown large at the top.
    private var featured: (path: [Coord], caption: String)? {
        switch filter {
        case .segments, .starred:
            let pool = filter == .starred ? store.starredSegments : store.segments
            guard let segment = pool.first else { return nil }
            return (segment.path,
                    Fmt.distanceWithUnit(segment.distance, units, decimals: 2)
                        + " · " + Fmt.elevationWithUnit(segment.elevationGain, units))
        default:
            guard let route = store.routes.first(where: { $0.isStarred }) ?? store.routes.first else {
                guard let activity = store.activities.first(where: { $0.hasGPS }) else { return nil }
                return (activity.previewPath,
                        Fmt.distanceWithUnit(activity.distance, units)
                            + " · " + Fmt.elevationWithUnit(activity.elevationGain, units))
            }
            return (route.path,
                    Fmt.distanceWithUnit(route.distance, units)
                        + " · " + Fmt.elevationWithUnit(route.elevationGain, units))
        }
    }

    @ViewBuilder
    private var featureCard: some View {
        if let featured, featured.path.count > 1 {
            ZStack(alignment: .bottomLeading) {
                RouteMap(path: featured.path,
                         showsStartEnd: true,
                         interactive: false,
                         color: Theme.mapLine,
                         style: MapStyleOption(rawValue: store.settings.display.mapStyle) ?? .standard,
                         padding: 26)
                    .frame(height: 240)
                    .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                MapBadge(text: featured.caption, symbol: nil)
                    .padding(14)
            }
        }
    }

    private var allPaths: [[Coord]] {
        store.activities.filter { $0.hasGPS && $0.previewPath.count > 1 }.map(\.previewPath)
    }

    @ViewBuilder
    private var heatmapCard: some View {
        if allPaths.isEmpty {
            Card {
                EmptyStateView(symbol: "flame",
                               title: "Your heatmap is empty",
                               message: "Record a few activities and the roads and trails you use most will start to glow here.")
            }
        } else {
            ZStack(alignment: .bottomLeading) {
                HeatMap(paths: allPaths,
                        style: MapStyleOption(rawValue: store.settings.display.mapStyle) ?? .standard)
                    .frame(height: 380)
                    .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                MapBadge(text: "\(allPaths.count) activities · "
                         + Fmt.distanceWithUnit(store.activities.reduce(0) { $0 + $1.distance }, units, decimals: 0),
                         symbol: "flame")
                    .padding(14)
            }
        }
    }

    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Filter.allCases) { option in
                    FilterChip(title: option.name, isActive: filter == option) {
                        filter = option
                    }
                }
            }
        }
    }

    // MARK: - List

    @ViewBuilder
    private var list: some View {
        switch filter {
        case .heatmap:
            Text("Every route you have recorded, drawn on top of itself. The brighter a road, the more often you have been down it.")
                .font(.system(size: 13))
                .foregroundStyle(Theme.secondaryText)
        case .routes:
            if store.routes.isEmpty {
                Card {
                    EmptyStateView(symbol: "map",
                                   title: "No routes yet",
                                   message: "Build one by tapping points on the map, or save one from any activity you have already recorded.",
                                   actionTitle: "Build a route") { showBuilder = true }
                }
            } else {
                ForEach(store.routes) { route in
                    NavigationLink {
                        RouteDetailView(route: route)
                    } label: {
                        DetailRow(symbol: "point.topleft.down.curvedto.point.bottomright.up",
                                  title: route.name,
                                  subtitle: Fmt.distanceWithUnit(route.distance, units)
                                    + " · " + Fmt.elevationWithUnit(route.elevationGain, units) + " climb")
                    }
                    .buttonStyle(.plain)
                }
            }
        case .segments, .starred:
            let pool = filter == .starred ? store.starredSegments : store.segments
            if pool.isEmpty {
                Card {
                    EmptyStateView(symbol: "flag",
                                   title: filter == .starred ? "Nothing starred" : "No segments yet",
                                   message: filter == .starred
                                    ? "Star a segment and Stride will race you against your own best time as you pass through it."
                                    : "Open any activity, choose \"Create segment\", and drag the handles over the stretch you want to race.")
                }
            } else {
                ForEach(pool) { segment in
                    NavigationLink {
                        SegmentDetailView(segment: segment)
                    } label: {
                        DetailRow(symbol: "flag.fill",
                                  title: segment.name,
                                  subtitle: segmentSubtitle(segment))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private func segmentSubtitle(_ segment: Segment) -> String {
        var parts = [Fmt.distanceWithUnit(segment.distance, units, decimals: 2),
                     String(format: "%.1f%%", segment.averageGrade * 100)]
        if let best = store.personalBest(for: segment) {
            parts.append("PR " + Fmt.duration(best.time))
        }
        return parts.joined(separator: " · ")
    }
}

struct RouteDetailView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    @State var route: SavedRoute
    @State private var exportURL: URL?
    @State private var showShare = false

    private var units: UnitSystem { store.settings.units }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                RouteMap(path: route.path, color: route.sport.accentColor)
                    .frame(height: 300)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))

                Card {
                    let columns = [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())]
                    LazyVGrid(columns: columns, spacing: 16) {
                        StatTile(label: "Distance", value: Fmt.distance(route.distance, units),
                                 unit: units.distanceUnit, size: 20, alignment: .center)
                        StatTile(label: "Elev gain", value: Fmt.elevation(route.elevationGain, units),
                                 unit: units.elevationUnit, size: 20, alignment: .center)
                        StatTile(label: "Est. time", value: Fmt.durationCompact(estimatedTime),
                                 size: 20, alignment: .center)
                    }
                }

                if route.elevations.count > 2 {
                    Card {
                        VStack(alignment: .leading, spacing: 8) {
                            SectionHeader(title: "Elevation profile")
                            LineChart(values: route.elevations, color: Theme.hikeColor, fill: true)
                                .frame(height: 100)
                        }
                    }
                }

                Card {
                    VStack(spacing: 12) {
                        Toggle("Starred", isOn: $route.isStarred)
                        Divider()
                        Button {
                            exportRoute()
                        } label: {
                            Label("Export as GPX", systemImage: "square.and.arrow.up")
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        Divider()
                        Button(role: .destructive) {
                            store.deleteRoute(route)
                            dismiss()
                        } label: {
                            Label("Delete route", systemImage: "trash")
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    .font(.system(size: 14))
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .background(Theme.background(scheme))
        .navigationTitle(route.name)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showShare) {
            if let exportURL { ShareSheet(items: [exportURL]) }
        }
        .onChange(of: route) { store.updateRoute(route) }
    }

    /// Uses your own average speed for this sport rather than a generic guess.
    private var estimatedTime: TimeInterval {
        let relevant = store.activities.filter { $0.sport == route.sport && $0.movingTime > 300 }
        let speed: Double
        if relevant.isEmpty {
            speed = route.sport.isRide ? 6.5 : 2.8
        } else {
            let total = relevant.reduce(0.0) { $0 + $1.distance }
            let time = relevant.reduce(0.0) { $0 + $1.movingTime }
            speed = time > 0 ? total / time : 2.8
        }
        return route.estimatedTime(atSpeed: speed, sport: route.sport)
    }

    private func exportRoute() {
        let contents = Exporter.gpx(route: route)
        let filename = route.name.replacingOccurrences(of: "/", with: "-") + ".gpx"
        if let url = Exporter.write(contents, filename: filename) {
            exportURL = url
            showShare = true
        }
    }
}
