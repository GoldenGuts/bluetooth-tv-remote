import SwiftUI

@main
struct TVRemoteApp: App {
    @State private var model = RemoteModel()

    var body: some Scene {
        WindowGroup {
            RemoteScreen(model: model)
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
        }
    }
}
