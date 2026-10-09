import SwiftUI

/// Settings in grouped cards, like the phone's own settings. Each change applies at once.
struct SettingsView: View {
    let model: RemoteModel
    /// Goes up after each change, so the rows read the stored values again.
    @State private var tick = 0
    @State private var confirmReset = false

    var body: some View {
        let _ = tick
        List {
            Section("TV and box (Wi-Fi)") {
                NavigationLink { SetupView(model: model) } label: {
                    HStack {
                        RowText(title: "TV and box", detail: "An LG TV, an Android TV or Kodi box, or both")
                        Spacer()
                        Text([Tuning.tv.isEmpty ? nil : "TV", Tuning.host.isEmpty ? nil : "box"].compactMap { $0 }
                            .joined(separator: " + ").ifEmpty("Not set"))
                            .foregroundStyle(Theme.accent)
                    }
                }
                .cardRow()
                if !Tuning.tv.isEmpty && !Tuning.host.isEmpty {
                    choice("Volume and mute go to", Tuning.volumeTo,
                           ["TV", "Box", "TV, or the box when the TV link is down"])
                }
            }

            Section("Thumb menu") {
                toggle("Thumb menu", "Hold still, then slide or lift", Tuning.menuOn)
                slider("Hold time", "menuArmMs", 300...1500, 50) { String(format: "%.2f s", $0 / 1000) }
                slider("Slide distance", "menuSelectMm", 6...20, 1) { "\(Int($0.rounded())) mm" }
                ForEach(0..<5, id: \.self) { slot in
                    menuChoice(["Slide up", "Slide right", "Slide down", "Slide left", "Lift"][slot], slot)
                }
            }

            Section("Arrows") {
                toggleField("One swipe = one step", "Off: one step per distance, like a jog wheel", "flick")
                if Tuning.field("flick") != 0 {
                    slider("Step at", "flickMm", 4...20, 1) { "\(Int($0.rounded())) mm" }
                    slider("Long drag adds steps from", "flickMoreMm", 15...60, 1) { "\(Int($0.rounded())) mm" }
                } else {
                    slider("First step", "firstStepMm", 6...25, 1) { "\(Int($0.rounded())) mm" }
                }
                slider("Each next step", "stepMm", 4...20, 1) { "\(Int($0.rounded())) mm" }
                toggleField("Drag back steps back", nil, "reverseSteps")
                slider("Step back needs", "reverseMm", 0...30, 1) { "\(Int($0.rounded())) mm" }
                toggle("Repeat while holding", nil, Tuning.repeatOn)
                slider("Repeat starts after", "repeatDelayMs", 200...1500, 50) { String(format: "%.2f s", $0 / 1000) }
                slider("Repeat speed", "repeatIntervalMs", 60...500, 10) { "every \(Int($0.rounded())) ms" }
            }

            Section("Tap") {
                slider("Longest tap", "tapMaxMs", 120...400, 10) { "\(Int($0.rounded())) ms" }
                slider("Tap may move", "tapMaxMm", 1.5...8, 0.5) { String(format: "%.1f mm", $0) }
            }

            Section("Seek knob") {
                toggleField("Knob", "Hold, then twist the wrist", "knob")
                slider("Twist per notch", "knobStepDeg", 8...40, 1) { "\(Int($0.rounded()))°" }
                choice("Twist like a", Tuning.knobAxis, ["Doorknob", "Steering wheel"])
                choice("Direction", Tuning.knobDir, ["Reversed", "Normal"])
                choice("Sends", Tuning.knobKeys, ["Left / right", "Rewind / forward", "Volume"])
            }

            Section("Gestures") {
                toggle("Edge swipe = Back", "Swipe in from the left or right edge", Tuning.edgeBack)
                toggle("Two-finger gestures", nil, Tuning.twoFinger)
                choice("Two-finger tap", Tuning.twoTap, ["Play/Pause", "Back"])
            }

            Section("Vibration") {
                toggle("Vibration", nil, Tuning.hapticsOn)
                appSlider("Strength", Tuning.hapticStrength, 10...150, 10) { "\($0) %" }
                NavigationLink { VibrationSheet(model: model) } label: { RowText(title: "Feel each vibration") }
                    .cardRow()
            }

            Section("Screen") {
                appSlider("Brightness", Tuning.brightness, 1...100, 1) { "\($0) %" }
                appSlider("Keep on for", Tuning.keepOnMin, 1...30, 1) { "\($0) min" }
                toggle("Animations", nil, Tuning.animations)
                toggle("Hint line", nil, Tuning.hints)
                toggle("Status line", nil, Tuning.statusLine)
                toggle("Action names", nil, Tuning.showLabels)
                toggle("Pocket guard", "Screen off when the top sensor is covered", Tuning.pocket)
                toggle("Turn with gravity", "Off is better on a couch", Tuning.gravity)
            }

            Section {
                NavigationLink { AdvancedView(model: model) } label: { RowText(title: "Advanced values") }
                    .cardRow()
                Button { confirmReset = true } label: {
                    RowText(title: "Reset to defaults", color: Theme.danger)
                }
                .cardRow()
            }
        }
        .cards()
        .navigationTitle("Settings")
        .confirmationDialog("Reset all settings?", isPresented: $confirmReset, titleVisibility: .visible) {
            Button("Reset", role: .destructive) {
                Tuning.resetAll()
                changed()
            }
        }
    }

    private func changed() {
        tick += 1
        model.applySettings()
    }

    // MARK: - rows

    private func toggle(_ title: String, _ detail: String?, _ key: String) -> some View {
        Toggle(isOn: Binding(get: { _ = tick; return Tuning.app(key) == 1 },
                             set: { Tuning.put(key, $0 ? 1 : 0); changed() })) {
            RowText(title: title, detail: detail)
        }
        .cardRow()
    }

    /// A switch for a boolean GestureConfig field.
    private func toggleField(_ title: String, _ detail: String?, _ field: String) -> some View {
        Toggle(isOn: Binding(get: { _ = tick; return Tuning.field(field) != 0 },
                             set: { Tuning.setField(field, $0 ? 1 : 0); changed() })) {
            RowText(title: title, detail: detail)
        }
        .cardRow()
    }

    /// A slider for a GestureConfig field.
    private func slider(_ title: String, _ field: String, _ range: ClosedRange<Double>, _ step: Double,
                        _ format: @escaping (Double) -> String) -> some View {
        let value = Binding(get: { _ = tick; return min(range.upperBound, max(range.lowerBound, Double(Tuning.field(field)))) },
                            set: { Tuning.setField(field, Float($0)); changed() })
        return VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title).foregroundStyle(Theme.text)
                Spacer()
                Text(format(Double(Tuning.field(field)))).foregroundStyle(Theme.accent)
            }
            Slider(value: value, in: range, step: step)
        }
        .padding(.vertical, 6)
        .cardRow()
    }

    /// A slider for an app setting (int).
    private func appSlider(_ title: String, _ key: String, _ range: ClosedRange<Double>, _ step: Double,
                           _ format: @escaping (Int) -> String) -> some View {
        let value = Binding(get: { _ = tick; return Double(Tuning.app(key)) },
                            set: { Tuning.put(key, Int($0.rounded())); changed() })
        return VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title).foregroundStyle(Theme.text)
                Spacer()
                Text(format(Tuning.app(key))).foregroundStyle(Theme.accent)
            }
            Slider(value: value, in: range, step: step)
        }
        .padding(.vertical, 6)
        .cardRow()
    }

    private func choice(_ title: String, _ key: String, _ options: [String]) -> some View {
        Picker(selection: Binding(get: { _ = tick; return max(0, min(options.count - 1, Tuning.app(key))) },
                                  set: { Tuning.put(key, $0); changed() })) {
            ForEach(options.indices, id: \.self) { Text(options[$0]).tag($0) }
        } label: {
            Text(title).foregroundStyle(Theme.text)
        }
        .cardRow()
    }

    private func menuChoice(_ title: String, _ slot: Int) -> some View {
        Picker(selection: Binding(get: { _ = tick; return Tuning.menuAction(slot) },
                                  set: { Tuning.setMenuAction(slot, $0); changed() })) {
            ForEach(Tuning.menuChoices.indices, id: \.self) { i in
                Text(Tuning.menuChoiceNames[i]).tag(Tuning.menuChoices[i])
            }
        } label: {
            Text(title).foregroundStyle(Theme.text)
        }
        .cardRow()
    }
}

/// Every gesture value with its default, for fine tuning.
struct AdvancedView: View {
    let model: RemoteModel
    @State private var tick = 0

    var body: some View {
        let _ = tick
        let defaults = Tuning.appDefaultConfig()
        var current = Tuning.load()
        let _ = Tuning.appDefaults(&current, saved: true)
        List {
            Section {
                ForEach(GestureConfig.fields.filter { $0.name != "flick" }, id: \.name) { field in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(field.name).font(.system(size: 15, design: .monospaced)).foregroundStyle(Theme.text)
                            Text("default " + Self.show(defaults, field)).font(.system(size: 12)).foregroundStyle(Theme.subtext)
                        }
                        Spacer()
                        Text(Self.show(current, field)).foregroundStyle(Theme.accent)
                        Stepper("", onIncrement: { step(field, current, +1) }, onDecrement: { step(field, current, -1) })
                            .labelsHidden()
                    }
                    .cardRow()
                }
            } footer: {
                Text("Distances in mm, times in ms.").foregroundStyle(Theme.subtext)
            }
            Section {
                Button {
                    Tuning.resetGestures()
                    tick += 1
                    model.applySettings()
                } label: { RowText(title: "Reset gestures to defaults", color: Theme.danger) }
                    .cardRow()
            }
        }
        .cards()
        .navigationTitle("Advanced values")
    }

    private func step(_ field: (name: String, ref: GestureConfig.Ref), _ current: GestureConfig, _ delta: Float) {
        let v = current.value(field.name)
        switch field.ref {
        case .float: Tuning.setField(field.name, max(0, v + delta * (abs(v) < 5 && v != 0 ? 0.5 : 1)))
        case .long: Tuning.setField(field.name, max(0, v + delta * 25))
        case .bool: Tuning.setField(field.name, v == 0 ? 1 : 0)
        }
        tick += 1
        model.applySettings()
    }

    private static func show(_ c: GestureConfig, _ field: (name: String, ref: GestureConfig.Ref)) -> String {
        switch field.ref {
        case .float(let k): return String(format: "%.1f", c[keyPath: k])
        case .long(let k): return String(c[keyPath: k])
        case .bool(let k): return c[keyPath: k] ? "on" : "off"
        }
    }
}

/// One button per vibration, to learn them.
struct VibrationSheet: View {
    let model: RemoteModel

    private let tests: [(String, Haptics.Pattern)] = [
        ("Step ↑↓", .tickV), ("Step ←→", .tickH), ("OK", .click), ("Back", .back), ("Menu", .menu), ("Home", .home),
        ("Play/Pause", .play), ("Input", .input), ("Settings", .settings), ("Mute", .mute), ("Connected", .ready),
        ("No link", .noLink), ("Not sent", .error), ("Link lost", .lost),
    ]

    var body: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                ForEach(tests.indices, id: \.self) { i in
                    Button {
                        model.haptics.strength = Float(Tuning.app(Tuning.hapticStrength)) / 100
                        model.haptics.play(tests[i].1)
                    } label: {
                        Text(tests[i].0)
                            .font(.system(size: 15))
                            .foregroundStyle(Theme.text)
                            .frame(maxWidth: .infinity, minHeight: 52)
                            .background(Theme.pill, in: RoundedRectangle(cornerRadius: 14))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(16)
        }
        .background(Theme.background)
        .navigationTitle("Feel each vibration")
        .onDisappear(perform: model.applySettings)
    }
}

extension String {
    func ifEmpty(_ other: String) -> String {
        isEmpty ? other : self
    }
}
