// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "FeatureHome",
    platforms: [.iOS(.v15), .macOS(.v12)],
    dependencies: [
        .package(path: "../CoreDomain"),
        .package(path: "../CoreUI"),
        .package(path: "../TestSupport")
    ],
    targets: [
        .target(name: "FeatureHome", dependencies: [
            "CoreDomain",
            "CoreUI"
        ]),
        .testTarget(name: "FeatureHomeTests", dependencies: [
            "FeatureHome",
            "CoreDomain",
            "CoreUI",
            "TestSupport"
        ])
    ]
)
