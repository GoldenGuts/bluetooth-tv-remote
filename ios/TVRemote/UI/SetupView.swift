import Observation
import SwiftUI
import UIKit

/**
 The steps of setting up the TV or the box: find or type the address, then an LG TV (a prompt on the TV), Kodi
 (no pairing) or Android TV (a code).
 */
@Observable
final class Setup {

    enum Phase: Equatable {
        case idle
        case checking
        case connecting
        case code(Int)
        case checkingCode
        /// An LG TV: the TV link runs; the page follows its state.
        case tv
        case done(ok: Bool, title: String, message: String)
    }

    var phase = Phase.idle
    var address = ""
    var code = ""
    var codeProblem = ""
    let discovery = Discovery()

    @ObservationIgnored private var pairing: BoxPairing?
    @ObservationIgnored private let model: RemoteModel

    init(model: RemoteModel) {
        self.model = model
    }

    /// A found device: its address, then the same steps as a typed one.
    func use(_ found: Discovery.Found) {
        phase = .checking
        Task {
            guard let host = await Discovery.resolve(found) else {
                phase = .done(ok: false, title: "Not found", message: "The device did not answer. Type its address.")
                return
            }
            address = host
            if found.isTV { useTV(host) } else { useBox(host) }
        }
    }

    /// A typed address: an LG TV answers on its remote-app ports, else it is a box.
    func connect() {
        let host = address.trimmingCharacters(in: .whitespaces)
        guard !host.isEmpty else { return }
        phase = .checking
        Task {
            if await TVLink.at(host) { useTV(host) } else { useBox(host) }
        }
    }

    /// The TV shows a prompt the first time; the TV link waits for OK there.
    private func useTV(_ host: String) {
        if host != Tuning.tv {
            Tuning.tv = host
            TVLink.forget()   // another TV: the key and the MAC address of the old one do not fit
        }
        model.boxChanged()
        phase = .tv
    }

    func pairTVAgain() {
        TVLink.forget()
        model.boxChanged()
        phase = .tv
    }

    func forgetTV() {
        Tuning.tv = ""
        TVLink.forget()
        model.boxChanged()
        phase = .idle
    }

    /// Kodi (CoreELEC) needs no pairing; Android TV does. A dual-boot box may run either.
    private func useBox(_ host: String) {
        if host != Tuning.host {
            Tuning.host = host
            model.boxChanged()
        }
        Task {
            if await BoxLink.kodiAt(host) {
                model.haptics.play(.ready)
                model.boxChanged()
                phase = .done(ok: true, title: "Kodi found", message: "The box runs Kodi: no pairing needed. Keys now go to Kodi.\n\n"
                              + "When the box runs Android TV, the remote switches to it by itself. Pair it once from here while Android runs.")
            } else {
                pairBox()
            }
        }
    }

    func pairBox() {
        let host = Tuning.host
        guard !host.isEmpty else { return }
        pairing?.cancel()
        code = ""
        codeProblem = ""
        phase = .connecting
        let p = BoxPairing(host: host, clientName: UIDevice.current.model)
        p.codeShown = { [weak self] n in self?.phase = .code(n) }
        p.done = { [weak self] ok, message in
            guard let self else { return }
            if ok {
                model.haptics.play(.ready)
                model.boxChanged()
            }
            phase = .done(ok: ok, title: ok ? "Paired" : "Pairing failed",
                          message: ok ? "Keys now go to the box, with the same gestures." : message)
        }
        pairing = p
        p.start()
    }

    func submit() {
        guard let pairing else { return }
        if pairing.submit(code) {
            codeProblem = ""
            phase = .checkingCode
        } else {
            codeProblem = "That code does not fit. Check it and type it again."
            model.haptics.play(.error)
        }
    }

    func cancel() {
        pairing?.cancel()
        pairing = nil
        phase = .idle
    }

    func forgetBox() {
        cancel()
        Tuning.host = ""
        model.boxChanged()
    }
}

/// The TV and box page: find, type, pair, forget.
struct SetupView: View {
    let model: RemoteModel
    @State private var setup: Setup?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            if let setup { content(setup) } else { Color.black }
        }
        .navigationTitle("TV and box")
        .background(Theme.background)
        .onAppear {
            if setup == nil { setup = Setup(model: model) }
            setup?.discovery.start()
        }
        .onDisappear {
            setup?.discovery.stop()
            setup?.cancel()
        }
    }

    @ViewBuilder
    private func content(_ s: Setup) -> some View {
        @Bindable var s = s
        List {
            switch s.phase {
            case .idle:
                pick(s)
            case .checking, .connecting:
                Section {
                    HStack(spacing: 12) {
                        ProgressView()
                        Text(s.phase == .checking ? "Looking at \(s.address)…" : "Connecting to \(Tuning.host)…")
                            .foregroundStyle(Theme.text)
                    }
                    .cardRow()
                    Button("Cancel", action: s.cancel).cardRow()
                }
            case .tv:
                tvProgress(s)
            case .code(let n):
                Section {
                    VStack(spacing: 14) {
                        Text("Type the \(n) characters that the TV shows.").foregroundStyle(Theme.text)
                        TextField(n == 4 ? "4D35" : "4D35A1", text: $s.code)
                            .font(.system(size: 28, weight: .medium, design: .monospaced))
                            .multilineTextAlignment(.center)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .keyboardType(.asciiCapable)
                            .submitLabel(.go)
                            .onSubmit(s.submit)
                            .onChange(of: s.code) { _, new in
                                let clean = String(new.uppercased().filter(\.isHexDigit).prefix(n))
                                if clean != new { s.code = clean }
                            }
                            .padding(12)
                            .background(Theme.pill, in: RoundedRectangle(cornerRadius: 14))
                        if !s.codeProblem.isEmpty {
                            Text(s.codeProblem).font(.system(size: 13)).foregroundStyle(Theme.danger)
                        }
                    }
                    .padding(.vertical, 8)
                    .cardRow()
                    Button("Pair", action: s.submit).disabled(s.code.count != n).cardRow()
                    Button("Cancel", action: s.cancel).cardRow()
                } header: {
                    Text("Code on the TV")
                }
            case .checkingCode:
                Section {
                    HStack(spacing: 12) {
                        ProgressView()
                        Text("Checking…").foregroundStyle(Theme.text)
                    }
                    .cardRow()
                }
            case .done(let ok, let title, let message):
                result(ok, title, message, s)
            }
        }
        .cards()
    }

    /// The LG TV link while it connects: the prompt on the TV, then connected or why not.
    @ViewBuilder
    private func tvProgress(_ s: Setup) -> some View {
        switch model.tvState {
        case .connected:
            result(true, "TV connected", "\(model.tvName.isEmpty ? "The TV" : model.tvName) takes keys from this iPhone. "
                   + (model.hasBox ? "Keys go to the box; input and power go to the TV. Switch with ••• → Send keys to."
                                   : "Keys now go to the TV."), s)
        case .waiting:
            result(false, "No link to the TV", model.tvDetail.isEmpty ? "The TV did not answer." : model.tvDetail, s)
        default:
            Section {
                HStack(spacing: 14) {
                    ProgressView()
                    VStack(alignment: .leading, spacing: 4) {
                        Text(model.tvState == .pairing ? "Press OK on the TV" : "Connecting to the TV…")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(Theme.text)
                        Text(model.tvState == .pairing
                             ? "The TV asks to allow this iPhone. Choose Accept with the TV remote."
                             : Tuning.tv)
                            .font(.system(size: 13))
                            .foregroundStyle(Theme.subtext)
                    }
                }
                .padding(.vertical, 6)
                .cardRow()
                Button("Cancel") { s.phase = .idle }.cardRow()
            }
        }
    }

    @ViewBuilder
    private func result(_ ok: Bool, _ title: String, _ message: String, _ s: Setup) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                Text(title).font(.system(size: 20, weight: .semibold)).foregroundStyle(ok ? Theme.accent : Theme.danger)
                Text(message).foregroundStyle(Theme.text)
            }
            .padding(.vertical, 6)
            .cardRow()
            if ok {
                Button("Done") { dismiss() }.cardRow()
            } else {
                Button("Try again") { s.phase = .idle }.cardRow()
            }
        }
    }

    @ViewBuilder
    private func pick(_ s: Setup) -> some View {
        @Bindable var s = s
        Section {
            if s.discovery.found.isEmpty {
                HStack(spacing: 12) {
                    ProgressView()
                    Text("Looking for TVs and boxes…").foregroundStyle(Theme.subtext)
                }
                .cardRow()
            }
            ForEach(s.discovery.found) { f in
                ActionRow(title: f.name, detail: f.kind) { s.use(f) }
            }
        } header: {
            Text("On your Wi-Fi")
        } footer: {
            Text("LG TVs, Android TV and Google TV boxes, and Kodi (when Settings → Services → Zeroconf is on).")
                .foregroundStyle(Theme.subtext)
        }

        Section {
            TextField("192.168.1.50", text: $s.address)
                .keyboardType(.numbersAndPunctuation)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.go)
                .onSubmit(s.connect)
                .foregroundStyle(Theme.text)
                .cardRow()
            Button("Connect", action: s.connect)
                .disabled(s.address.trimmingCharacters(in: .whitespaces).isEmpty)
                .cardRow()
        } header: {
            Text("Or type an address")
        } footer: {
            Text("The app finds out what answers there. LG TV: Settings → General → Network → Wi-Fi. Box: "
                 + "Settings → Network (Kodi: Settings → System information → Network). Give the TV and the box "
                 + "fixed addresses in your router.")
                .foregroundStyle(Theme.subtext)
        }

        if !Tuning.tv.isEmpty {
            Section {
                HStack {
                    RowText(title: model.tvName.isEmpty ? "LG TV" : model.tvName, detail: Tuning.tv + " · " + tvStateText)
                    Spacer()
                }
                .cardRow()
                ActionRow(title: "Pair again", detail: "The TV asks to allow this iPhone") { s.pairTVAgain() }
                Button { s.forgetTV() } label: { RowText(title: "Forget this TV", color: Theme.danger) }
                    .cardRow()
            } header: {
                Text("TV")
            } footer: {
                Text("To turn the TV on from the iPhone, turn on \"Turn on via Wi-Fi\" (or \"Mobile TV On\") in the TV's "
                     + "settings, under General → Devices.")
                    .foregroundStyle(Theme.subtext)
            }
        }

        if !Tuning.host.isEmpty {
            Section {
                HStack {
                    RowText(title: Tuning.host, detail: BoxIdentity.paired ? "Paired (Android TV)" : "Not paired: fine for Kodi")
                    Spacer()
                }
                .cardRow()
                ActionRow(title: "Pair again", detail: "Android TV: the box shows a code") { s.pairBox() }
                Button { s.forgetBox() } label: { RowText(title: "Forget this box", color: Theme.danger) }
                    .cardRow()
            } header: {
                Text("Box")
            } footer: {
                Text(Tuning.tv.isEmpty
                     ? "Input and power go to the box. With HDMI-CEC the box passes them on to the TV."
                     : "Input and power go to the TV. Volume and mute: Settings → Volume and mute go to.")
                    .foregroundStyle(Theme.subtext)
            }
        }
    }

    private var tvStateText: String {
        switch model.tvState {
        case .connected: return "connected"
        case .pairing: return "press OK on the TV"
        case .connecting: return "connecting"
        case .off: return "not connected"
        case .waiting: return model.tvDetail
        }
    }
}
