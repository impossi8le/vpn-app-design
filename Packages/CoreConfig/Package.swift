// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "CoreConfig",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "CoreConfig", targets: ["CoreConfig"])
    ],
    dependencies: [
        .package(path: "../CoreDomain")
    ],
    targets: [
        .target(name: "CoreConfig", dependencies: ["CoreDomain"]),
        .testTarget(name: "CoreConfigTests", dependencies: ["CoreConfig", "CoreDomain"])
    ]
)
