import CryptoKit
import Foundation
import Security

/**
 The phone's key and self-signed certificate, made once and kept in the Keychain. The box remembers the
 certificate at pairing, so it must never change. Also keeps the box certificate hash from the pairing (the pin).
 */
enum BoxIdentity {

    struct Loaded {
        let identity: SecIdentity
        let cert: SecCertificate
        let publicKey: SecKey
    }

    private static let tag = Data("in.weenja.tvremote.client".utf8)
    private static let label = "TV Remote client"
    private static let pinKey = "box.pin"
    private static let versionKey = "box.version"
    private static var cached: Loaded?

    enum Failure: Error, CustomStringConvertible {
        case keychain(String, OSStatus)
        var description: String {
            switch self {
            case .keychain(let what, let status): return "\(what) failed (\(status))"
            }
        }
    }

    static func load() throws -> Loaded {
        if let cached { return cached }
        if let found = find() {
            cached = found
            return found
        }
        try create()
        guard let made = find() else { throw Failure.keychain("identity lookup", errSecItemNotFound) }
        cached = made
        return made
    }

    // MARK: - the pin from the pairing

    /// The hash of the box certificate from the last pairing, or nil.
    static var pin: String? { UserDefaults.standard.string(forKey: pinKey) }

    /// The protocol version found at pairing: 1 or 2.
    static var version: Int { UserDefaults.standard.integer(forKey: versionKey) == 1 ? 1 : 2 }

    static var paired: Bool { pin != nil }

    static func savePin(_ box: SecCertificate, version: Int) {
        UserDefaults.standard.set(hash(box), forKey: pinKey)
        UserDefaults.standard.set(version, forKey: versionKey)
    }

    static func hash(_ cert: SecCertificate) -> String {
        let der = SecCertificateCopyData(cert) as Data
        return SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
    }

    // MARK: - Keychain

    private static func find() -> Loaded? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassIdentity,
            kSecMatchLimit as String: kSecMatchLimitAll,
            kSecReturnRef as String: true,
        ]
        var items: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &items) == errSecSuccess,
              let identities = items as? [SecIdentity] else { return nil }
        for identity in identities {
            var cert: SecCertificate?
            guard SecIdentityCopyCertificate(identity, &cert) == errSecSuccess, let cert,
                  SecCertificateCopySubjectSummary(cert) as String? == "atvremote",
                  let key = SecCertificateCopyKey(cert) else { continue }
            return Loaded(identity: identity, cert: cert, publicKey: key)
        }
        return nil
    }

    private static func create() throws {
        // A half-made identity from an earlier run: start clean.
        SecItemDelete([kSecClass as String: kSecClassKey, kSecAttrApplicationTag as String: tag] as CFDictionary)
        SecItemDelete([kSecClass as String: kSecClassCertificate, kSecAttrLabel as String: label] as CFDictionary)

        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeySizeInBits as String: 2048,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: tag,
                kSecAttrLabel as String: label,
                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ],
        ]
        var error: Unmanaged<CFError>?
        guard let privateKey = SecKeyCreateRandomKey(attributes as CFDictionary, &error),
              let publicKey = SecKeyCopyPublicKey(privateKey),
              let pkcs1 = SecKeyCopyExternalRepresentation(publicKey, &error) as Data? else {
            throw Failure.keychain("key generation", errSecParam)
        }
        let der = try certificate(pkcs1: [UInt8](pkcs1)) { tbs in
            guard let sig = SecKeyCreateSignature(privateKey, .rsaSignatureMessagePKCS1v15SHA256,
                                                  Data(tbs) as CFData, &error) as Data? else {
                throw Failure.keychain("signing", errSecParam)
            }
            return [UInt8](sig)
        }
        guard let cert = SecCertificateCreateWithData(nil, Data(der) as CFData) else {
            throw Failure.keychain("certificate", errSecParam)
        }
        let status = SecItemAdd([
            kSecClass as String: kSecClassCertificate,
            kSecValueRef as String: cert,
            kSecAttrLabel as String: label,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ] as CFDictionary, nil)
        guard status == errSecSuccess || status == errSecDuplicateItem else {
            throw Failure.keychain("certificate store", status)
        }
    }

    // MARK: - a self-signed X.509 certificate, DER by hand

    private static let oidSha256Rsa: [UInt8] = [0x06, 0x09, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0B]
    private static let oidRsa: [UInt8] = [0x06, 0x09, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x01]
    private static let oidCn: [UInt8] = [0x06, 0x03, 0x55, 0x04, 0x03]

    /// CN=atvremote, valid from yesterday for 20 years. pkcs1: the RSA public key (SEQUENCE of modulus, exponent).
    static func certificate(pkcs1: [UInt8], now: Date = Date(),
                            sign: ([UInt8]) throws -> [UInt8]) throws -> [UInt8] {
        let name = tlv(0x30, tlv(0x31, tlv(0x30, oidCn + tlv(0x0C, Array("atvremote".utf8)))))
        let alg = tlv(0x30, oidSha256Rsa + [0x05, 0x00])
        let day: TimeInterval = 86_400
        let validity = tlv(0x30, time(now - day) + time(now + 20 * 365 * day))
        var serial = UInt64.random(in: 1...(UInt64(1) << 62))
        var serialBytes: [UInt8] = []
        while serial > 0 {
            serialBytes.insert(UInt8(serial & 0xFF), at: 0)
            serial >>= 8
        }
        if serialBytes[0] & 0x80 != 0 { serialBytes.insert(0, at: 0) }
        let spki = tlv(0x30, tlv(0x30, oidRsa + [0x05, 0x00]) + tlv(0x03, [0] + pkcs1))
        let version = tlv(0xA0, tlv(0x02, [2]))
        let tbs = tlv(0x30, version + tlv(0x02, serialBytes) + alg + name + validity + name + spki)
        let sig = try sign(tbs)
        return tlv(0x30, tbs + alg + tlv(0x03, [0] + sig))
    }

    /// UTCTime: fine until 2049.
    private static func time(_ date: Date) -> [UInt8] {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyMMddHHmmss'Z'"
        return tlv(0x17, Array(f.string(from: date).utf8))
    }

    static func tlv(_ tag: UInt8, _ value: [UInt8]) -> [UInt8] {
        var o: [UInt8] = [tag]
        let n = value.count
        if n < 0x80 {
            o.append(UInt8(n))
        } else {
            let bytes = n > 0xFFFF ? 3 : n > 0xFF ? 2 : 1
            o.append(0x80 | UInt8(bytes))
            for i in stride(from: bytes - 1, through: 0, by: -1) { o.append(UInt8((n >> (8 * i)) & 0xFF)) }
        }
        return o + value
    }

    // MARK: - RSA numbers for the pairing secret

    /// Modulus and exponent of an RSA public key, without leading zero bytes.
    static func rsaNumbers(_ key: SecKey) -> (modulus: [UInt8], exponent: [UInt8])? {
        var error: Unmanaged<CFError>?
        guard let data = SecKeyCopyExternalRepresentation(key, &error) as Data? else { return nil }
        return rsaNumbers(pkcs1: [UInt8](data))
    }

    /// PKCS#1 RSAPublicKey: SEQUENCE { INTEGER modulus, INTEGER exponent }.
    static func rsaNumbers(pkcs1 b: [UInt8]) -> (modulus: [UInt8], exponent: [UInt8])? {
        var pos = 0
        func header(_ tag: UInt8) -> Int? {
            guard pos + 2 <= b.count, b[pos] == tag else { return nil }
            pos += 1
            var n = Int(b[pos])
            pos += 1
            if n & 0x80 != 0 {
                let count = n & 0x7F
                guard count <= 3, pos + count <= b.count else { return nil }
                n = 0
                for _ in 0..<count {
                    n = n << 8 | Int(b[pos])
                    pos += 1
                }
            }
            return pos + n <= b.count ? n : nil
        }
        func integer() -> [UInt8]? {
            guard let n = header(0x02) else { return nil }
            var v = Array(b[pos..<pos + n])
            pos += n
            while v.count > 1 && v[0] == 0 { v.removeFirst() }
            return v
        }
        guard header(0x30) != nil, let m = integer(), let e = integer() else { return nil }
        return (m, e)
    }
}
