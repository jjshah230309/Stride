import SwiftUI

struct RootView: View {
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var session: RecordingSession
    @State private var tab: AppTab = .today
    @State private var showOnboarding = false

    var body: some View {
        VStack(spacing: 0) {
            content
            StrideTabBar(selection: $tab)
        }
        .background(Theme.pageBackground)
        .fullScreenCover(isPresented: $showOnboarding) {
            OnboardingView(isPresented: $showOnboarding)
        }
        .onAppear {
            if !store.settings.display.hasCompletedOnboarding {
                showOnboarding = true
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch tab {
        case .today: TodayView(tab: $tab)
        case .record: RecordView()
        case .progress: ProgressTabView()
        case .routes: RoutesView()
        }
    }
}

/// A short first-run flow: units, a couple of body numbers, and the permissions
/// the app genuinely needs.
struct OnboardingView: View {
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var session: RecordingSession
    @EnvironmentObject private var health: HealthKitManager
    @Binding var isPresented: Bool
    @State private var page = 0

    var body: some View {
        VStack(spacing: 0) {
            TabView(selection: $page) {
                welcome.tag(0)
                profile.tag(1)
                permissions.tag(2)
            }
            .tabViewStyle(.page)

            Button {
                if page < 2 {
                    withAnimation { page += 1 }
                } else {
                    store.settings.display.hasCompletedOnboarding = true
                    store.flush()
                    isPresented = false
                }
            } label: {
                Text(page < 2 ? "Continue" : "Start training")
                    .font(.system(size: 17, weight: .bold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 17)
                    .background(Theme.lime)
                    .foregroundStyle(Theme.deepGreen)
                    .clipShape(Capsule())
            }
            .buttonStyle(.plain)
            .padding(20)
        }
        .background(Theme.pageBackground)
    }

    private var welcome: some View {
        VStack(spacing: 18) {
            Spacer()
            ZStack {
                Circle().fill(Theme.lime).frame(width: 108, height: 108)
                Image(systemName: "figure.run")
                    .font(.system(size: 48, weight: .semibold))
                    .foregroundStyle(Theme.deepGreen)
            }
            Text("Stride")
                .font(.figure(40, .bold))
                .foregroundStyle(Theme.primaryText)
            Text("GPS tracking, live splits read aloud, segments and a full training log — all on your phone, nothing behind a paywall.")
                .font(.system(size: 16))
                .multilineTextAlignment(.center)
                .foregroundStyle(Theme.secondaryText)
                .padding(.horizontal, 32)
            Spacer()
        }
    }

    private var profile: some View {
        Form {
            Section("What should we call you?") {
                TextField("Your name", text: $store.settings.profile.name)
            }
            Section("Units") {
                Picker("Units", selection: $store.settings.display.units) {
                    ForEach(UnitSystem.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
            }
            Section("About you") {
                Stepper(value: $store.settings.profile.weightKg, in: 30...200, step: 0.5) {
                    LabeledContent("Weight", value: weightText)
                }
                Stepper(value: $store.settings.profile.birthYear, in: 1930...2018) {
                    LabeledContent("Born", value: String(store.settings.profile.birthYear))
                }
                Picker("Sex", selection: $store.settings.profile.isMale) {
                    Text("Male").tag(true)
                    Text("Female").tag(false)
                }
            }
            Section {
                Text("These feed the calorie, power and training-load estimates. You can change them later in your profile.")
                    .font(.footnote)
                    .foregroundStyle(Theme.secondaryText)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.pageBackground)
    }

    private var weightText: String {
        let u = store.settings.units
        return String(format: "%.1f %@", store.settings.profile.weightKg / u.kilogramsPerWeightUnit, u.weightUnit)
    }

    private var permissions: some View {
        VStack(alignment: .leading, spacing: 22) {
            Text("Permissions")
                .font(.screenTitle)
                .foregroundStyle(Theme.primaryText)
                .padding(.top, 40)

            permissionRow(symbol: "location.fill",
                          title: "Location",
                          detail: "Required to record your route, distance, pace and elevation. Choose \"Always\" so recording survives a locked screen on long rides.") {
                session.location.requestAlwaysAuthorization()
            }

            permissionRow(symbol: "heart.fill",
                          title: "Apple Health",
                          detail: "Optional. Reads heart rate from an Apple Watch and writes finished workouts back so they count towards your rings.") {
                health.requestAuthorization { _ in }
            }

            permissionRow(symbol: "speaker.wave.2.fill",
                          title: "Audio",
                          detail: "No permission needed — splits are spoken over your music, ducking it briefly rather than stopping it.",
                          buttonTitle: nil, action: nil)

            Spacer()
        }
        .padding(.horizontal, 28)
    }

    private func permissionRow(symbol: String, title: String, detail: String,
                               buttonTitle: String? = "Allow", action: (() -> Void)?) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: symbol)
                .font(.system(size: 17))
                .foregroundStyle(Theme.deepGreen)
                .frame(width: 38, height: 38)
                .background(Theme.lime)
                .clipShape(Circle())
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Theme.primaryText)
                Text(detail)
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.secondaryText)
                if let buttonTitle, let action {
                    Button(buttonTitle, action: action)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Theme.green)
                        .padding(.top, 2)
                }
            }
        }
    }
}
