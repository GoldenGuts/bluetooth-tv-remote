import SwiftUI

/// The black gesture surface with a few quiet marks on top: help, the link dot and line, and the extras button.
struct RemoteScreen: View {
    @Bindable var model: RemoteModel
    @Environment(\.scenePhase) private var phase
    @State private var afterPanel: (() -> Void)?

    var body: some View {
        ZStack {
            Surface(model: model).ignoresSafeArea()
            VStack(spacing: 0) {
                HStack(alignment: .top) {
                    corner("?") { model.showHelp = true }
                    Spacer()
                    VStack(spacing: 6) {
                        Circle().fill(model.dotColor).frame(width: 8, height: 8).opacity(0.5)
                        if model.statusVisible {
                            Text(model.statusLine)
                                .font(.system(size: 12))
                                .foregroundStyle(.gray)
                                .opacity(0.7)
                                .multilineTextAlignment(.center)
                        }
                    }
                    .padding(.top, 12)
                    .allowsHitTesting(false)
                    Spacer()
                    corner("•••") { model.panelOpen = true }
                }
                Spacer()
                if !model.hasBox && !model.hasTV {
                    Button("Set up your TV or box") { model.showSetup = true }
                        .font(.system(size: 15))
                        .foregroundStyle(Theme.accent)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 12)
                        .background(Theme.pill, in: Capsule())
                }
                Spacer()
                Text(model.labelText)
                    .font(.system(size: model.labelBig ? 40 : 18))
                    .foregroundStyle(.white)
                    .opacity(model.labelBig ? 0.9 : 0.35)
                    .multilineTextAlignment(.center)
                    .padding(.bottom, 20)
                    .allowsHitTesting(false)
                if model.touchpad {
                    Button("exit touchpad") { model.setTouchpad(false) }
                        .font(.system(size: 14))
                        .foregroundStyle(.gray)
                        .padding(.vertical, 10)
                        .padding(.horizontal, 18)
                }
                if model.hintVisible {
                    Text(model.hint)
                        .font(.system(size: 12))
                        .foregroundStyle(.gray)
                        .multilineTextAlignment(.center)
                        .opacity(model.hintFaded ? 0 : 0.6)
                        .animation(.easeOut(duration: 0.6), value: model.hintFaded)
                        .padding(.bottom, 12)
                        .allowsHitTesting(false)
                }
            }
        }
        .background(Color.black)
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .defersSystemGestures(on: .all)
        .sheet(isPresented: $model.panelOpen, onDismiss: {
            model.panelClosed()
            afterPanel?()
            afterPanel = nil
        }) {
            PanelView(model: model) { next in
                afterPanel = next
                model.panelOpen = false
            }
        }
        .sheet(isPresented: $model.showHelp) {
            NavigationStack { HelpView(model: model, tutorial: { model.showHelp = false; model.showTutorial = true }) }
                .preferredColorScheme(.dark)
        }
        .sheet(isPresented: $model.showSetup, onDismiss: model.applySettings) {
            NavigationStack { SetupView(model: model) }
                .preferredColorScheme(.dark)
        }
        .fullScreenCover(isPresented: $model.showTutorial, onDismiss: model.applySettings) {
            TutorialView(haptics: model.haptics)
        }
        .onChange(of: phase, initial: true) { _, now in
            if now == .active { model.resume() } else if now == .background { model.pause() }
        }
    }

    private func corner(_ title: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 18))
                .foregroundStyle(.gray)
                .padding(.horizontal, 18)
                .padding(.vertical, 10)
                .contentShape(Rectangle())
        }
    }
}

/// The UIKit surface: it takes the raw touches and draws the feedback.
struct Surface: UIViewRepresentable {
    let model: RemoteModel

    func makeUIView(context: Context) -> FeedbackView {
        let v = FeedbackView(frame: .zero)
        model.attach(v)
        return v
    }

    func updateUIView(_ view: FeedbackView, context: Context) {
    }
}

/// The typing field: no suggestions or smart punctuation, so each key reaches the box as typed.
struct TypingField: UIViewRepresentable {
    let model: RemoteModel

    func makeCoordinator() -> Coordinator {
        Coordinator(model: model)
    }

    func makeUIView(context: Context) -> UITextField {
        let f = UITextField()
        f.autocorrectionType = .no
        f.spellCheckingType = .no
        f.autocapitalizationType = .none
        f.smartQuotesType = .no
        f.smartDashesType = .no
        f.smartInsertDeleteType = .no
        f.inlinePredictionType = .no
        f.returnKeyType = .send
        f.textColor = UIColor(Theme.text)
        f.font = .systemFont(ofSize: 17)
        f.attributedPlaceholder = NSAttributedString(string: "Tap here and type",
                                                     attributes: [.foregroundColor: UIColor(Theme.subtext)])
        f.delegate = context.coordinator
        f.addTarget(context.coordinator, action: #selector(Coordinator.changed(_:)), for: .editingChanged)
        f.setContentHuggingPriority(.defaultLow, for: .horizontal)
        return f
    }

    func updateUIView(_ f: UITextField, context: Context) {
        if f.text != model.fieldText { f.text = model.fieldText }
        if model.focusPending {
            DispatchQueue.main.async {
                model.focusPending = false
                f.becomeFirstResponder()
            }
        }
    }

    final class Coordinator: NSObject, UITextFieldDelegate {
        let model: RemoteModel

        init(model: RemoteModel) {
            self.model = model
        }

        @objc func changed(_ f: UITextField) {
            model.userTyped(f.text ?? "")
        }

        func textFieldShouldReturn(_ f: UITextField) -> Bool {
            model.sendEnter()
            return false
        }
    }
}
