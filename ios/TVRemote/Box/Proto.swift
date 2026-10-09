/// The few protobuf rules the Android TV Remote protocol needs: varints, strings, bytes, nested messages.
/// On the wire, each message has its length (a varint) in front.
final class Proto {

    private(set) var bytes: [UInt8] = []

    @discardableResult
    func varint(_ field: Int, _ value: UInt64) -> Proto {
        raw(UInt64(field << 3))
        raw(value)
        return self
    }

    @discardableResult
    func varint(_ field: Int, _ value: Int) -> Proto {
        varint(field, UInt64(bitPattern: Int64(value)))
    }

    @discardableResult
    func bytes(_ field: Int, _ value: [UInt8]) -> Proto {
        raw(UInt64(field << 3 | 2))
        raw(UInt64(value.count))
        bytes.append(contentsOf: value)
        return self
    }

    @discardableResult
    func string(_ field: Int, _ value: String) -> Proto {
        bytes(field, Array(value.utf8))
    }

    @discardableResult
    func message(_ field: Int, _ value: Proto) -> Proto {
        bytes(field, value.bytes)
    }

    /// Length + body, ready to write to the socket.
    func frame() -> [UInt8] {
        let f = Proto()
        f.raw(UInt64(bytes.count))
        return f.bytes + bytes
    }

    private func raw(_ value: UInt64) {
        var v = value
        while v & ~0x7F != 0 {
            bytes.append(UInt8(v & 0x7F) | 0x80)
            v >>= 7
        }
        bytes.append(UInt8(v))
    }

    // MARK: - reading

    enum Value {
        case num(UInt64)
        case data([UInt8])
    }

    struct Bad: Error, CustomStringConvertible {
        let description: String
    }

    /// Field number -> number (varint, fixed) or bytes (length-delimited). A repeated field keeps its last value.
    static func parse(_ b: [UInt8]) throws -> [Int: Value] {
        var fields: [Int: Value] = [:]
        var pos = 0
        while pos < b.count {
            let tag = try varint(b, &pos)
            let field = Int(tag >> 3)
            switch tag & 7 {
            case 0:
                fields[field] = .num(try varint(b, &pos))
            case 1:
                fields[field] = .num(try fixed(b, &pos, 8))
            case 2:
                let n = try varint(b, &pos)
                guard n <= UInt64(b.count - pos) else { throw Bad(description: "bad length") }
                fields[field] = .data(Array(b[pos..<pos + Int(n)]))
                pos += Int(n)
            case 5:
                fields[field] = .num(try fixed(b, &pos, 4))
            default:
                throw Bad(description: "bad wire type")
            }
        }
        return fields
    }

    static func num(_ m: [Int: Value], _ field: Int) -> UInt64 {
        if case .num(let v)? = m[field] { return v }
        return 0
    }

    static func sub(_ m: [Int: Value], _ field: Int) throws -> [Int: Value] {
        if case .data(let d)? = m[field] { return try parse(d) }
        return [:]
    }

    static func str(_ m: [Int: Value], _ field: Int) -> String {
        if case .data(let d)? = m[field] { return String(decoding: d, as: UTF8.self) }
        return ""
    }

    /// The length in front of a framed message, as read byte by byte. Nil while more bytes are needed.
    static func frameLength(_ prefix: [UInt8]) throws -> Int? {
        var n: UInt64 = 0
        for (i, c) in prefix.enumerated() {
            n |= UInt64(c & 0x7F) << (7 * UInt64(i))
            if c & 0x80 == 0 {
                guard n <= 1 << 20 else { throw Bad(description: "message too long") }
                return Int(n)
            }
            if i >= 4 { throw Bad(description: "bad length") }
        }
        return nil
    }

    private static func varint(_ b: [UInt8], _ pos: inout Int) throws -> UInt64 {
        var v: UInt64 = 0
        var shift: UInt64 = 0
        while shift < 64 {
            guard pos < b.count else { throw Bad(description: "truncated") }
            let c = b[pos]
            pos += 1
            v |= UInt64(c & 0x7F) << shift
            if c & 0x80 == 0 { return v }
            shift += 7
        }
        throw Bad(description: "bad varint")
    }

    private static func fixed(_ b: [UInt8], _ pos: inout Int, _ n: Int) throws -> UInt64 {
        guard pos + n <= b.count else { throw Bad(description: "truncated") }
        var v: UInt64 = 0
        for i in 0..<n { v |= UInt64(b[pos + i]) << (8 * UInt64(i)) }
        pos += n
        return v
    }
}
