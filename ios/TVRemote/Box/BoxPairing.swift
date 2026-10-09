import CryptoKit
import Foundation
import Security

/**
 Pairing with an Android TV box (port 6467, the "polo" protocol of the Google TV remote).
 The box shows a code; the code proves that both sides see the same two certificates.
 Version 2 (newer Google TV): protobuf, 6-character code. Version 1 (older Android TV, Ugoos): JSON, 4 characters.
 The pairing tries version 2 first; a version-1 box answers that in JSON, and the pairing starts again in version 1.
 */
final class BoxPairing {

    /// The box shows the code now: ask for it, then call submit().
    var codeShown: (Int) -> Void = { _ in }
    var done: (Bool, String) -> Void = { _, _ in }

    private static let port: UInt16 = 6467
    private static let statusOK = 200
    private static let encodingHex = 3
    private static let roleInput = 1
    // message types; in version 2 they are the field numbers of the outer message
    private static let requestAck = 11, options = 20, configurationAck = 31, secretAck = 41

    private let host: String
    private let clientName: String
    private var conn: Conn?
    private var identity: BoxIdentity.Loaded?
    private var boxCert: SecCertificate?
    private var finished = false
    private(set) var version = 2

    init(host: String, clientName: String) {
        self.host = host
        self.clientName = clientName
    }

    func start() {
        Task { await run() }
    }

    func cancel() {
        finished = true
        conn?.close()
    }

    static func codeLength(_ version: Int) -> Int {
        version == 1 ? 4 : 6
    }

    /// Returns false at once if the code does not fit (a typo); the box keeps showing it, so try again.
    func submit(_ typed: String) -> Bool {
        let code = typed.trimmingCharacters(in: .whitespaces).uppercased()
        let n = Self.codeLength(version)
        guard code.count == n, code.allSatisfy({ $0.isHexDigit }), let identity, let boxCert,
              let secret = Self.secret(client: identity.publicKey, box: boxCert, code: code) else { return false }
        let frame = version == 1
            ? Self.json(40, "{\"secret\":\"\(Data(secret).base64EncodedString())\"}")
            : Self.outer().message(40, Proto().bytes(1, secret)).frame()
        conn?.send(frame) { [weak self] ok in
            if !ok { self?.finish(false, "Lost the link to the box.") }
        }
        return true
    }

    private func run() async {
        do {
            identity = try BoxIdentity.load()
            try await open()
            conn?.send(Self.outer().message(10, Proto().string(1, "atvremote").string(2, clientName)).frame())
            if try await conn?.peekByte() == 0 {
                // A 4-byte length in front of JSON: a version-1 box. It refused the version-2 request.
                version = 1
                conn?.close()
                try await open()
                conn?.send(Self.json(10, "{\"service_name\":\"androidtvremote\",\"client_name\":\(Self.quote(clientName))}"))
            }
            while !finished, let c = conn {
                if version == 1 {
                    let n = try await c.readUInt32()
                    guard n > 0 && n <= 1 << 16 else { throw LinkError.message("bad length") }
                    let m = String(decoding: try await c.read(n), as: UTF8.self)
                    try step(Self.number("status", m), Self.number("type", m))
                } else {
                    let m = try Proto.parse(try await c.readFrame())
                    var type = 0
                    for t in [Self.requestAck, Self.options, Self.configurationAck, Self.secretAck] where m[t] != nil {
                        type = t
                    }
                    try step(Int(Proto.num(m, 2)), type)
                }
            }
        } catch {
            finish(false, Conn.reason(error))
        }
        conn?.close()
    }

    private func open() async throws {
        guard let identity else { return }
        let c = Conn(host: host, port: Self.port, tls: .init(identity: identity.identity, pin: nil))
        conn = c
        try await c.open(timeout: 8)
        if finished { throw LinkError.message("cancelled") }
        boxCert = c.peer
        // No read timeout: the person may need a while to read the code
    }

    private func step(_ status: Int, _ type: Int) throws {
        guard status == Self.statusOK else {
            finish(false, status == 402 ? "Wrong code. Pair again." : "The box refused (status \(status)).")
            return
        }
        let n = Self.codeLength(version)
        switch type {
        case Self.requestAck:
            conn?.send(version == 1
                ? Self.json(20, "{\"output_encodings\":[{\"symbol_length\":\(n),\"type\":3}],"
                    + "\"input_encodings\":[{\"symbol_length\":\(n),\"type\":3}],\"preferred_role\":1}")
                : Self.outer().message(20, Proto().message(1, Self.encoding(n)).varint(3, Self.roleInput)).frame())
        case Self.options:
            conn?.send(version == 1
                ? Self.json(30, "{\"encoding\":{\"symbol_length\":\(n),\"type\":3},\"client_role\":1}")
                : Self.outer().message(30, Proto().message(1, Self.encoding(n)).varint(2, Self.roleInput)).frame())
        case Self.configurationAck:
            codeShown(n)
        case Self.secretAck:
            guard let boxCert else { throw LinkError.message("no box certificate") }
            BoxIdentity.savePin(boxCert, version: version)
            finish(true, "Paired")
        default:
            break
        }
    }

    private func finish(_ ok: Bool, _ message: String) {
        if finished { return }
        finished = true
        conn?.close()
        done(ok, message)
    }

    private static func outer() -> Proto {
        Proto().varint(1, 2).varint(2, statusOK)
    }

    private static func encoding(_ length: Int) -> Proto {
        Proto().varint(1, encodingHex).varint(2, length)
    }

    /// Version 1: 4-byte length, then the JSON message.
    static func json(_ type: Int, _ payload: String) -> [UInt8] {
        let b = Array("{\"protocol_version\":1,\"payload\":\(payload),\"type\":\(type),\"status\":200}".utf8)
        let n = b.count
        return [UInt8(n >> 24 & 0xFF), UInt8(n >> 16 & 0xFF), UInt8(n >> 8 & 0xFF), UInt8(n & 0xFF)] + b
    }

    /// The last match: the payload comes first and has its own "type" keys (the encodings).
    static func number(_ key: String, _ s: String) -> Int {
        guard let regex = try? NSRegularExpression(pattern: "\"\(key)\"\\s*:\\s*(\\d+)") else { return 0 }
        let ns = s as NSString
        var value = 0
        for m in regex.matches(in: s, range: NSRange(location: 0, length: ns.length)) {
            value = Int(ns.substring(with: m.range(at: 1))) ?? 0
        }
        return value
    }

    static func quote(_ s: String) -> String {
        var q = "\""
        for u in s.unicodeScalars {
            if u == "\"" || u == "\\" { q += "\\\(u)" }
            else if u.value < 0x20 { q += String(format: "\\u%04x", u.value) }
            else { q.unicodeScalars.append(u) }
        }
        return q + "\""
    }

    /**
     SHA-256 over both RSA keys and the code after its first 2 characters. The first 2 characters are the
     first byte of that hash: a check that the code (and the box) is right. Nil if the check fails.
     */
    static func secret(client: SecKey, box: SecCertificate, code: String) -> [UInt8]? {
        guard let boxKey = SecCertificateCopyKey(box),
              let c = BoxIdentity.rsaNumbers(client), let b = BoxIdentity.rsaNumbers(boxKey) else { return nil }
        return secret(client: c, box: b, code: code)
    }

    static func secret(client c: (modulus: [UInt8], exponent: [UInt8]), box b: (modulus: [UInt8], exponent: [UInt8]),
                       code: String) -> [UInt8]? {
        let chars = Array(code)
        guard chars.count >= 2, chars.count % 2 == 0, let check = UInt8(String(chars[0..<2]), radix: 16) else { return nil }
        var h = SHA256()
        h.update(data: c.modulus)
        h.update(data: c.exponent)
        h.update(data: b.modulus)
        h.update(data: b.exponent)
        var i = 2
        while i < chars.count {
            guard let byte = UInt8(String(chars[i..<i + 2]), radix: 16) else { return nil }
            h.update(data: [byte])
            i += 2
        }
        let d = Array(h.finalize())
        return d[0] == check ? d : nil
    }
}
