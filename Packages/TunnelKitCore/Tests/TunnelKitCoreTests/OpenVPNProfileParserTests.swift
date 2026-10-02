// Tests/TunnelKitCoreTests/OpenVPNProfileParserTests.swift
import XCTest
@testable import TunnelKitCore

/// Anonymised, production-SHAPE .ovpn fixture. Real IPs, serials and key material
/// are replaced with dummies; the STRUCTURE (3-token `remote`, `remote-random`,
/// leading non-PEM text inside <cert>/<key>, PKCS#8 key, <tls-auth> + key-direction,
/// modern `data-ciphers`) is preserved. No live key material lives in the repo.
let realShapeProfile = """
# Anonymised production-shape profile
client
dev tun
proto udp
remote 203.0.113.10 443 udp
remote-random
resolv-retry infinite
nobind
redirect-gateway def1 bypass-dhcp
persist-key
persist-tun
remote-cert-tls server
key-direction 1
data-ciphers AES-256-GCM:AES-128-GCM:AES-256-CBC
data-ciphers-fallback AES-256-CBC
verb 3
<ca>
-----BEGIN CERTIFICATE-----
MIIBEXAMPLEcaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
-----END CERTIFICATE-----
</ca>
<cert>
Certificate:
    Data:
        Version: 3 (0x2)
        Serial Number:
            aa:bb:cc:dd:ee:ff
        Signature Algorithm: sha256WithRSAEncryption
        Issuer: CN=Example CA
        Validity
            Not Before: Jan  1 00:00:00 2026 GMT
        Subject: CN=client-0001
        Subject Public Key Info:
            Public Key Algorithm: rsaEncryption
-----BEGIN CERTIFICATE-----
MIIBEXAMPLEcertaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
-----END CERTIFICATE-----
</cert>
<key>
Bag Attributes
    friendlyName: client
    localKeyID: 01 02 03 04
Key Attributes: <No Attributes>
-----BEGIN PRIVATE KEY-----
MIIEEXAMPLEkeyaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
-----END PRIVATE KEY-----
</key>
<tls-auth>
-----BEGIN OpenVPN Static key V1-----
deadbeefdeadbeefdeadbeefdeadbeef
-----END OpenVPN Static key V1-----
</tls-auth>
"""

final class OpenVPNProfileParserTests: XCTestCase {

    // MARK: Realistic full profile with inline blocks

    func testParsesRealisticProfileWithInlineBlocks() throws {
        let profile = try OpenVPNProfile(parsing: realShapeProfile)

        XCTAssertTrue(profile.contains("client"))
        XCTAssertEqual(profile.firstValue(for: "dev"), "tun")
        XCTAssertEqual(profile.firstValue(for: "proto"), "udp")
        XCTAssertEqual(profile.firstValue(for: "verb"), "3")
        XCTAssertEqual(profile.firstValue(for: "key-direction"), "1")
        XCTAssertEqual(profile.firstValue(for: "data-ciphers"),
                       "AES-256-GCM:AES-128-GCM:AES-256-CBC")
        XCTAssertEqual(profile.firstValue(for: "data-ciphers-fallback"), "AES-256-CBC")
        XCTAssertEqual(profile.firstValue(for: "remote-cert-tls"), "server")
        XCTAssertEqual(profile.firstValue(for: "resolv-retry"), "infinite")

        // Inline blocks present and captured.
        XCTAssertNotNil(profile.inlineBlock("ca"))
        XCTAssertNotNil(profile.inlineBlock("cert"))
        XCTAssertNotNil(profile.inlineBlock("key"))
        XCTAssertNotNil(profile.inlineBlock("tls-auth"))
    }

    // MARK: 3-token remote + bare remote-random

    func testRemoteWithThreeTokens() throws {
        let profile = try OpenVPNProfile(parsing: realShapeProfile)
        XCTAssertEqual(profile.remotes,
                       [RemoteEndpoint(host: "203.0.113.10", port: 443, proto: "udp")])
        XCTAssertTrue(profile.contains("remote-random"))
    }

    func testRemoteWithHostOnly() throws {
        let profile = try OpenVPNProfile(parsing: "remote vpn.example.com")
        XCTAssertEqual(profile.remotes,
                       [RemoteEndpoint(host: "vpn.example.com", port: nil, proto: nil)])
    }

    func testRemoteWithHostAndPort() throws {
        let profile = try OpenVPNProfile(parsing: "remote vpn.example.com 1194")
        XCTAssertEqual(profile.remotes,
                       [RemoteEndpoint(host: "vpn.example.com", port: 1194, proto: nil)])
    }

    // MARK: Leading non-PEM text before the armor

    func testCertBlockWithLeadingTextYieldsPEM() throws {
        let profile = try OpenVPNProfile(parsing: realShapeProfile)
        let pem = try XCTUnwrap(profile.pemBlock("cert"))
        XCTAssertTrue(pem.hasPrefix("-----BEGIN CERTIFICATE-----"),
                      "PEM must start at the armor, not at the x509 text dump")
        XCTAssertFalse(pem.contains("Certificate:"),
                       "leading openssl dump must not leak into the PEM")
        XCTAssertTrue(pem.contains("-----END CERTIFICATE-----"))
    }

    func testKeyBlockWithLeadingTextYieldsPKCS8PEM() throws {
        let profile = try OpenVPNProfile(parsing: realShapeProfile)
        let pem = try XCTUnwrap(profile.pemBlock("key"))
        XCTAssertTrue(pem.hasPrefix("-----BEGIN PRIVATE KEY-----"),
                      "PKCS#8 armor must be located even with Bag Attributes before it")
        XCTAssertFalse(pem.contains("Bag Attributes"))
        XCTAssertTrue(pem.contains("-----END PRIVATE KEY-----"))
    }

    func testTLSBlockIsStaticKey() throws {
        let profile = try OpenVPNProfile(parsing: realShapeProfile)
        let block = try XCTUnwrap(profile.inlineBlock("tls-auth"))
        XCTAssertTrue(block.contains("-----BEGIN OpenVPN Static key V1-----"))
    }

    // MARK: Errors

    func testUnknownDirectiveIsTypedError() {
        let text = """
        client
        dev tun
        totally-bogus-directive foo
        """
        XCTAssertThrowsError(try OpenVPNProfile(parsing: text)) { error in
            XCTAssertEqual(error as? OpenVPNParseError,
                           .unknownDirective(name: "totally-bogus-directive", line: 3))
        }
    }

    func testUnterminatedInlineBlockIsTypedError() {
        let text = """
        client
        <ca>
        -----BEGIN CERTIFICATE-----
        MIIB
        """
        XCTAssertThrowsError(try OpenVPNProfile(parsing: text)) { error in
            XCTAssertEqual(error as? OpenVPNParseError,
                           .unterminatedInlineBlock(name: "ca", line: 2))
        }
    }

    // MARK: Comments / blank lines

    func testCommentsAndBlankLinesIgnored() throws {
        let text = """

        # a hash comment
        client

        ; a semicolon comment
        dev tun

        """
        let profile = try OpenVPNProfile(parsing: text)
        XCTAssertTrue(profile.contains("client"))
        XCTAssertTrue(profile.contains("dev"))
        XCTAssertEqual(profile.directives.count, 2)
    }

    func testSemicolonInsideInlineBlockIsContentNotComment() throws {
        let text = """
        <ca>
        -----BEGIN CERTIFICATE-----
        MIIBwith;semicolon
        -----END CERTIFICATE-----
        </ca>
        """
        let profile = try OpenVPNProfile(parsing: text)
        XCTAssertTrue(try XCTUnwrap(profile.inlineBlock("ca")).contains("with;semicolon"))
    }
}
