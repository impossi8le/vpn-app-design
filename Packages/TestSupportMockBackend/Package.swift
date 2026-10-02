// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "TestSupportMockBackend",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "TestSupportMockBackend", targets: ["TestSupportMockBackend"])
    ],
    dependencies: [
        .package(path: "../CoreDomain")
    ],
    targets: [
        .target(name: "TestSupportMockBackend", dependencies: ["CoreDomain"])
    ]
)
