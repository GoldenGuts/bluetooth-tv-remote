import Foundation
import Network
import Security

enum LinkError: Error {
    case closed
    case timeout
    case localNetworkDenied
    case message(String)
}

/**
 One TCP or TLS connection to the box, with buffered reads. All callbacks run on the main queue.
 TLS: the phone shows its own certificate (the box remembers it at pairing). The box certificate is
 self-signed: no CA can check it, the pin from the pairing does.
 */
final class Conn {

    struct TLS {
        let identity: SecIdentity
        /// The box certificate hash from the pairing. Nil: any certificate (pairing: the code then proves the box).
        let pin: String?
    }

    /// What the TLS checks saw. A class: the TLS blocks keep it before the connection exists.
    private final class Peer {
        var cert: SecCertificate?
        var pinMismatch = false
    }

    private let nw: NWConnection
    private let peerState = Peer()
    private var buf: [UInt8] = []
    private var pos = 0
    private var opening: CheckedContinuation<Void, Error>?
    private var reading: CheckedContinuation<Data, Error>?
    private var closed = false
    private var timedOut = false
    private var lastActivity = Date()
    private var watchdog: Task<Void, Never>?

    /// The box certificate seen in the TLS handshake.
    var peer: SecCertificate? { peerState.cert }

    init(host: String, port: UInt16, tls: TLS?) {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.connectionTimeout = 4
        let params: NWParameters
        if let tls {
            let o = NWProtocolTLS.Options()
            Self.configure(o.securityProtocolOptions, tls, peerState)
            params = NWParameters(tls: o, tcp: tcp)
        } else {
            params = NWParameters(tls: nil, tcp: tcp)
        }
        if let ip = params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options {
            ip.version = .v4
        }
        nw = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port) ?? 1, using: params)
    }

    /// A WebSocket (ws or wss) to an LG TV: text messages. The TV's certificate is self-signed and changes with
    /// its firmware, so any certificate is taken: the TV's client key, given at the prompt, is the trust.
    init(webSocket url: URL) {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.connectionTimeout = 4
        var tls: NWProtocolTLS.Options?
        if url.scheme == "wss" {
            let o = NWProtocolTLS.Options()
            sec_protocol_options_set_verify_block(o.securityProtocolOptions, { _, _, complete in complete(true) }, .main)
            tls = o
        }
        let params = NWParameters(tls: tls, tcp: tcp)
        let ws = NWProtocolWebSocket.Options()
        ws.autoReplyPing = true
        ws.maximumMessageSize = 4 << 20
        params.defaultProtocolStack.applicationProtocols.insert(ws, at: 0)
        if let ip = params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options {
            ip.version = .v4
        }
        nw = NWConnection(to: .url(url), using: params)
    }

    private static func configure(_ sec: sec_protocol_options_t, _ tls: TLS, _ peer: Peer) {
        sec_protocol_options_set_min_tls_protocol_version(sec, .TLSv12)
        nonisolated(unsafe) let identity = sec_identity_create(tls.identity)
        if let identity {
            sec_protocol_options_set_local_identity(sec, identity)
            sec_protocol_options_set_challenge_block(sec, { _, complete in complete(identity) }, .main)
        }
        let pin = tls.pin
        sec_protocol_options_set_verify_block(sec, { _, trust, complete in
            let ref = sec_trust_copy_ref(trust).takeRetainedValue()
            let chain = SecTrustCopyCertificateChain(ref) as? [SecCertificate] ?? []
            let ok = MainActor.assumeIsolated { () -> Bool in
                guard let first = chain.first else { return false }
                peer.cert = first
                guard let pin else { return true }
                peer.pinMismatch = BoxIdentity.hash(first) != pin
                return !peer.pinMismatch
            }
            complete(ok)
        }, .main)
    }

    // MARK: - open and close

    /// Connects (TCP, then TLS if set). Throws on refusal, timeout or a TLS failure.
    func open(timeout: Double) async throws {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            opening = c
            nw.stateUpdateHandler = { [weak self] state in
                MainActor.assumeIsolated { self?.changed(state) }
            }
            nw.start(queue: .main)
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(timeout))
                guard let self, self.opening != nil else { return }
                self.timedOut = true
                self.finishOpen(LinkError.timeout)
            }
        }
        lastActivity = Date()
    }

    private func changed(_ state: NWConnection.State) {
        switch state {
        case .ready:
            finishOpen(nil)
        case .waiting(let e):
            // Refused, unreachable, or no Local Network access: no point in waiting for the network to change
            finishOpen(mapped(e))
        case .failed(let e):
            finishOpen(mapped(e))
            fail(mapped(e))
        case .cancelled:
            finishOpen(LinkError.closed)
            fail(LinkError.closed)
        default:
            break
        }
    }

    private func finishOpen(_ error: Error?) {
        guard let c = opening else { return }
        opening = nil
        if let error {
            close()
            c.resume(throwing: error)
        } else {
            c.resume()
        }
    }

    func close() {
        watchdog?.cancel()
        watchdog = nil
        if !closed {
            closed = true
            nw.cancel()
        }
        fail(timedOut ? LinkError.timeout : LinkError.closed)
    }

    private func fail(_ error: Error) {
        closed = true
        if let r = reading {
            reading = nil
            r.resume(throwing: error)
        }
    }

    /// No bytes from the box for this long: the link is dead without a sign. Nil: wait forever.
    func idleTimeout(_ seconds: Double?) {
        watchdog?.cancel()
        watchdog = nil
        guard let seconds else { return }
        lastActivity = Date()
        watchdog = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(500))
                guard let self, !Task.isCancelled else { return }
                if Date().timeIntervalSince(self.lastActivity) > seconds {
                    self.timedOut = true
                    self.close()
                    return
                }
            }
        }
    }

    // MARK: - reading

    func read(_ n: Int) async throws -> [UInt8] {
        while buf.count - pos < n { try await fill() }
        let out = Array(buf[pos..<pos + n])
        pos += n
        compact()
        return out
    }

    func readByte() async throws -> UInt8 {
        if pos >= buf.count { try await fill() }
        let b = buf[pos]
        pos += 1
        compact()
        return b
    }

    func peekByte() async throws -> UInt8 {
        if pos >= buf.count { try await fill() }
        return buf[pos]
    }

    func readUInt16() async throws -> Int {
        let b = try await read(2)
        return Int(b[0]) << 8 | Int(b[1])
    }

    func readUInt32() async throws -> Int {
        let b = try await read(4)
        return Int(b[0]) << 24 | Int(b[1]) << 16 | Int(b[2]) << 8 | Int(b[3])
    }

    /// One framed protobuf message: a varint length, then the body.
    func readFrame() async throws -> [UInt8] {
        var prefix: [UInt8] = []
        while true {
            prefix.append(try await readByte())
            if let n = try Proto.frameLength(prefix) { return try await read(n) }
        }
    }

    /// One JSON object from the stream: count braces outside strings. Bytes before the first "{" are skipped.
    func readJsonObject() async throws -> [UInt8] {
        var out: [UInt8] = []
        var depth = 0
        var inString = false, escape = false
        while true {
            let c = try await readByte()
            if depth == 0 && c != UInt8(ascii: "{") { continue }
            out.append(c)
            if out.count > 1 << 20 { throw LinkError.message("message too long") }
            if inString {
                if escape { escape = false }
                else if c == UInt8(ascii: "\\") { escape = true }
                else if c == UInt8(ascii: "\"") { inString = false }
            } else if c == UInt8(ascii: "\"") {
                inString = true
            } else if c == UInt8(ascii: "{") {
                depth += 1
            } else if c == UInt8(ascii: "}") {
                depth -= 1
                if depth == 0 { return out }
            }
        }
    }

    // MARK: - WebSocket messages

    /// The next text message.
    func readMessage() async throws -> String {
        if closed { throw timedOut ? LinkError.timeout : LinkError.closed }
        while true {
            let data: Data = try await withCheckedThrowingContinuation { c in
                reading = c
                nw.receiveMessage { [weak self] data, _, _, error in
                    MainActor.assumeIsolated {
                        guard let self, let r = self.reading else { return }
                        self.reading = nil
                        if let error {
                            r.resume(throwing: self.mapped(error))
                        } else {
                            self.lastActivity = Date()
                            r.resume(returning: data ?? Data())
                        }
                    }
                }
            }
            if !data.isEmpty { return String(decoding: data, as: UTF8.self) }
            if closed { throw LinkError.closed }
        }
    }

    /// Sends one text message, in call order.
    func sendText(_ text: String, done: (@MainActor @Sendable (Bool) -> Void)? = nil) {
        if closed {
            if let done { Task { done(false) } }
            return
        }
        let meta = NWProtocolWebSocket.Metadata(opcode: .text)
        let context = NWConnection.ContentContext(identifier: "text", metadata: [meta])
        nw.send(content: Data(text.utf8), contentContext: context, isComplete: true, completion: .contentProcessed { error in
            MainActor.assumeIsolated { done?(error == nil) }
        })
    }

    private func fill() async throws {
        if closed { throw timedOut ? LinkError.timeout : LinkError.closed }
        let data: Data = try await withCheckedThrowingContinuation { c in
            reading = c
            nw.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, complete, error in
                MainActor.assumeIsolated {
                    guard let self, let r = self.reading else { return }
                    self.reading = nil
                    if let data, !data.isEmpty {
                        self.lastActivity = Date()
                        r.resume(returning: data)
                    } else if let error {
                        r.resume(throwing: self.mapped(error))
                    } else {
                        r.resume(throwing: LinkError.closed)   // complete: the box closed the link
                    }
                }
            }
        }
        buf.append(contentsOf: data)
    }

    private func compact() {
        if pos > 4096 && pos * 2 > buf.count {
            buf.removeFirst(pos)
            pos = 0
        }
    }

    // MARK: - writing

    /// Sends in call order. done gets false if the link is down or the send failed.
    func send(_ bytes: [UInt8], done: (@MainActor @Sendable (Bool) -> Void)? = nil) {
        if closed {
            if let done { Task { done(false) } }
            return
        }
        nw.send(content: Data(bytes), completion: .contentProcessed { error in
            MainActor.assumeIsolated { done?(error == nil) }
        })
    }

    // MARK: - errors

    private func mapped(_ error: Error) -> Error {
        if timedOut { return LinkError.timeout }
        if nw.currentPath?.unsatisfiedReason == .localNetworkDenied { return LinkError.localNetworkDenied }
        if peerState.pinMismatch { return LinkError.message("the box certificate changed: pair again") }
        return error
    }

    /// A short reason for the status line.
    static func reason(_ error: Error) -> String {
        switch error {
        case LinkError.timeout: return "the box does not answer"
        case LinkError.closed: return "the box closed the link"
        case LinkError.localNetworkDenied: return "allow Local Network for TV Remote in iOS Settings"
        case LinkError.message(let m): return m
        case let e as NWError:
            switch e {
            case .posix(let code):
                switch code {
                case .ECONNREFUSED: return "the box refused the link (remote service off?)"
                case .ECONNRESET, .EPIPE, .ENOTCONN: return "the box closed the link"
                case .ENETDOWN, .ENETUNREACH: return "no Wi-Fi"
                default: return "the box does not answer"
                }
            case .dns: return "unknown address"
            case .tls: return "the box refused this phone: pair again"
            default: return e.localizedDescription
            }
        default:
            return error.localizedDescription
        }
    }

    static func refused(_ error: Error) -> Bool {
        if case NWError.posix(.ECONNREFUSED) = error { return true }
        return false
    }

    /// Something listens on the port. Gives up after `timeout` seconds.
    static func probe(_ host: String, _ port: UInt16, timeout: Double = 1.5) async -> Bool {
        let c = Conn(host: host, port: port, tls: nil)
        do {
            try await c.open(timeout: timeout)
            c.close()
            return true
        } catch {
            return false
        }
    }
}
