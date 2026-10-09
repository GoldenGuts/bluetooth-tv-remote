import Foundation
import Security
import Testing
@testable import TVRemote

/// Protocol bytes and the pairing secret, checked against vectors from the Android (Java) code.
@MainActor
struct BoxProtocolTests {

    // Made with openssl; the expected values come from the Java BoxPairing, BoxIdentity and Proto.
    static let clientDer = "MIICpDCCAYwCCQC2WcDdpPZ2nzANBgkqhkiG9w0BAQsFADAUMRIwEAYDVQQDDAlhdHZyZW1vdGUwHhcNMjYxMDA5MTYyODM0WhcNMzYxMDA2MTYyODM0WjAUMRIwEAYDVQQDDAlhdHZyZW1vdGUwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCx3kw8cvGpt4NiwjKUw5TNSVfGvXtT1WJElck3gyNEB3Q73QAIINl+boW3rjpw2mIXI91CXzUvfu7UzeaH81dJ4hiYPFn5IKmbjQUCZk2rJuKrLIbVFi4VK2YhUKWXT2B1+8cYBDo0Ej+37VhE71COncef18eshr7nsuTLdEHCX/FDvRyBEfkgZxyIpMzqPnRffXe8hdrHMv+hWZ488+KP4Q5XMNABq6Eeis0z607hn8uKVSilgOzoAE/lQP0ytBGJT2gd++b8dNVuaxAiZ5PhhkbLU97F+5qv5F/PAr2bTDjhHg+6qcoY9QyitfVi0mqeRmJ1uTW23yPiawUi7J+DAgMBAAEwDQYJKoZIhvcNAQELBQADggEBADc0mtRwZHFdvzWDkbYQlFgiUPqKEV5aomn1kxl8XlbrE8DBtlxjpXbE6YvCzQa/IvZh3Z6P51bJC/H7hieGuev9Pv9IEESS8bCDTv0bKEX7O0qxp6vg6XFIuNpHXQPD/cqrI2kuGYb7Aj3kiTWoqPF8H5IMdlQmK8eoQ7CCDOO7BRqxJu09MlEmJCwVbgD21/AGpaabReQ3rcWmE+btYj1B1rAm9LOAMcRfMttC/Es9mNqPJOsyyANE5X+1QDbPgYaiZ08nh1jBkCHf/9PPzY9gXjiSfKB5d9MU7UDLQuSfAfnkhuqJr+HpMLOxt/sR6Hg9qsxCQ2fR+/WUGzq3F7g="
    static let boxDer = "MIIBkjCB/AIJAONTdLfXk+2OMA0GCSqGSIb3DQEBCwUAMA4xDDAKBgNVBAMMA2JveDAeFw0yNjEwMDkxNjI4MzRaFw0zNjEwMDYxNjI4MzRaMA4xDDAKBgNVBAMMA2JveDCBnzANBgkqhkiG9w0BAQEFAAOBjQAwgYkCgYEAzEh4edD4+XXrjJZUXL2uVPa+bpBHaHgF0wgtxljR0KtRZ9lQxvjZ3PDv2xDR3V/twSg2Mvkwz693ADR913Bf/UIh0o6ZwQ7HcDtEDZNX+ILOXxxH4xtFG2TqrL0y1GeFY1arvKdSy6sJ+5dXVzcMHT2MU5DyvLX1HqL81TsJxF8CAwEAATANBgkqhkiG9w0BAQsFAAOBgQDIFCX9udx4FVgLmWnwkkynf+Wd2QlXfuG1QmwdYL9u5c2RzdBH5HU5BU9byOU2RDuoiiWpArXZskBaV5BLo8d4cZdES+rW8F9JnRXhCPKKIDF5uob47KMaKN3eLowmAlztNZkSaBn7fsVHvhokNJHVzYcD/ATncW3QzEs+dy8RwQ=="

    static func cert(_ base64: String) -> SecCertificate {
        SecCertificateCreateWithData(nil, Data(base64Encoded: base64)! as CFData)!
    }

    static func hex(_ b: [UInt8]) -> String {
        b.map { String(format: "%02x", $0) }.joined()
    }

    @Test func pairingSecretMatchesJava() {
        let client = SecCertificateCopyKey(Self.cert(Self.clientDer))!
        let box = Self.cert(Self.boxDer)
        #expect(BoxPairing.secret(client: client, box: box, code: "01A1B2").map(Self.hex) == "01cddc536946ec76d91e6d06e8fab80cc38968a74009eabf34c3fef12587f852")
        #expect(BoxPairing.secret(client: client, box: box, code: "1309").map(Self.hex) == "1374ba07780ca94c86a9de47bfc2994445a3dcd8350e69ba0ea15dfe180744e2")
        // A typo in the check byte: no secret
        #expect(BoxPairing.secret(client: client, box: box, code: "02A1B2") == nil)
    }

    @Test func certificateHashMatchesJava() {
        #expect(BoxIdentity.hash(Self.cert(Self.boxDer)) == "0f327861f9fb918c3b23608c8fcc3f353830a7752ab3c6e77a084ebff92324d9")
    }

    @Test func protoFramesMatchJava() {
        let configure = Proto().message(1, Proto().varint(1, 611)
            .message(2, Proto().string(1, "iPhone").string(2, "TV Remote")
                .varint(3, 1).string(4, "1").string(5, "in.weenja.tvremote").string(6, "1"))).frame()
        #expect(Self.hex(configure) == "360a3408e304122f0a066950686f6e65120954562052656d6f746518012201312a12696e2e7765656e6a612e747672656d6f7465320131")
        #expect(Self.hex(Proto().message(10, Proto().varint(1, 19).varint(2, 3)).frame()) == "06520408131003")
        #expect(Self.hex(Proto().message(9, Proto().varint(1, 300_000)).frame()) == "064a0408e0a712")
        #expect(Self.hex(Proto().varint(1, 2).varint(2, 200)
            .message(20, Proto().message(1, Proto().varint(1, 3).varint(2, 6)).varint(3, 1)).frame()) == "10080210c801a201080a04080310061801")
    }

    @Test func protoParse() throws {
        let body = Array(Proto().varint(1, 2).varint(2, 200).message(11, Proto().string(1, "x")).bytes)
        let m = try Proto.parse(body)
        #expect(Proto.num(m, 1) == 2)
        #expect(Proto.num(m, 2) == 200)
        #expect(Proto.str(try Proto.sub(m, 11), 1) == "x")
        #expect(try Proto.frameLength([0x96, 0x01]) == 150)
        #expect(try Proto.frameLength([0x96]) == nil)
        #expect(throws: Proto.Bad.self) { try Proto.parse([0x0A, 0x05, 0x01]) }
    }

    @Test func v1Pieces() {
        // ack: remote id, zero padding, then strings with a 1-byte length; the last is the device name
        let ack: [UInt8] = [0, 0, 0, 1, 0, 0, 0, 0, 3] + Array("abc".utf8) + [7] + Array("AM9 PRO".utf8)
        #expect(BoxLink.v1Model(ack) == "AM9 PRO")
        let json = BoxPairing.json(10, "{}")
        #expect(json.prefix(4) == [0, 0, 0, UInt8(json.count - 4)])
        #expect(String(decoding: json.dropFirst(4), as: UTF8.self)
                == "{\"protocol_version\":1,\"payload\":{},\"type\":10,\"status\":200}")
        #expect(BoxPairing.number("type", "{\"payload\":{\"type\":3},\"type\":31,\"status\":200}") == 31)
        #expect(BoxPairing.quote("a\"b\\c\n") == "\"a\\\"b\\\\c\\u000a\"")
        #expect(BoxLink.int32(-1) == [255, 255, 255, 255])
        #expect(BoxLink.int64(258) == [0, 0, 0, 0, 0, 0, 1, 2])
    }

    @Test func webosPieces() {
        let p = TVLink.magicPacket("AA:bb:CC:dd:EE:ff")
        #expect(p?.count == 102)
        #expect(p?.prefix(6).allSatisfy { $0 == 0xFF } == true)
        #expect(p.map { Array($0[6..<12]) } == [0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF])
        #expect(p.map { Array($0[96..<102]) } == [0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF])
        #expect(TVLink.magicPacket("aa:bb:cc") == nil)
        #expect(TVLink.buttonMessage("UP") == "type:button\nname:UP\n\n")
        let first = TVLink.registration(clientKey: nil)
        let payload = first["payload"] as? [String: Any]
        #expect(first["type"] as? String == "register")
        #expect(payload?["pairingType"] as? String == "PROMPT")
        #expect(payload?["client-key"] == nil)
        let permissions = (payload?["manifest"] as? [String: Any])?["permissions"] as? [String] ?? []
        #expect(permissions.contains("CONTROL_POWER") && permissions.contains("CONTROL_INPUT_TEXT"))
        let later = TVLink.registration(clientKey: "abc")["payload"] as? [String: Any]
        #expect(later?["client-key"] as? String == "abc")
        #expect(TVLink.json(["b": 1, "a": "x"]) == #"{"a":"x","b":1}"#)
    }

    @Test func keyCodes() {
        #expect(Key.code(Key.up) == 19)
        #expect(Key.code(Key.power) == 26)
        #expect(Key.charCode("a") == 29)
        #expect(Key.charCode("Z") == 54)
        #expect(Key.charCode("0") == 7)
        #expect(Key.charCode("@") == 77)
        #expect(Key.charCode("é") == 0)
    }

    @Test func rsaNumbers() {
        // SEQUENCE { INTEGER 0x00C1 (leading zero), INTEGER 65537 }
        let pkcs1: [UInt8] = [0x30, 0x09, 0x02, 0x02, 0x00, 0xC1, 0x02, 0x03, 0x01, 0x00, 0x01]
        let n = BoxIdentity.rsaNumbers(pkcs1: pkcs1)
        #expect(n?.modulus == [0xC1])
        #expect(n?.exponent == [0x01, 0x00, 0x01])
    }

    /// The phone's own identity: made once in the Keychain, a valid certificate, the same on the next load.
    @Test func identityInKeychain() throws {
        let a = try BoxIdentity.load()
        #expect(SecCertificateCopySubjectSummary(a.cert) as String? == "atvremote")
        #expect(BoxIdentity.rsaNumbers(a.publicKey)?.modulus.count == 256)
        let der = SecCertificateCopyData(a.cert) as Data
        // DER: SEQUENCE, long form length
        #expect(der.first == 0x30)
        #expect(BoxIdentity.hash(a.cert).count == 64)
    }

    @Test func handMadeCertificateParses() throws {
        let attributes: [String: Any] = [kSecAttrKeyType as String: kSecAttrKeyTypeRSA, kSecAttrKeySizeInBits as String: 2048]
        var error: Unmanaged<CFError>?
        let key = try #require(SecKeyCreateRandomKey(attributes as CFDictionary, &error))
        let pub = try #require(SecKeyCopyPublicKey(key))
        let pkcs1 = try #require(SecKeyCopyExternalRepresentation(pub, &error) as Data?)
        let der = try BoxIdentity.certificate(pkcs1: [UInt8](pkcs1)) { tbs in
            [UInt8](SecKeyCreateSignature(key, .rsaSignatureMessagePKCS1v15SHA256, Data(tbs) as CFData, &error)! as Data)
        }
        let cert = try #require(SecCertificateCreateWithData(nil, Data(der) as CFData))
        #expect(SecCertificateCopySubjectSummary(cert) as String? == "atvremote")
        let certKey = try #require(SecCertificateCopyKey(cert))
        #expect(BoxIdentity.rsaNumbers(certKey)?.modulus == BoxIdentity.rsaNumbers(pub)?.modulus)
        // The pairing secret works with our own certificate on both sides (it only needs the RSA numbers)
        let code = (0..<256).lazy.map { String(format: "%02X", $0) + "A1B2" }
            .first { BoxPairing.secret(client: pub, box: cert, code: $0) != nil }
        #expect(code != nil)
    }
}
