import Foundation

/// A parsed `.ovpn` profile: ordered directives plus inline `<ca>…</ca>`-style blocks.
///
/// Parsing is a pure function of text → model. Nothing here touches the network,
/// NetworkExtension or TunnelKit, so it is fully testable on any platform.
public struct OpenVPNProfile: Equatable {

    public struct Directive: Equatable {
        public let name: String
        public let args: [String]
        public let line: Int
        /// Value as a single space-joined string (`nil` when the directive is bare).
        public var value: String? { args.isEmpty ? nil : args.joined(separator: " ") }
    }

    public let directives: [Directive]
    /// Inline block name (e.g. `ca`, `cert`, `key`, `tls-auth`, `tls-crypt`) → raw content.
    private let blocks: [String: String]

    public init(parsing text: String) throws {
        var directives: [Directive] = []
        var blocks: [String: String] = [:]

        // Split on any newline flavour without collapsing blank lines: physical line
        // numbers are part of the error contract, so we keep the original indexing.
        let lines = text.replacingOccurrences(of: "\r\n", with: "\n")
                        .replacingOccurrences(of: "\r", with: "\n")
                        .components(separatedBy: "\n")

        var openBlock: (name: String, startLine: Int, content: [String])?

        for (index, rawLine) in lines.enumerated() {
            let lineNumber = index + 1
            let trimmed = rawLine.trimmingCharacters(in: .whitespaces)

            if let open = openBlock {
                if trimmed == "</\(open.name)>" {
                    let content = open.content.joined(separator: "\n")
                    blocks[open.name] = content
                    openBlock = nil
                } else {
                    // Inside a block every line is content — including `;`/`#` chars.
                    openBlock?.content.append(rawLine)
                }
                continue
            }

            if trimmed.isEmpty || trimmed.hasPrefix("#") || trimmed.hasPrefix(";") { continue }

            if trimmed.hasPrefix("<") {
                guard let close = trimmed.firstIndex(of: ">") else {
                    throw OpenVPNParseError.unknownDirective(name: trimmed, line: lineNumber)
                }
                let name = String(trimmed[trimmed.index(after: trimmed.startIndex)..<close])
                guard Self.inlineBlockNames.contains(name) else {
                    throw OpenVPNParseError.unknownDirective(name: "<\(name)>", line: lineNumber)
                }
                openBlock = (name, lineNumber, [])
                continue
            }

            let tokens = trimmed.split(whereSeparator: { $0 == " " || $0 == "\t" }).map(String.init)
            guard let name = tokens.first else { continue }
            guard Self.knownDirectives.contains(name) else {
                throw OpenVPNParseError.unknownDirective(name: name, line: lineNumber)
            }
            directives.append(Directive(name: name, args: Array(tokens.dropFirst()), line: lineNumber))
        }

        if let open = openBlock {
            throw OpenVPNParseError.unterminatedInlineBlock(name: open.name, line: open.startLine)
        }

        self.directives = directives
        self.blocks = blocks
    }

    // MARK: Queries

    public func contains(_ name: String) -> Bool { directives.contains { $0.name == name } }

    public func allValues(for name: String) -> [Directive] { directives.filter { $0.name == name } }

    public func firstValue(for name: String) -> String? {
        directives.first { $0.name == name }?.value
    }

    /// Raw content of an inline block (may include leading non-PEM text).
    public func inlineBlock(_ name: String) -> String? { blocks[name] }

    /// The PEM/armor region of an inline block, located by scanning for the
    /// `-----BEGIN … -----` / `-----END … -----` pair. Real `<cert>`/`<key>` blocks
    /// carry a human-readable openssl dump before the armor, so anchoring on the
    /// first line of the block would be wrong.
    public func pemBlock(_ name: String) -> String? {
        guard let content = blocks[name] else { return nil }
        let lines = content.components(separatedBy: "\n")
        guard let start = lines.firstIndex(where: { $0.hasPrefix("-----BEGIN") && $0.hasSuffix("-----") }) else {
            return nil
        }
        guard let end = lines[start...].firstIndex(where: { $0.hasPrefix("-----END") && $0.hasSuffix("-----") }) else {
            return nil
        }
        return lines[start...end].joined(separator: "\n")
    }

    /// Endpoints declared by `remote` directives. `remote` accepts one, two or three
    /// tokens: `host`, `host port`, `host port proto`.
    public var remotes: [RemoteEndpoint] {
        allValues(for: "remote").compactMap { directive in
            guard let host = directive.args.first else { return nil }
            let port = directive.args.count > 1 ? Int(directive.args[1]) : nil
            let proto = directive.args.count > 2 ? directive.args[2] : nil
            return RemoteEndpoint(host: host, port: port, proto: proto)
        }
    }

    // MARK: Directive registry — unknown directives are a hard error, never a skip.

    static let inlineBlockNames: Set<String> = ["ca", "cert", "key", "tls-auth", "tls-crypt"]

    static let knownDirectives: Set<String> = [
        // Structural / transport
        "client", "tls-client", "dev", "dev-type", "proto", "remote", "remote-random",
        "resolv-retry", "nobind", "persist-key", "persist-tun", "verb", "mute",
        // Routing
        "redirect-gateway", "route", "route-nopull", "pull", "ifconfig",
        // TLS / crypto
        "remote-cert-tls", "remote-cert-ku", "key-direction", "cipher",
        "data-ciphers", "data-ciphers-fallback", "ncp-ciphers", "auth", "tls-version-min",
        "tls-cipher", "auth-nocache", "comp-lzo", "tun-mtu", "mssfix", "sndbuf", "rcvbuf",
        "reneg-sec", "float", "duplicate-cn",
        // Inline-block carriers (also recognised as blocks, listed for symmetry)
        "ca", "cert", "key", "tls-auth", "tls-crypt"
    ]
}

/// A single OpenVPN endpoint. `proto` is the three-token form's protocol (`udp`/`tcp`).
public struct RemoteEndpoint: Equatable {
    public let host: String
    public let port: Int?
    public let proto: String?
    public init(host: String, port: Int? = nil, proto: String? = nil) {
        self.host = host; self.port = port; self.proto = proto
    }
}

/// Typed parse failures. An unsupported directive is an error by contract (W4 §8):
/// silently skipping it could hide a security-relevant option.
public enum OpenVPNParseError: Error, Equatable {
    case unknownDirective(name: String, line: Int)
    case unterminatedInlineBlock(name: String, line: Int)
}
