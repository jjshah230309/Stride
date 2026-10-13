// swift-tools-version:5.9
import PackageDescription

// Compiles the platform-independent parts of the iOS app (copied and lightly patched by
// prepare.sh) into a command-line tool, so the *actual* Swift code can generate the golden files
// the Android tests compare against. Nothing here ships in either app.
let package = Package(
    name: "StrideReference",
    targets: [
        .executableTarget(
            name: "StrideReference",
            path: "Sources/StrideReference"
        )
    ]
)
