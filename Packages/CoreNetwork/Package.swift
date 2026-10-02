// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "CoreNetwork",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "CoreNetwork", targets: ["CoreNetwork"])
    ],
    dependencies: [
        .package(path: "../CoreDomain"),
        // TestSupport участвует только в тестовом таргете (см. dependencies ниже).
        .package(path: "../TestSupport")
    ],
    targets: [
        .target(name: "CoreNetwork", dependencies: ["CoreDomain"]),
        .testTarget(
            name: "CoreNetworkTests",
            dependencies: [
                "CoreNetwork",
                "CoreDomain",
                .product(name: "TestSupport", package: "TestSupport")
            ]
        )
    ]
)
