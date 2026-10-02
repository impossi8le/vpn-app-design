import Foundation
import CoreDomain

/// Persistence boundary for an in-flight login operation. The controller in
/// `FeatureAuth` needs to survive a trip to the background, so the whole
/// operation — including the secret — is kept here rather than in memory.
///
/// Per architecture §4.4 the auth-operation secret belongs to CoreSecurity.
public protocol AuthOperationStore {
    func save(_ operation: AuthOperation) throws
    func load() throws -> AuthOperation?
    func clear() throws
}
