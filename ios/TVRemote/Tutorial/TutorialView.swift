import SwiftUI

/// The tutorial page: progress, the lesson text, the practice area with the ghost thumb, and what happened.
struct TutorialView: View {
    @State private var model: TutorialModel
    @Environment(\.dismiss) private var dismiss

    init(haptics: Haptics) {
        _model = State(initialValue: TutorialModel(haptics: haptics))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(model.stepText).font(.system(size: 13)).foregroundStyle(.gray)
                    HStack(spacing: 5) {
                        ForEach(model.dots.indices, id: \.self) { i in
                            Circle().fill(model.dots[i]).frame(width: 6, height: 6)
                        }
                    }
                }
                Spacer()
                textButton("Exit") { dismiss() }
            }
            .padding(.leading, 20)

            Text(model.title)
                .font(.system(size: 26, weight: .bold))
                .foregroundStyle(.white)
                .padding(.horizontal, 20)
                .padding(.top, 10)
            Text(model.bodyText)
                .font(.system(size: 15))
                .foregroundStyle(Color(white: 0.8))
                .padding(.horizontal, 20)
                .padding(.top, 4)
                .fixedSize(horizontal: false, vertical: true)

            ZStack(alignment: .top) {
                PracticeArea(model: model)
                Text(model.bigLabel)
                    .font(.system(size: 40))
                    .foregroundStyle(.white)
                    .opacity(model.bigVisible ? 0.9 : 0)
                    .animation(.easeOut(duration: 0.4), value: model.bigVisible)
                    .padding(.top, 24)
                    .allowsHitTesting(false)
                if model.showDone {
                    VStack(spacing: 12) {
                        bigButton("Practice freely") { model.startPractice() }
                        bigButton("Close") { dismiss() }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.black)
                }
            }
            .padding(.top, 12)

            Text(model.status)
                .font(.system(size: 16))
                .foregroundStyle(model.statusColor)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity, minHeight: 44)
                .padding(.horizontal, 20)
                .padding(.top, 10)

            HStack {
                textButton("Back", action: model.back).opacity(model.showBack ? 1 : 0).disabled(!model.showBack)
                Spacer()
                textButton("Skip", action: model.skip).opacity(model.showSkip ? 1 : 0).disabled(!model.showSkip)
            }
        }
        .background(Color.black)
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .defersSystemGestures(on: .all)
        .preferredColorScheme(.dark)
        .onAppear(perform: model.start)
        .onDisappear(perform: model.stop)
    }

    private func textButton(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.system(size: 16)).foregroundStyle(Color(white: 0.8))
                .padding(.horizontal, 20).padding(.vertical, 14)
        }
    }

    private func bigButton(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).foregroundStyle(Theme.text)
                .frame(width: 240, height: 56)
                .background(Theme.pill, in: RoundedRectangle(cornerRadius: 14))
        }
    }
}

/// The touch surface with the demo drawn on top (the demo takes no touches).
struct PracticeArea: UIViewRepresentable {
    let model: TutorialModel

    func makeUIView(context: Context) -> FeedbackView {
        let surface = FeedbackView(frame: .zero)
        let demo = DemoView(config: model.demoConfig, pointsPerMm: model.mm)
        demo.frame = surface.bounds
        demo.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        surface.addSubview(demo)
        model.attach(surface, demo)
        return surface
    }

    func updateUIView(_ view: FeedbackView, context: Context) {
    }
}
