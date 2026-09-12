import SwiftUI
import UIKit
import MapKit

enum MapStyleOption: String, CaseIterable, Identifiable {
    case standard, hybrid, satellite
    var id: String { rawValue }
    var name: String { rawValue.capitalized }
    var mkType: MKMapType {
        switch self {
        case .standard: return .mutedStandard
        case .hybrid: return .hybrid
        case .satellite: return .satellite
        }
    }
}

/// Draws one or more paths on a map. Used for activity maps, route previews,
/// segment previews and the live recording map.
struct RouteMap: UIViewRepresentable {
    var path: [Coord]
    var highlight: [Coord] = []
    var comparison: [Coord] = []
    var showsStartEnd: Bool = true
    var interactive: Bool = true
    var followsUser: Bool = false
    var showsUserLocation: Bool = false
    var color: Color = Theme.accent
    var style: MapStyleOption = .standard
    var padding: CGFloat = 30

    func makeUIView(context: Context) -> MKMapView {
        let map = MKMapView()
        map.delegate = context.coordinator
        map.isZoomEnabled = interactive
        map.isScrollEnabled = interactive
        map.isRotateEnabled = false
        map.isPitchEnabled = false
        map.showsUserLocation = showsUserLocation
        map.mapType = style.mkType
        map.pointOfInterestFilter = .excludingAll
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        map.mapType = style.mkType
        map.showsUserLocation = showsUserLocation

        let signature = "\(path.count)-\(highlight.count)-\(comparison.count)"
        if context.coordinator.signature != signature {
            context.coordinator.signature = signature
            map.removeOverlays(map.overlays)
            map.removeAnnotations(map.annotations.filter { !($0 is MKUserLocation) })

            if comparison.count > 1 {
                let line = MKPolyline(coordinates: comparison.map(\.clCoordinate), count: comparison.count)
                line.title = "comparison"
                map.addOverlay(line)
            }
            if path.count > 1 {
                let line = MKPolyline(coordinates: path.map(\.clCoordinate), count: path.count)
                line.title = "main"
                map.addOverlay(line)
            }
            if highlight.count > 1 {
                let line = MKPolyline(coordinates: highlight.map(\.clCoordinate), count: highlight.count)
                line.title = "highlight"
                map.addOverlay(line)
            }
            if showsStartEnd, let start = path.first, let end = path.last {
                let a = MKPointAnnotation()
                a.coordinate = start.clCoordinate
                a.title = "start"
                map.addAnnotation(a)
                if path.count > 2 {
                    let b = MKPointAnnotation()
                    b.coordinate = end.clCoordinate
                    b.title = "finish"
                    map.addAnnotation(b)
                }
            }
            context.coordinator.mainColor = UIColor(color)
            fitRegion(map)
        }

        if followsUser, let user = map.userLocation.location {
            let region = MKCoordinateRegion(center: user.coordinate,
                                            latitudinalMeters: 500, longitudinalMeters: 500)
            map.setRegion(region, animated: true)
        }
    }

    private func fitRegion(_ map: MKMapView) {
        let all = path + highlight + comparison
        guard all.count > 1 else {
            if let single = all.first {
                map.setRegion(MKCoordinateRegion(center: single.clCoordinate,
                                                 latitudinalMeters: 800, longitudinalMeters: 800),
                              animated: false)
            }
            return
        }
        var rect = MKMapRect.null
        for c in all where c.isValid {
            let point = MKMapPoint(c.clCoordinate)
            rect = rect.union(MKMapRect(x: point.x, y: point.y, width: 0.1, height: 0.1))
        }
        guard !rect.isNull else { return }
        let inset = UIEdgeInsets(top: padding, left: padding, bottom: padding, right: padding)
        map.setVisibleMapRect(rect, edgePadding: inset, animated: false)
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(mainColor: UIColor(color))
    }

    final class Coordinator: NSObject, MKMapViewDelegate {
        var signature: String = ""
        var mainColor: UIColor

        init(mainColor: UIColor) {
            self.mainColor = mainColor
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            if let heat = overlay as? HeatOverlay {
                return HeatOverlayRenderer(overlay: heat)
            }
            guard let line = overlay as? MKPolyline else {
                return MKOverlayRenderer(overlay: overlay)
            }
            let renderer = MKPolylineRenderer(polyline: line)
            switch line.title {
            case "highlight":
                renderer.strokeColor = UIColor.systemYellow
                renderer.lineWidth = 6
            case "comparison":
                renderer.strokeColor = UIColor.secondaryLabel.withAlphaComponent(0.7)
                renderer.lineWidth = 3
                renderer.lineDashPattern = [4, 6]
            default:
                renderer.strokeColor = mainColor
                renderer.lineWidth = 4
            }
            renderer.lineCap = .round
            renderer.lineJoin = .round
            return renderer
        }

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            guard !(annotation is MKUserLocation) else { return nil }
            let identifier = "endpoint"
            let view = mapView.dequeueReusableAnnotationView(withIdentifier: identifier)
                ?? MKAnnotationView(annotation: annotation, reuseIdentifier: identifier)
            view.annotation = annotation
            let isStart = annotation.title == "start"
            let size: CGFloat = 14
            let dot = UIView(frame: CGRect(x: 0, y: 0, width: size, height: size))
            dot.backgroundColor = isStart ? UIColor.systemGreen : UIColor.systemRed
            dot.layer.cornerRadius = size / 2
            dot.layer.borderWidth = 2.5
            dot.layer.borderColor = UIColor.white.cgColor
            view.frame = dot.frame
            view.subviews.forEach { $0.removeFromSuperview() }
            view.addSubview(dot)
            view.canShowCallout = false
            return view
        }
    }
}

// MARK: - Personal heatmap

/// Every path you have ever recorded, drawn in one pass with additive blending so
/// the roads you use most glow brightest.
final class HeatOverlay: NSObject, MKOverlay {
    let paths: [[CLLocationCoordinate2D]]
    let coordinate: CLLocationCoordinate2D
    let boundingMapRect: MKMapRect

    init(paths: [[CLLocationCoordinate2D]]) {
        self.paths = paths
        var rect = MKMapRect.null
        for path in paths {
            for c in path {
                let point = MKMapPoint(c)
                rect = rect.union(MKMapRect(x: point.x, y: point.y, width: 0.1, height: 0.1))
            }
        }
        let resolved = rect.isNull ? MKMapRect.world : rect
        self.boundingMapRect = resolved
        self.coordinate = MKMapPoint(x: resolved.midX, y: resolved.midY).coordinate
        super.init()
    }
}

final class HeatOverlayRenderer: MKOverlayRenderer {
    override func draw(_ mapRect: MKMapRect, zoomScale: MKZoomScale, in context: CGContext) {
        guard let heat = overlay as? HeatOverlay else { return }
        let lineWidth = MKRoadWidthAtZoomScale(zoomScale) * 1.6
        context.setBlendMode(.plusLighter)
        context.setLineWidth(lineWidth)
        context.setLineCap(.round)
        context.setLineJoin(.round)
        context.setStrokeColor(UIColor(red: 1.0, green: 0.35, blue: 0.17, alpha: 0.30).cgColor)

        for path in heat.paths where path.count > 1 {
            let cgPath = CGMutablePath()
            var started = false
            for c in path {
                let point = self.point(for: MKMapPoint(c))
                if started {
                    cgPath.addLine(to: point)
                } else {
                    cgPath.move(to: point)
                    started = true
                }
            }
            context.addPath(cgPath)
            context.strokePath()
        }
    }
}

struct HeatMap: UIViewRepresentable {
    var paths: [[Coord]]
    var style: MapStyleOption = .standard

    func makeUIView(context: Context) -> MKMapView {
        let map = MKMapView()
        map.delegate = context.coordinator
        map.mapType = style.mkType
        map.pointOfInterestFilter = .excludingAll
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        map.mapType = style.mkType
        let signature = "\(paths.count)-\(paths.reduce(0) { $0 + $1.count })"
        guard context.coordinator.signature != signature else { return }
        context.coordinator.signature = signature
        map.removeOverlays(map.overlays)
        let converted = paths.map { $0.filter(\.isValid).map(\.clCoordinate) }.filter { $0.count > 1 }
        guard !converted.isEmpty else { return }
        let overlay = HeatOverlay(paths: converted)
        map.addOverlay(overlay)
        map.setVisibleMapRect(overlay.boundingMapRect,
                              edgePadding: UIEdgeInsets(top: 24, left: 24, bottom: 24, right: 24),
                              animated: false)
    }

    func makeCoordinator() -> RouteMap.Coordinator {
        RouteMap.Coordinator(mainColor: UIColor(Theme.accent))
    }
}

// MARK: - Tap-to-build map

/// The route builder's canvas: tap to drop waypoints, drag the map as normal.
struct EditableMap: UIViewRepresentable {
    @Binding var waypoints: [Coord]
    var renderedPath: [Coord]
    var style: MapStyleOption = .standard
    var initialCenter: Coord?

    func makeUIView(context: Context) -> MKMapView {
        let map = MKMapView()
        map.delegate = context.coordinator
        map.showsUserLocation = true
        map.mapType = style.mkType
        map.pointOfInterestFilter = .excludingAll
        let tap = UITapGestureRecognizer(target: context.coordinator,
                                         action: #selector(Coordinator.handleTap(_:)))
        map.addGestureRecognizer(tap)
        context.coordinator.map = map
        if let center = initialCenter {
            map.setRegion(MKCoordinateRegion(center: center.clCoordinate,
                                             latitudinalMeters: 2500, longitudinalMeters: 2500),
                          animated: false)
        }
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        map.mapType = style.mkType
        context.coordinator.parent = self
        let signature = "\(waypoints.count)-\(renderedPath.count)"
        guard context.coordinator.signature != signature else { return }
        context.coordinator.signature = signature

        map.removeOverlays(map.overlays)
        map.removeAnnotations(map.annotations.filter { !($0 is MKUserLocation) })

        let line = renderedPath.count > 1 ? renderedPath : waypoints
        if line.count > 1 {
            let polyline = MKPolyline(coordinates: line.map(\.clCoordinate), count: line.count)
            polyline.title = "main"
            map.addOverlay(polyline)
        }
        for (i, w) in waypoints.enumerated() {
            let a = MKPointAnnotation()
            a.coordinate = w.clCoordinate
            a.title = i == 0 ? "start" : (i == waypoints.count - 1 ? "finish" : "mid")
            map.addAnnotation(a)
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    final class Coordinator: NSObject, MKMapViewDelegate {
        var parent: EditableMap
        var signature: String = ""
        weak var map: MKMapView?

        init(parent: EditableMap) {
            self.parent = parent
        }

        @objc func handleTap(_ gesture: UITapGestureRecognizer) {
            guard let map else { return }
            let point = gesture.location(in: map)
            let coordinate = map.convert(point, toCoordinateFrom: map)
            parent.waypoints.append(Coord(coordinate))
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            guard let line = overlay as? MKPolyline else { return MKOverlayRenderer(overlay: overlay) }
            let renderer = MKPolylineRenderer(polyline: line)
            renderer.strokeColor = UIColor(Theme.accent)
            renderer.lineWidth = 4
            renderer.lineCap = .round
            return renderer
        }

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            guard !(annotation is MKUserLocation) else { return nil }
            let identifier = "waypoint"
            let view = mapView.dequeueReusableAnnotationView(withIdentifier: identifier)
                ?? MKAnnotationView(annotation: annotation, reuseIdentifier: identifier)
            view.annotation = annotation
            view.subviews.forEach { $0.removeFromSuperview() }
            let isEnd = annotation.title == "start" || annotation.title == "finish"
            let size: CGFloat = isEnd ? 14 : 9
            let dot = UIView(frame: CGRect(x: 0, y: 0, width: size, height: size))
            dot.backgroundColor = annotation.title == "start" ? .systemGreen
                : (annotation.title == "finish" ? .systemRed : UIColor(Theme.accent))
            dot.layer.cornerRadius = size / 2
            dot.layer.borderWidth = 2
            dot.layer.borderColor = UIColor.white.cgColor
            view.frame = dot.frame
            view.addSubview(dot)
            view.canShowCallout = false
            return view
        }
    }
}

/// Snap a set of tapped waypoints onto real paths using MapKit's own directions.
enum RouteSnapper {
    static func snap(waypoints: [Coord], walking: Bool, completion: @escaping ([Coord]) -> Void) {
        guard waypoints.count > 1 else {
            completion(waypoints)
            return
        }
        var result: [Coord] = [waypoints[0]]
        var index = 0

        func step() {
            guard index < waypoints.count - 1 else {
                completion(result)
                return
            }
            let from = waypoints[index]
            let to = waypoints[index + 1]
            let request = MKDirections.Request()
            request.source = MKMapItem(placemark: MKPlacemark(coordinate: from.clCoordinate))
            request.destination = MKMapItem(placemark: MKPlacemark(coordinate: to.clCoordinate))
            request.transportType = walking ? .walking : .automobile
            request.requestsAlternateRoutes = false

            MKDirections(request: request).calculate { response, _ in
                if let route = response?.routes.first {
                    let count = route.polyline.pointCount
                    var coords = [CLLocationCoordinate2D](repeating: kCLLocationCoordinate2DInvalid, count: count)
                    route.polyline.getCoordinates(&coords, range: NSRange(location: 0, length: count))
                    result.append(contentsOf: coords.map(Coord.init))
                } else {
                    result.append(to)
                }
                index += 1
                step()
            }
        }
        step()
    }
}
