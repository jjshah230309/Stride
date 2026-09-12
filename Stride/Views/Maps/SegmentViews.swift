import SwiftUI

/// Carve a segment out of an activity by dragging the start and end handles.
struct SegmentCreatorView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss

    var activity: Activity
    var points: [TrackPoint]

    @State private var startFraction: Double = 0.1
    @State private var endFraction: Double = 0.4
    @State private var name = ""

    private var units: UnitSystem { store.settings.units }

    private var validPoints: [TrackPoint] {
        points.filter { $0.coord.isValid }
    }

    private var startIndex: Int {
        Swift.max(0, Swift.min(validPoints.count - 1, Int(startFraction * Double(Swift.max(1, validPoints.count - 1)))))
    }

    private var endIndex: Int {
        Swift.max(startIndex + 1, Swift.min(validPoints.count - 1, Int(endFraction * Double(Swift.max(1, validPoints.count - 1)))))
    }

    private var selectedPoints: [TrackPoint] {
        guard validPoints.count > 1, endIndex > startIndex else { return [] }
        return Array(validPoints[startIndex...endIndex])
    }

    private var selectedPath: [Coord] {
        selectedPoints.map(\.coord)
    }

    private var selectedDistance: Double {
        guard let first = selectedPoints.first, let last = selectedPoints.last else { return 0 }
        return last.d - first.d
    }

    private var selectedElevation: (gain: Double, loss: Double) {
        Smooth.elevationChange(Smooth.movingAverage(selectedPoints.map(\.alt), window: 7), threshold: 0.8)
    }

    private var selectedTime: TimeInterval {
        guard let first = selectedPoints.first, let last = selectedPoints.last else { return 0 }
        return last.t - first.t
    }

    var body: some View {
        VStack(spacing: 0) {
            RouteMap(path: activity.previewPath,
                     highlight: Geo.downsample(selectedPath, limit: 300),
                     color: .secondary)
                .frame(height: 260)

            ScrollView {
                VStack(spacing: 16) {
                    Card {
                        let columns = [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())]
                        LazyVGrid(columns: columns, spacing: 14) {
                            StatTile(label: "Length", value: Fmt.distance(selectedDistance, units),
                                     unit: units.distanceUnit, size: 19, alignment: .center)
                            StatTile(label: "Climb", value: Fmt.elevation(selectedElevation.gain, units),
                                     unit: units.elevationUnit, size: 19, alignment: .center)
                            StatTile(label: "Grade",
                                     value: String(format: "%.1f", selectedDistance > 0 ? selectedElevation.gain / selectedDistance * 100 : 0),
                                     unit: "%", size: 19, alignment: .center)
                            StatTile(label: "Your time", value: Fmt.duration(selectedTime), size: 19, alignment: .center)
                        }
                    }

                    Card {
                        VStack(alignment: .leading, spacing: 14) {
                            SectionHeader(title: "Trim")
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Start").font(.system(size: 12)).foregroundStyle(.secondary)
                                Slider(value: $startFraction, in: 0...0.95)
                                    .onChange(of: startFraction) {
                                        if endFraction <= startFraction + 0.02 {
                                            endFraction = Swift.min(1, startFraction + 0.02)
                                        }
                                    }
                            }
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Finish").font(.system(size: 12)).foregroundStyle(.secondary)
                                Slider(value: $endFraction, in: 0.05...1)
                                    .onChange(of: endFraction) {
                                        if startFraction >= endFraction - 0.02 {
                                            startFraction = Swift.max(0, endFraction - 0.02)
                                        }
                                    }
                            }
                        }
                    }

                    Card {
                        VStack(alignment: .leading, spacing: 10) {
                            SectionHeader(title: "Name")
                            TextField("Riverside climb", text: $name)
                                .textFieldStyle(.roundedBorder)
                            Text("Stride will search every activity you have recorded for previous attempts, then keep score every time you cover it again.")
                                .font(.system(size: 11))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .padding(16)
            }
        }
        .navigationTitle("New Segment")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .topBarTrailing) {
                Button("Create") { create() }
                    .fontWeight(.semibold)
                    .disabled(selectedDistance < 100 || name.isEmpty)
            }
        }
    }

    private func create() {
        let path = Geo.simplify(selectedPath, tolerance: 3)
        let elevations = Geo.downsample(selectedPoints.map(\.alt).map { $0 }, limit: path.count)
        var segment = Segment.make(name: name, sport: activity.sport, path: path,
                                   elevations: elevations.isEmpty ? selectedPoints.map(\.alt) : elevations)
        segment.createdFromActivityID = activity.id
        store.addSegment(segment)
        dismiss()
    }
}

/// The personal leaderboard: every attempt you have made, ranked.
struct SegmentDetailView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    @State var segment: Segment
    @State private var confirmDelete = false

    private var units: UnitSystem { store.settings.units }

    private var efforts: [SegmentEffort] {
        store.efforts(for: segment)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                RouteMap(path: segment.path, color: Theme.accent)
                    .frame(height: 220)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))

                Card {
                    let columns = [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())]
                    LazyVGrid(columns: columns, spacing: 16) {
                        StatTile(label: "Length", value: Fmt.distance(segment.distance, units),
                                 unit: units.distanceUnit, size: 20, alignment: .center)
                        StatTile(label: "Avg grade", value: String(format: "%.1f", segment.averageGrade * 100),
                                 unit: "%", size: 20, alignment: .center)
                        StatTile(label: "Climb", value: Fmt.elevation(segment.elevationGain, units),
                                 unit: units.elevationUnit, size: 20, alignment: .center)
                    }
                }

                if efforts.count > 1 {
                    Card {
                        VStack(alignment: .leading, spacing: 8) {
                            SectionHeader(title: "Progress")
                            LineChart(values: efforts.sorted { $0.date < $1.date }.map(\.time),
                                      color: Theme.accent, fill: true)
                                .frame(height: 90)
                            HStack {
                                Text("Oldest").font(.system(size: 10)).foregroundStyle(.secondary)
                                Spacer()
                                Text("Latest").font(.system(size: 10)).foregroundStyle(.secondary)
                            }
                        }
                    }
                }

                Card {
                    VStack(alignment: .leading, spacing: 10) {
                        SectionHeader(title: "Your leaderboard")
                        if efforts.isEmpty {
                            Text("No efforts recorded yet. Go and ride or run it.")
                                .font(.system(size: 13))
                                .foregroundStyle(.secondary)
                        }
                        ForEach(Array(efforts.enumerated()), id: \.element.id) { index, effort in
                            effortRow(index: index, effort: effort)
                        }
                    }
                }

                Card {
                    VStack(spacing: 12) {
                        Toggle("Race this live", isOn: $segment.isStarred)
                            .font(.system(size: 14))
                        Text("Starred segments are watched while you record. Stride announces when you enter one and calls out how you compare with your best.")
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                        Divider()
                        Button(role: .destructive) {
                            confirmDelete = true
                        } label: {
                            Label("Delete segment", systemImage: "trash")
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .font(.system(size: 14))
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .background(Theme.background(scheme))
        .navigationTitle(segment.name)
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog("Delete this segment and all its efforts?",
                            isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                store.deleteSegment(segment)
                dismiss()
            }
        }
        .onChange(of: segment) {
            store.updateSegment(segment)
        }
    }

    private func effortRow(index: Int, effort: SegmentEffort) -> some View {
        HStack(spacing: 10) {
            Text("\(index + 1)")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(index == 0 ? Theme.warning : .secondary)
                .frame(width: 20, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(Fmt.shortDayFormatter.string(from: effort.date))
                    .font(.system(size: 13))
                Text(paceText(effort))
                    .font(.system(size: 10))
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if let hr = effort.avgHR {
                Text("\(Int(hr)) bpm").font(.system(size: 11)).foregroundStyle(.secondary)
            }
            Text(Fmt.duration(effort.time))
                .font(.system(size: 15, weight: .semibold))
                .monospacedDigit()
        }
    }

    private func paceText(_ effort: SegmentEffort) -> String {
        if segment.sport.usesPace {
            return Fmt.pace(distance: segment.distance, time: effort.time, units) + " " + units.paceUnit
        }
        return Fmt.avgSpeed(distance: segment.distance, time: effort.time, units) + " " + units.speedUnit
    }
}
