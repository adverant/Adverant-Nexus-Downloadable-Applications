// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "NexusLocalCompute",
    platforms: [
        .macOS(.v13)
    ],
    targets: [
        .executableTarget(
            name: "NexusLocalCompute",
            path: "MageAgentMenuBar"
        )
    ]
)
