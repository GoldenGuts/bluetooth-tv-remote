import UIKit

/// Physical size of a point. iOS gives no dpi: the iPhone screens fall into a few known densities.
enum Screen {

    /// Points per millimetre on this device.
    static var pointsPerMm: CGFloat {
        let traits = UITraitCollection.current
        let scale = traits.displayScale > 0 ? traits.displayScale : 3
        let pointsPerInch: CGFloat
        if traits.userInterfaceIdiom == .pad {
            pointsPerInch = 132        // 264 ppi at 2x (iPad mini: 163)
        } else if scale >= 3 {
            pointsPerInch = 460 / 3    // the 3x iPhones: 458 to 476 ppi
        } else {
            pointsPerInch = 163        // the 2x iPhones: 326 ppi
        }
        return pointsPerInch / 25.4
    }

    /// The milliseconds clock of UITouch.timestamp (seconds since boot).
    static func now() -> Int64 {
        Int64(ProcessInfo.processInfo.systemUptime * 1000)
    }

    static func ms(_ seconds: TimeInterval) -> Int64 {
        Int64(seconds * 1000)
    }

    static var window: UIWindowScene? {
        UIApplication.shared.connectedScenes.first as? UIWindowScene
    }
}
