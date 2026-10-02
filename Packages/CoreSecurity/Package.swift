// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "CoreSecurity",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "CoreSecurity", targets: ["CoreSecurity"])
    ],
    dependencies: [
        .package(path: "../CoreDomain")
    ],
    targets: [
        .target(
            name: "CoreSecurity",
            dependencies: [.product(name: "CoreDomain", package: "CoreDomain")]
        ),
        .testTarget(
            name: "CoreSecurityTests",
            dependencies: [
                "CoreSecurity",
                .product(name: "CoreDomain", package: "CoreDomain")
            ]
        )
    ]
)
