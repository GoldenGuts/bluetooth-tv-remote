import Foundation
import Network
import Observation

/**
 Finds LG TVs and Android TV and Kodi boxes on the Wi-Fi (Bonjour). New in the iPhone app: the Android app needs the
 address. Android TV announces its remote service; Kodi announces JSON-RPC when its zeroconf setting is on; an LG
 TV announces AirPlay, with "LG" as the manufacturer.
 */
@Observable
final class Discovery {

    struct Found: Identifiable, Hashable {
        let name: String
        let kind: String
        let endpoint: NWEndpoint
        var id: String { kind + "|" + name }
        var isTV: Bool { kind == Discovery.lgTV }
    }

    static let lgTV = "LG TV"

    private(set) var found: [Found] = []
    private var browsers: [NWBrowser] = []
    private var byType: [String: [Found]] = [:]

    private static let types: [(type: String, kind: String)] = [
        ("_androidtvremote2._tcp", "Android TV"),
        ("_androidtvremote._tcp", "Android TV"),
        ("_xbmc-jsonrpc._tcp", "Kodi"),
        ("_xbmc-jsonrpc-h._tcp", "Kodi"),
        ("_airplay._tcp", lgTV),
    ]

    func start() {
        guard browsers.isEmpty else { return }
        for (type, kind) in Self.types {
            let b = NWBrowser(for: .bonjourWithTXTRecord(type: type, domain: nil), using: .tcp)
            b.browseResultsChangedHandler = { [weak self] results, _ in
                MainActor.assumeIsolated { self?.update(type, kind, results) }
            }
            b.start(queue: .main)
            browsers.append(b)
        }
    }

    func stop() {
        browsers.forEach { $0.cancel() }
        browsers.removeAll()
        byType.removeAll()
        found.removeAll()
    }

    private func update(_ type: String, _ kind: String, _ results: Set<NWBrowser.Result>) {
        byType[type] = results.compactMap { r in
            guard case .service(let name, _, _, _) = r.endpoint else { return nil }
            if kind == Self.lgTV {
                // AirPlay: only LG TVs (other AirPlay devices are not TVs this app controls)
                var maker = ""
                if case .bonjour(let txt) = r.metadata { maker = txt["manufacturer"] ?? "" }
                guard maker.localizedCaseInsensitiveContains("LG") || name.hasPrefix("[LG]")
                        || name.localizedCaseInsensitiveContains("webOS") else { return nil }
            }
            return Found(name: name, kind: kind, endpoint: r.endpoint)
        }
        var seen = Set<String>()
        found = byType.values.flatMap { $0 }
            .filter { seen.insert($0.id).inserted }
            .sorted { ($0.kind, $0.name) < ($1.kind, $1.name) }
    }

    /// The IPv4 address of a found box: a short TCP connection to its service tells it.
    static func resolve(_ f: Found) async -> String? {
        let params = NWParameters.tcp
        if let ip = params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options { ip.version = .v4 }
        let c = NWConnection(to: f.endpoint, using: params)
        return await withCheckedContinuation { (k: CheckedContinuation<String?, Never>) in
            let once = Once(c, k)
            c.stateUpdateHandler = { state in
                MainActor.assumeIsolated {
                    switch state {
                    case .ready:
                        if case .hostPort(let host, _)? = c.currentPath?.remoteEndpoint {
                            var s = "\(host)"
                            if let percent = s.firstIndex(of: "%") { s = String(s[..<percent]) }
                            once.finish(s)
                        } else {
                            once.finish(nil)
                        }
                    case .failed, .cancelled:
                        once.finish(nil)
                    default:
                        break
                    }
                }
            }
            c.start(queue: .main)
            Task {
                try? await Task.sleep(for: .seconds(4))
                once.finish(nil)
            }
        }
    }

    /// Gives the first answer of a resolve, then closes its connection.
    private final class Once {
        private let c: NWConnection
        private var k: CheckedContinuation<String?, Never>?

        init(_ c: NWConnection, _ k: CheckedContinuation<String?, Never>) {
            self.c = c
            self.k = k
        }

        func finish(_ host: String?) {
            guard let k else { return }
            self.k = nil
            c.cancel()
            k.resume(returning: host)
        }
    }
}
