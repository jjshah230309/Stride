import SwiftUI

@main
struct StrideApp: App {
    @StateObject private var store = AppStore()
    @StateObject private var session = RecordingSession()
    @StateObject private var health = HealthKitManager()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environmentObject(session)
                .environmentObject(health)
                .tint(Theme.green)
                .preferredColorScheme(.light)
                .onAppear {
                    session.prepare(store: store, health: health)
                    store.recomputeGearMileage()
                    store.runAutomaticBackupIfDue()
                }
                .onChange(of: store.settings) {
                    session.settings = store.settings
                }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background || phase == .inactive {
                store.flush()
            }
            if phase == .active {
                store.runAutomaticBackupIfDue()
            }
        }
    }
}
