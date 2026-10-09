import SwiftUI

/// One page with every gesture and vibration.
struct HelpView: View {
    let model: RemoteModel
    let tutorial: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let menu = (0..<5).map(Tuning.menuName)
        let hold = Double(Tuning.gestureConfig().menuArmMs) / 1000
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("TV Remote").font(.system(size: 26, weight: .bold)).foregroundStyle(.white)
                para("Use it without a look. The gesture starts anywhere on the black area.")
                Button(action: tutorial) {
                    Text("Start the tutorial (learn by doing)")
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(Theme.pill, in: RoundedRectangle(cornerRadius: 14))
                        .foregroundStyle(Theme.text)
                }
                .padding(.top, 12)

                heading("One thumb")
                item("Tap", "OK")
                item("Swipe ↑ ↓ ← →", "Arrows. One swipe = one step. A long drag (3 cm or more) adds steps.")
                item("Drag, then stop", "Repeats the arrow while you hold")
                item("Swipe in from the edge", "Back")

                heading(String(format: "Thumb menu: hold still %.1f s", hold))
                para("A ring fills around your thumb, then a thud. Without lifting:")
                MenuDiagram(menu: menu).frame(height: 210)
                item("Twist the wrist", "Seek knob: one notch per 15°. The lift then does nothing.")

                heading("Two fingers (optional)")
                item("Two-finger tap", "Back (or Play/Pause: Settings → Gestures)")
                item("Two-finger drag ← →", "Rewind / forward")
                item("Two-finger drag ↑ / ↓", "Input list / Settings")
                item("Two-finger hold", "Mute")

                heading("Volume")
                item("••• → Volume", "− Mute +")
                item("Thumb menu", "Put Volume up or down on a slot in Settings. Slide down repeats")
                item("Seek knob", "Settings → Seek knob → Sends → Volume")
                para("An app cannot take the iPhone volume buttons. A soundbar on HDMI-CEC follows the TV or the box.")

                heading("LG TV, Android TV or Kodi box (Wi-Fi)")
                item("Settings → TV and box", "Find them on your Wi-Fi, or type an address. LG TV: accept the prompt "
                     + "on the TV once. Android TV: type the code once")
                item("••• → Send keys to", "With a TV and a box: keys go to one. Input and power stay on the TV")
                item("TV ↔ box", "Switch without a look: put it on a thumb-menu slot in Settings")
                item("••• → Touchpad", "Kodi or LG TV: a mouse pointer. Slide, tap, hold, two fingers")
                item("••• → Type on the TV", "Letters appear in the TV search box as you type. Voice works too")
                item("Power on an LG TV", "Turn on \"Turn on via Wi-Fi\" in the TV's settings, under General → Devices")

                heading("Vibrations")
                item("Rising buzz on open", "Connected to the TV or box")
                item("Two low buzzes on open", "NOT connected")
                item("One long low buzz", "The key did NOT go out")
                item("Three low buzzes", "Connection lost")
                item("Light tick", "Arrow step (up/down sharper, left/right lower)")

                heading("Screen")
                item("Dot at the top", "Green = connected, orange = connecting, red = not connected, gray = off or nothing set up")
                item("••• at the top right", "Extras: text, voice, volume, practice, settings")
                item("Practice", "Gestures without keys, with big labels")

                Button { dismiss() } label: {
                    Text("Got it")
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(Theme.pill, in: RoundedRectangle(cornerRadius: 14))
                        .foregroundStyle(Theme.text)
                }
                .padding(.top, 24)
            }
            .padding(20)
        }
        .background(Color.black)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func heading(_ s: String) -> some View {
        Text(s).font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.accent)
            .padding(.top, 22).padding(.bottom, 6)
    }

    private func para(_ s: String) -> some View {
        Text(s).font(.system(size: 15)).foregroundStyle(Color(white: 0.8)).padding(.vertical, 4)
    }

    private func item(_ gesture: String, _ result: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text(gesture).foregroundStyle(.white).frame(width: 140, alignment: .leading)
            Text(result).foregroundStyle(.gray).frame(maxWidth: .infinity, alignment: .leading)
        }
        .font(.system(size: 15))
        .padding(.vertical, 6)
    }
}

/// The cross around the thumb, as it looks when the menu opens.
struct MenuDiagram: View {
    let menu: [String]

    var body: some View {
        Canvas { c, size in
            let cx = size.width / 2, cy = size.height / 2, r: CGFloat = 70
            let gray = Color(red: 220 / 255, green: 220 / 255, blue: 225 / 255)
            c.stroke(Path(ellipseIn: circle(cx, cy, 40)), with: .color(gray.opacity(90 / 255)), lineWidth: 1.5)
            c.stroke(Path(ellipseIn: circle(cx, cy, 14)), with: .color(Theme.accent), lineWidth: 2.5)
            func label(_ s: String, _ x: CGFloat, _ y: CGFloat, _ anchor: UnitPoint, _ color: Color, _ size: CGFloat = 14) {
                c.draw(Text(s).font(.system(size: size)).foregroundStyle(color), at: CGPoint(x: x, y: y), anchor: anchor)
            }
            let text = gray.opacity(220 / 255)
            label("↑ " + menu[0], cx, cy - r, .center, text)
            label("↓ " + menu[2], cx, cy + r + 5, .center, text)
            label("← " + menu[3], cx - 50, cy, .trailing, text)
            label(menu[1] + " →", cx + 50, cy, .leading, text)
            label("lift = " + menu[4], cx, cy + 28, .center, Theme.accent, 12)
        }
    }
}
