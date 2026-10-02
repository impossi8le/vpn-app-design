// swift-tools-version:5.9
import PackageDescription

// W4 — pure Swift core: .ovpn parsing + tunnel configuration building.
//
// IMPORTANT: this package must NEVER import TunnelKit or NetworkExtension.
// It is built and tested on Windows (see docs/architecture/2026-10-02-agent-workstreams.md §W4).
// Feeding the produced TunnelConfiguration to TunnelKit is deferred to a macOS-only adapter.
let package = Package(
    name: "TunnelKitCore",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "TunnelKitCore", targets: ["TunnelKitCore"])
    ],
    dependencies: [
        .package(path: "../CoreDomain")
    ],
    targets: [
        .target(name: "TunnelKitCore", dependencies: ["CoreDomain"]),
        .testTarget(name: "TunnelKitCoreTests", dependencies: ["TunnelKitCore", "CoreDomain"])
    ]
)
