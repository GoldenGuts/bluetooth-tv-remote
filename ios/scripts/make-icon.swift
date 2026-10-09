// Draws the app icon (the Android ic_remote.xml vector) as a 1024 px PNG without alpha.
// Usage: swift ios/scripts/make-icon.swift ios/TVRemote/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let out = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "AppIcon.png"
let px = 1024
let s = CGFloat(px) / 108   // the vector's viewport is 108 x 108

guard let ctx = CGContext(data: nil, width: px, height: px, bitsPerComponent: 8, bytesPerRow: 0,
                          space: CGColorSpace(name: CGColorSpace.sRGB)!,
                          bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { fatalError("no context") }
// Vector coordinates have y down.
ctx.translateBy(x: 0, y: CGFloat(px))
ctx.scaleBy(x: s, y: -s)

func rgb(_ hex: UInt32) -> CGColor {
    CGColor(srgbRed: CGFloat(hex >> 16 & 0xFF) / 255, green: CGFloat(hex >> 8 & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
}

let accent = rgb(0x8AB4F8)
ctx.setFillColor(rgb(0x101114))
ctx.fill(CGRect(x: 0, y: 0, width: 108, height: 108))

// The remote: a rounded outline
ctx.setStrokeColor(accent)
ctx.setLineWidth(3)
ctx.addPath(CGPath(roundedRect: CGRect(x: 36, y: 24, width: 36, height: 60), cornerWidth: 6, cornerHeight: 6, transform: nil))
ctx.strokePath()

// The touch ring and the dot
ctx.setStrokeColor(rgb(0xFFFFFF))
ctx.setLineWidth(2.5)
ctx.strokeEllipse(in: CGRect(x: 45, y: 36, width: 18, height: 18))
ctx.setFillColor(rgb(0xFFFFFF))
ctx.fillEllipse(in: CGRect(x: 51.5, y: 42.5, width: 5, height: 5))

// Two bars
ctx.setFillColor(accent)
ctx.fill(CGRect(x: 46, y: 62, width: 16, height: 3))
ctx.fill(CGRect(x: 46, y: 70, width: 16, height: 3))

guard let image = ctx.makeImage(),
      let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: out) as CFURL, UTType.png.identifier as CFString, 1, nil)
else { fatalError("cannot write \(out)") }
CGImageDestinationAddImage(dest, image, nil)
guard CGImageDestinationFinalize(dest) else { fatalError("cannot write \(out)") }
print("wrote \(out)")
