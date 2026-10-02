import Foundation

public struct Connection: Equatable, Identifiable, Decodable {
    public enum SubscriptionStatus: String, Equatable, Decodable {
        case active, expired, revoked, pending
    }
    public let id: String
    public let name: String
    public let countryCode: String
    public let city: String
    public let startDate: Date
    public let endDate: Date
    public let status: SubscriptionStatus

    enum CodingKeys: String, CodingKey {
        case id, name, status
        case countryCode = "country_code"
        case city
        case startDate = "start_date"
        case endDate = "end_date"
    }

    // Сервер отдаёт location как вложенный объект; разворачиваем через отдельный init.
    private struct Location: Decodable { let countryCode: String; let city: String
        enum CodingKeys: String, CodingKey { case countryCode = "country_code"; case city } }
    private enum TopKeys: String, CodingKey { case id, name, location, startDate = "start_date",
                                              endDate = "end_date", status }

    public init(from decoder: Decoder) throws {
        let top = try decoder.container(keyedBy: TopKeys.self)
        id = try top.decode(String.self, forKey: .id)
        name = try top.decode(String.self, forKey: .name)
        let loc = try top.decode(Location.self, forKey: .location)
        countryCode = loc.countryCode
        city = loc.city
        startDate = try top.decode(Date.self, forKey: .startDate)
        endDate = try top.decode(Date.self, forKey: .endDate)
        status = try top.decode(SubscriptionStatus.self, forKey: .status)
    }

    public init(id: String, name: String, countryCode: String, city: String,
                startDate: Date, endDate: Date, status: SubscriptionStatus) {
        self.id = id; self.name = name; self.countryCode = countryCode; self.city = city
        self.startDate = startDate; self.endDate = endDate; self.status = status
    }
}

public struct Profile: Equatable {
    public let raw: Data
    public let version: String?
    public let hash: String?
    public init(raw: Data, version: String? = nil, hash: String? = nil) {
        self.raw = raw; self.version = version; self.hash = hash
    }
}

public struct StagedProfile {
    public let profile: Profile
    public let temporaryURL: URL
    public init(profile: Profile, temporaryURL: URL) {
        self.profile = profile; self.temporaryURL = temporaryURL
    }
}

public protocol ConfigService {
    func fetchConnections() async throws -> [Connection]
    func fetchProfile(id: Connection.ID) async throws -> Profile
}

public protocol ProfileStore {
    func load() throws -> Profile?
    func stage(_ raw: Data) throws -> StagedProfile
    func commit(_ staged: StagedProfile) throws
    func rollback() throws
}
