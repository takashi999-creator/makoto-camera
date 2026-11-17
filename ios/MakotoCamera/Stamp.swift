import UIKit
import CoreLocation
import CoreImage

enum Format {
    static let tokyo = TimeZone(identifier: "Asia/Tokyo")!

    static func jst(_ date: Date, _ pattern: String) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = tokyo
        f.dateFormat = pattern
        return f.string(from: date)
    }

    static func latLon(_ lat: Double, _ lon: Double) -> String {
        String(format: "%@ %.6f°  %@ %.6f°", lat >= 0 ? "北緯" : "南緯", abs(lat), lon >= 0 ? "東経" : "西経", abs(lon))
    }

    /// 標高(平均海面からの高さ)。取れていなければ nil。
    static func altitude(_ loc: CLLocation) -> Double? {
        loc.verticalAccuracy > 0 ? loc.altitude : nil
    }
}

/// 写真の下端に入れる表示(場所、時刻、標高、アプリ名、AI編集不可ロゴ)。非表示にもできる。
enum Stamp {
    static func lines(_ date: Date, _ loc: CLLocation?) -> [String] {
        var out = [Format.jst(date, "yyyy/MM/dd HH:mm:ss") + " 日本時間"]
        if let loc = loc {
            out.append(Format.latLon(loc.coordinate.latitude, loc.coordinate.longitude))
            if let a = Format.altitude(loc) { out.append(String(format: "標高 %.1f m", a)) }
        } else {
            out.append("位置情報なし")
        }
        return out
    }

    /// QR の画像(1 マス 1 画素、白地に黒)。作れなければ nil。
    static func qrImage(_ text: String) -> CGImage? {
        guard let f = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        f.setValue(Data(text.utf8), forKey: "inputMessage")
        f.setValue("L", forKey: "inputCorrectionLevel")
        guard let out = f.outputImage else { return nil }
        return CIContext(options: nil).createCGImage(out, from: out.extent)
    }

    /// 左上が原点の UIKit 描画コンテキスト(UIGraphicsPushContext 済み)に描く。
    /// qrText: QR に入れる文字列。nil なら QR なし
    static func draw(width w: CGFloat, height h: CGFloat, date: Date, loc: CLLocation?, qrText: String? = nil) {
        let u = min(w, h) / 36
        let pad = u * 0.8
        let ls = lines(date, loc)
        let lineH = u * 1.35
        // QR を入れるときは、読み取れる大きさ(短辺の 30%)を確保するため帯を高くする
        let qr = qrText.flatMap { qrImage($0) }
        let qrSide = qr != nil ? min(w, h) * 0.30 : 0
        let bandH = max(pad * 2 + lineH * CGFloat(ls.count + 1), qr != nil ? qrSide + pad * 2 : 0)

        UIColor(red: 0x0E / 255.0, green: 0x14 / 255.0, blue: 0x1B / 255.0, alpha: 0.6).setFill()
        UIRectFillUsingBlendMode(CGRect(x: 0, y: h - bandH, width: w, height: bandH), .normal)

        let text: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: u), .foregroundColor: UIColor.white]
        let bold: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: u * 1.05), .foregroundColor: UIColor.white]

        // 1 行目: 盾マーク + アプリ名 + AI編集不可バッジ
        var y = h - bandH + pad
        let s = u * 1.1
        drawShield(x: pad, y: y, s: s)
        var x = pad + s + u * 0.4
        let name = Spec.appName as NSString
        name.draw(at: CGPoint(x: x, y: y), withAttributes: bold)
        x += name.size(withAttributes: bold).width + u * 0.7
        let badge = "AI編集不可" as NSString
        let bs = badge.size(withAttributes: bold)
        let frame = UIBezierPath(roundedRect: CGRect(x: x, y: y - u * 0.05, width: bs.width + u * 0.8, height: bs.height + u * 0.1), cornerRadius: u * 0.2)
        frame.lineWidth = u * 0.08
        UIColor.white.setStroke()
        frame.stroke()
        badge.draw(at: CGPoint(x: x + u * 0.4, y: y), withAttributes: bold)

        for line in ls {
            y += lineH
            (line as NSString).draw(at: CGPoint(x: pad, y: y), withAttributes: text)
        }

        if let qr = qr, let ctx = UIGraphicsGetCurrentContext() {
            // 白地の上に、周りに 4 マス分の余白を空けて描く(拡大はぼかさない)
            let left = w - pad - qrSide
            let top = h - bandH + (bandH - qrSide) / 2
            UIColor.white.setFill()
            UIRectFill(CGRect(x: left, y: top, width: qrSide, height: qrSide))
            let n = CGFloat(qr.width)
            let cell = qrSide / (n + 8)
            ctx.saveGState()
            ctx.interpolationQuality = .none
            UIImage(cgImage: qr).draw(in: CGRect(x: left + cell * 4, y: top + cell * 4, width: cell * n, height: cell * n))
            ctx.restoreGState()
        }
    }

    private static func drawShield(x: CGFloat, y: CGFloat, s: CGFloat) {
        let p = UIBezierPath()
        p.move(to: CGPoint(x: x + s * 0.5, y: y))
        p.addLine(to: CGPoint(x: x + s, y: y + s * 0.18))
        p.addLine(to: CGPoint(x: x + s, y: y + s * 0.55))
        p.addCurve(to: CGPoint(x: x + s * 0.5, y: y + s * 1.15), controlPoint1: CGPoint(x: x + s, y: y + s * 0.85), controlPoint2: CGPoint(x: x + s * 0.75, y: y + s * 1.05))
        p.addCurve(to: CGPoint(x: x, y: y + s * 0.55), controlPoint1: CGPoint(x: x + s * 0.25, y: y + s * 1.05), controlPoint2: CGPoint(x: x, y: y + s * 0.85))
        p.addLine(to: CGPoint(x: x, y: y + s * 0.18))
        p.close()
        UIColor.white.setFill()
        p.fill()
        let c = UIBezierPath()
        c.move(to: CGPoint(x: x + s * 0.27, y: y + s * 0.58))
        c.addLine(to: CGPoint(x: x + s * 0.45, y: y + s * 0.76))
        c.addLine(to: CGPoint(x: x + s * 0.75, y: y + s * 0.40))
        c.lineWidth = s * 0.13
        c.lineCapStyle = .round
        c.lineJoinStyle = .round
        UIColor(red: 0x2F / 255.0, green: 0x80 / 255.0, blue: 0xC2 / 255.0, alpha: 1).setStroke()
        c.stroke()
    }
}
