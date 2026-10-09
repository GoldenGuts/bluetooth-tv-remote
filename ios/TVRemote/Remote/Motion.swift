import CoreMotion
import Foundation

/**
 Gyroscope and gravity from Core Motion. iOS and Android use the same device axes (x right, y to the top edge,
 z out of the screen, + = counter-clockwise), so the knob math of the Android app carries over.
 */
final class Motion {

    /// Rotation rate in rad/s around x, y, z, and the sample time in seconds since boot.
    var onRotation: (Double, Double, Double, TimeInterval) -> Void = { _, _, _, _ in }
    /// Gravity like Android's TYPE_GRAVITY: m/s², + y when the phone stands upright.
    var onGravity: (Double, Double) -> Void = { _, _ in }

    private let manager = CMMotionManager()

    var hasGyro: Bool { manager.isGyroAvailable }

    func start() {
        guard manager.isDeviceMotionAvailable, !manager.isDeviceMotionActive else { return }
        manager.deviceMotionUpdateInterval = 1.0 / 60
        manager.startDeviceMotionUpdates(to: .main) { [weak self] motion, _ in
            guard let motion else { return }
            let r = motion.rotationRate, g = motion.gravity, t = motion.timestamp
            MainActor.assumeIsolated {
                self?.onRotation(r.x, r.y, r.z, t)
                // Core Motion gravity points down in g; Android's points up in m/s²
                self?.onGravity(-g.x * 9.81, -g.y * 9.81)
            }
        }
    }

    func stop() {
        manager.stopDeviceMotionUpdates()
    }
}
