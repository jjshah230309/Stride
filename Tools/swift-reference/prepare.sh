#!/usr/bin/env bash
# Copies the pure-logic iOS sources into Sources/StrideReference/ios/ with the Apple-only imports
# removed, so they compile on Linux. The iOS tree is read, never modified.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ios="$here/../../Stride"
out="$here/Sources/StrideReference/ios"
rm -rf "$out"
mkdir -p "$out"

files=(
  Core/Speech.swift Core/Smoothing.swift Core/Geo.swift Core/Units.swift
  Models/Activity.swift Models/TrackPoint.swift Models/Segment.swift Models/Settings.swift Models/SportType.swift
  Analysis/ActivityAnalyzer.swift Analysis/Efforts.swift Analysis/Physiology.swift Analysis/SegmentMatcher.swift
  IO/FIT.swift IO/TCX.swift IO/FileFormats.swift IO/ActivityFile.swift IO/Backup.swift
  Store/Persistence.swift
  Engine/LiveSegmentEngine.swift
)

for f in "${files[@]}"; do
  python3 - "$ios/$f" "$out/$(basename "$f")" <<'PY'
import re, sys
src, dst = sys.argv[1], sys.argv[2]
text = open(src, encoding="utf-8").read()
for module in ("SwiftUI", "CoreLocation", "UniformTypeIdentifiers", "Combine"):
    text = text.replace("import %s\n" % module, "")
text = text.replace("import Foundation\n",
                    "import Foundation\n#if canImport(FoundationXML)\nimport FoundationXML\n#endif\n", 1)
if src.endswith("Persistence.swift"):
    # Never touch the real Documents folder: point the copied Store at a scratch directory.
    needle = "FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]"
    assert needle in text, "Persistence.swift changed; update prepare.sh"
    text = text.replace(needle, 'URL(fileURLWithPath: ProcessInfo.processInfo.environment["STRIDE_REFERENCE_DOCUMENTS"] ?? (NSTemporaryDirectory() + "stride-reference-documents"), isDirectory: true)')
    # Reproducible output: stable key order, and a fixed timestamp in the backup header.
    needle = "e.dateEncodingStrategy = .iso8601"
    assert needle in text, "Persistence.swift encoder changed; update prepare.sh"
    text = text.replace(needle, needle + "\n        e.outputFormatting = .sortedKeys")
if src.endswith("Backup.swift"):
    assert "createdAt: Date()," in text, "Backup.swift changed; update prepare.sh"
    text = text.replace("createdAt: Date(),", "createdAt: referenceNow,")
if src.endswith("ActivityFile.swift"):
    # the file-picker content types need UniformTypeIdentifiers, which has no Linux equivalent
    text = re.sub(r"    /// Types the file picker should offer\..*?(?=    static func detect)", "", text, flags=re.S)
    text = text.replace("(filename as NSString).pathExtension", "NSString(string: filename).pathExtension")
    text = text.replace("(filename as NSString).deletingPathExtension", "NSString(string: filename).deletingPathExtension")
open(dst, "w", encoding="utf-8").write(text)
PY
done
echo "prepared ${#files[@]} files in $out"
