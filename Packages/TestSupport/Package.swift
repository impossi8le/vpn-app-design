// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "TestSupport",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [ .library(name: "TestSupport", targets: ["TestSupport"]) ],
    dependencies: [ .package(path: "../CoreDomain") ],
    targets: [
        .target(name: "TestSupport", dependencies: ["CoreDomain"]),
        .testTarget(name: "TestSupportTests", dependencies: ["TestSupport", "CoreDomain"])
    ]
)
