#if DEBUG
import UIKit

/**
 Store screenshots: `-shot menu|drag|panel|settings|tutorial|setup` on the launch line sets up a fixed screen.
 Debug builds only. ios/scripts/screenshots.sh uses it.
 */
enum Shots {

    private static var done = false

    static var name: String? {
        UserDefaults.standard.string(forKey: "shot")   // -shot <name> on the launch line
    }

    /// The tutorial lesson to show (-shotLesson <index>).
    static var lesson: Int? {
        UserDefaults.standard.object(forKey: "shotLesson") != nil ? UserDefaults.standard.integer(forKey: "shotLesson") : nil
    }

    static func apply(_ model: RemoteModel) {
        guard let name, !done else { return }
        done = true
        UserDefaults.standard.set(true, forKey: "tutorialSeen")
        model.showTutorial = name == "tutorial"
        model.showSetup = name == "setup"
        model.practice = name == "menu" || name == "drag"
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
            guard let v = model.feedback else { return }
            let c = CGPoint(x: v.bounds.midX, y: v.bounds.midY + 40)
            switch name {
            case "menu":
                v.touchDown(c)
                v.frozenAt = CACurrentMediaTime() * 1000 + 10_000
                v.menuOpened()
            case "drag":
                v.touchDown(CGPoint(x: c.x - 90, y: c.y))
                v.touchMove(CGPoint(x: c.x + 30, y: c.y))
                v.frozenAt = CACurrentMediaTime() * 1000 + 90
                v.step(FeedbackView.RIGHT)
                v.touchMove(CGPoint(x: c.x + 60, y: c.y))
                v.step(FeedbackView.RIGHT)
                v.setNeedsDisplay()
                model.label("right")
            case "panel":
                model.panelOpen = true
            case "settings":
                model.panelOpen = true
            default:
                break
            }
        }
    }
}
#endif
