// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "FeatureAuth",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "FeatureAuth", targets: ["FeatureAuth"])
    ],
    dependencies: [
        .package(path: "../CoreDomain"),
        .package(path: "../CoreSecurity"),
        .package(path: "../TestSupport")
    ],
    targets: [
        .target(
            name: "FeatureAuth",
            dependencies: [
                .product(name: "CoreDomain", package: "CoreDomain"),
                .product(name: "CoreSecurity", package: "CoreSecurity")
            ]
        ),
        .testTarget(
            name: "FeatureAuthTests",
            dependencies: [
                "FeatureAuth",
                .product(name: "CoreDomain", package: "CoreDomain"),
                .product(name: "TestSupport", package: "TestSupport")
            ]
        )
    ]
)
