import Foundation

// Usage:
//   StrideReference generate <outputDir> <fixturesDir>   write golden files from the real iOS code
//   StrideReference verify <dir>                          decode files written by the Android app with the real iOS decoders

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    exit(1)
}

let args = CommandLine.arguments
guard args.count >= 3 else { fail("usage: StrideReference generate <outDir> <fixturesDir> | verify <dir>") }

switch args[1] {
case "generate":
    guard args.count >= 4 else { fail("usage: StrideReference generate <outDir> <fixturesDir>") }
    let outDir = URL(fileURLWithPath: args[2])
    let fixtures = URL(fileURLWithPath: args[3])
    let scenarios = makeScenarios()
    let small = makeSmallScenarios(from: scenarios)
    do {
        try write(analysisGolden(scenarios), to: outDir.appendingPathComponent("analysis.json"))
        try write(segmentsGolden(scenarios), to: outDir.appendingPathComponent("segments.json"))
        try write(speechGolden(), to: outDir.appendingPathComponent("speech.json"))
        try write(fmtGolden(), to: outDir.appendingPathComponent("fmt.json"))
        let (exports, route) = exportsGolden(small)
        try write(exports, to: outDir.appendingPathComponent("exports.json"))
        try write(route, to: outDir.appendingPathComponent("route-export.json"))
        try write(try importsGolden(fixturesDir: fixtures, exports: exports), to: outDir.appendingPathComponent("imports.json"))
        try write(fitnessGolden(small), to: outDir.appendingPathComponent("fitness.json"))
        try write(liveGolden(scenarios), to: outDir.appendingPathComponent("live-segments.json"))
        try writeDocuments(scenarios: small, fullScenarios: scenarios, into: outDir)
    } catch {
        fail("generate failed: \(error)")
    }

case "verify":
    verifyDirectory(URL(fileURLWithPath: args[2]))

default:
    fail("unknown command \(args[1])")
}
