import SwiftUI

/// The extras behind "•••": typing, volume, box keys, learning, settings.
struct PanelView: View {
    @Bindable var model: RemoteModel
    /// Closes the panel, then runs the next step (a full-screen page cannot open over a closing sheet).
    let thenOpen: (@escaping () -> Void) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var path: [String] = []

    var body: some View {
        NavigationStack(path: $path) {
            List {
                Section {
                    HStack(alignment: .center) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("TV Remote").font(.system(size: 26)).foregroundStyle(Theme.text)
                            Text(model.panelHeader)
                                .font(.system(size: 13))
                                .foregroundStyle(model.panelConnected ? Theme.accent : Theme.subtext)
                        }
                        Spacer()
                        Button { dismiss() } label: {
                            Text("✕").font(.system(size: 15)).foregroundStyle(Theme.text)
                                .frame(width: 44, height: 44)
                                .background(Theme.pill, in: RoundedRectangle(cornerRadius: 14))
                        }
                        .buttonStyle(.plain)
                    }
                    .listRowBackground(Color.clear)
                }

                if model.hasTV && model.hasBox {
                    Section("Send keys to") {
                        Toggle(isOn: Binding(get: { model.boxIsTarget }, set: { model.switchTarget($0) })) {
                            RowText(title: "Android TV or Kodi box (Wi-Fi)",
                                    detail: "Input and power stay on the TV. Volume: see Settings")
                        }
                        .cardRow()
                    }
                }

                Section("Type on the TV") {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Select a search box on the TV first. Letters appear there as you type.")
                            .font(.system(size: 13))
                            .foregroundStyle(Theme.subtext)
                        TypingField(model: model)
                            .padding(.horizontal, 16)
                            .frame(height: 52)
                            .background(Theme.pill, in: RoundedRectangle(cornerRadius: 14))
                    }
                    .padding(.vertical, 6)
                    .cardRow()
                    Pills(items: [
                        (model.listening ? "Stop" : "Voice", model.toggleVoice),
                        ("⌫", model.backspace),
                        ("Clear", model.clearField),
                        ("Enter", model.sendEnter),
                    ])
                }

                Section("Volume") {
                    Pills(items: [
                        ("−", { model.send(Key.volumeDown, .tickV) }),
                        ("Mute", { model.send(Key.mute, .mute) }),
                        ("+", { model.send(Key.volumeUp, .tickV) }),
                    ])
                }

                Section("TV") {
                    Toggle(isOn: Binding(get: { model.touchpad }, set: { model.setTouchpad($0) })) {
                        RowText(title: "Touchpad", detail: "A mouse pointer on Kodi or the LG TV. Slide, tap, hold, two fingers")
                    }
                    .cardRow()
                    ActionRow(title: "Settings menu") { model.send(Key.settings, .settings) }
                    ActionRow(title: "Input list", detail: model.hasTV ? "The TV's inputs" : "Android TV boxes with HDMI-CEC") {
                        model.send(Key.input, .input)
                    }
                    RowText(title: "Power", detail: model.hasTV ? "Touch and hold. Turns the TV on or off"
                                : "Touch and hold. The box, and the TV with HDMI-CEC", color: Theme.danger)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                        .onTapGesture { model.label("hold to turn off") }
                        .onLongPressGesture { model.send(Key.power, .home) }
                        .cardRow()
                }

                Section("Learn") {
                    ActionRow(title: "Tutorial") { thenOpen { model.showTutorial = true } }
                    NavigationLink {
                        HelpView(model: model, tutorial: { thenOpen { model.showTutorial = true } })
                    } label: { RowText(title: "Cheat sheet") }
                        .cardRow()
                    Toggle(isOn: $model.practice) {
                        RowText(title: "Practice mode", detail: "Gestures without sending keys")
                    }
                    .cardRow()
                    ActionRow(title: "Measure thumb angle", detail: "Swipe up 10 times") { model.startCalibration() }
                }

                Section {
                    NavigationLink { SettingsView(model: model) } label: { RowText(title: "Settings") }
                        .cardRow()
                    NavigationLink { SetupView(model: model) } label: {
                        RowText(title: "TV and box", detail: devices)
                    }
                    .cardRow()
                    Link(destination: URL(string: "https://github.com/GoldenGuts/bluetooth-tv-remote")!) {
                        RowText(title: "Source code", detail: "Free and open source (GPL-3.0)")
                    }
                    .cardRow()
                }
            }
            .cards()
            .scrollDismissesKeyboard(.immediately)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: String.self) { _ in SettingsView(model: model) }

        }
        .onAppear {
            #if DEBUG
            if Shots.name == "settings" && path.isEmpty { path = ["settings"] }
            #endif
        }
        .preferredColorScheme(.dark)
        .tint(Theme.accent)
    }

    /// What is set up, for the "TV and box" row.
    private var devices: String {
        let tv = model.hasTV ? (model.tvName.isEmpty ? "LG TV" : model.tvName) : nil
        let box = model.hasBox ? "box " + Tuning.host : nil
        let both = [tv, box].compactMap { $0 }
        return both.isEmpty ? "Not set up" : both.joined(separator: " · ")
    }
}
