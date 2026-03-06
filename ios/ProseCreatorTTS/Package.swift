// swift-tools-version:5.9
// NOTE: This Package.swift is for dependency resolution only.
// The actual build uses the Xcode project (ProseCreatorTTS.xcodeproj).
// sherpa-onnx xcframeworks must be built separately via scripts/build-sherpa-onnx.sh
// and placed in the Frameworks/ directory before building.

import PackageDescription

let package = Package(
    name: "ProseCreatorTTS",
    platforms: [
        .iOS(.v16),
    ],
    products: [],
    dependencies: [],
    targets: []
)
