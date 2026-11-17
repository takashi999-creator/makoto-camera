import UIKit
import CoreLocation
import ImageIO
import Photos
import UniformTypeIdentifiers

struct ShotSettings {
    let stamp: Bool
    let qr: Bool        // 表示の帯に、簡易確認用の QR を入れるか
    let frontLens: Bool
    let zoom: Double
    let ev: Double
    let flash: String   // off / auto / on
    let ratio: String   // 4:3 / 16:9 / 1:1
}

/// 保存した写真。原本のまま送るために、ファイルも一時フォルダに置く
struct SavedShot {
    let idHex: String
    let fileURL: URL
}

enum PipelineError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let m) = self { return m }; return nil }
}

/// RGBX(1 画素 4 バイト)の作業用ビットマップ。行は上から順。
final class PixelBuffer {
    let width: Int, height: Int
    let context: CGContext
    var bytesPerRow: Int { context.bytesPerRow }
    var pixels: UnsafeMutablePointer<UInt8> { context.data!.assumingMemoryBound(to: UInt8.self) }

    init(width: Int, height: Int) throws {
        guard width > 0, height > 0,
              let c = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue),
              c.data != nil else { throw PipelineError.message("画像用のメモリを確保できません") }
        self.width = width; self.height = height; context = c
    }

    /// ファイルの画素をそのまま(Exif の向きを適用せずに)読む。判定用。
    static func raw(from data: Data) throws -> PixelBuffer {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else { throw PipelineError.message("画像を読み込めません") }
        let b = try PixelBuffer(width: img.width, height: img.height)
        b.context.interpolationQuality = .none
        b.context.draw(img, in: CGRect(x: 0, y: 0, width: img.width, height: img.height))
        return b
    }
}

enum PhotoPipeline {
    static let appVersion = "0.1.0"

    static func deviceModel() -> String {
        var info = utsname()
        uname(&info)
        let id = withUnsafeBytes(of: &info.machine) { raw in String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self) }
        return "Apple " + id
    }

    /// 比率に合わせて中央を切り出す範囲(向きはそのまま。4:3 で撮った画像から切り出す)
    static func cropRect(width w: Int, height h: Int, ratio: String) -> CGRect {
        let (a, b): (Double, Double) = ratio == "16:9" ? (16, 9) : (ratio == "1:1" ? (1, 1) : (4, 3))
        let target = h >= w ? b / a : a / b          // 幅 ÷ 高さ
        var cw = Double(w), ch = Double(h)
        if cw / ch > target { cw = (ch * target).rounded() } else { ch = (cw / target).rounded() }
        return CGRect(x: ((Double(w) - cw) / 2).rounded(), y: ((Double(h) - ch) / 2).rounded(), width: cw, height: ch)
    }

    /// カメラから受け取った JPEG を、表示入れ → 透かし → Exif → 署名 → 保存 の順に処理する。
    static func process(captured: Data, loc: CLLocation?, settings s: ShotSettings) async throws -> SavedShot {
        let now = Date()
        let timeMs = Int64(now.timeIntervalSince1970 * 1000)
        var rnd: UInt64 = 0
        guard withUnsafeMutableBytes(of: &rnd, { SecRandomCopyBytes(kSecRandomDefault, 8, $0.baseAddress!) }) == errSecSuccess else {
            throw PipelineError.message("乱数を作れません")
        }
        let id = rnd & 0xFFFF_FFFF_FFFF
        let idHex = toHex(WmMath.idBytes(id))

        // 1. 正しい向きで描く(向きを画素に焼き込むと、再保存後も透かしが読める)
        guard let ui = UIImage(data: captured) else { throw PipelineError.message("撮影データを読み込めません") }
        let fullW = Int((ui.size.width * ui.scale).rounded())
        let fullH = Int((ui.size.height * ui.scale).rounded())
        let crop = cropRect(width: fullW, height: fullH, ratio: s.ratio)
        let w = Int(crop.width)
        let h = Int(crop.height)
        let buf = try PixelBuffer(width: w, height: h)
        let ctx = buf.context
        ctx.translateBy(x: 0, y: CGFloat(h))
        ctx.scaleBy(x: 1, y: -1)
        UIGraphicsPushContext(ctx)
        ui.draw(in: CGRect(x: -crop.minX, y: -crop.minY, width: CGFloat(fullW), height: CGFloat(fullH)))
        // 2. 表示(非表示にできる)
        let timeJst = Format.jst(now, "yyyy-MM-dd'T'HH:mm:ss") + "+09:00"
        if s.stamp {
            var qrText: String? = nil
            if s.qr, let pub = try? DeviceKey.publicKey() {
                qrText = try? QrPayload.build(shotId: WmMath.idBytes(id), timeMs: timeMs,
                                              lat: loc?.coordinate.latitude, lon: loc?.coordinate.longitude,
                                              alt: loc.flatMap { Format.altitude($0) },
                                              sign: { try DeviceKey.sign($0) }, publicKey: pub)
            }
            Stamp.draw(width: CGFloat(w), height: CGFloat(h), date: now, loc: loc, qrText: qrText)
        }
        UIGraphicsPopContext()

        // 3. 透かし。先に絵柄を調べて、ビットごとの強さを決める
        let host = ImageAnalyzer(w: w, h: h)
        host.feed(buf.pixels, bytesPerRow: buf.bytesPerRow)
        let cells = WmMath.planCells(hostEst: host.cellEstimates(), activity: host.cellActivity(), id: id)
        WatermarkEmbedder(w: w, h: h, cells: cells).apply(buf.pixels, bytesPerRow: buf.bytesPerRow)
        let marked = ImageAnalyzer(w: w, h: h)
        marked.feed(buf.pixels, bytesPerRow: buf.bytesPerRow)

        // 4. JPEG にして Exif を書く
        guard let cg = ctx.makeImage() else { throw PipelineError.message("画像を作れません") }
        let dt = Format.jst(now, "yyyy:MM:dd HH:mm:ss")
        let model = deviceModel()
        var props: [CFString: Any] = [
            kCGImageDestinationLossyCompressionQuality: 0.95,
            kCGImagePropertyOrientation: 1,
            kCGImagePropertyExifDictionary: [
                kCGImagePropertyExifDateTimeOriginal: dt,
                kCGImagePropertyExifDateTimeDigitized: dt,
                kCGImagePropertyExifOffsetTimeOriginal: "+09:00",
                kCGImagePropertyExifOffsetTimeDigitized: "+09:00",
                kCGImagePropertyExifOffsetTime: "+09:00"
            ] as [CFString: Any],
            kCGImagePropertyTIFFDictionary: [
                kCGImagePropertyTIFFMake: "Apple",
                kCGImagePropertyTIFFModel: model.replacingOccurrences(of: "Apple ", with: ""),
                kCGImagePropertyTIFFSoftware: "MakotoCamera " + appVersion,
                kCGImagePropertyTIFFDateTime: dt,
                kCGImagePropertyTIFFImageDescription: "MakotoCamera shot " + idHex
            ] as [CFString: Any]
        ]
        if let loc = loc {
            var gps: [CFString: Any] = [
                kCGImagePropertyGPSLatitude: abs(loc.coordinate.latitude),
                kCGImagePropertyGPSLatitudeRef: loc.coordinate.latitude >= 0 ? "N" : "S",
                kCGImagePropertyGPSLongitude: abs(loc.coordinate.longitude),
                kCGImagePropertyGPSLongitudeRef: loc.coordinate.longitude >= 0 ? "E" : "W"
            ]
            if let a = Format.altitude(loc) {
                gps[kCGImagePropertyGPSAltitude] = abs(a)
                gps[kCGImagePropertyGPSAltitudeRef] = a >= 0 ? 0 : 1
            }
            props[kCGImagePropertyGPSDictionary] = gps
        }
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else { throw PipelineError.message("JPEG を作れません") }
        CGImageDestinationAddImage(dest, cg, props as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { throw PipelineError.message("JPEG を作れません") }
        let jpeg = [UInt8](out as Data)

        // 5. 記録を作って署名し、JPEG に入れる
        var f: [(String, JsonValue)] = [
            ("v", .int(1)), ("app", .string(Spec.appName)), ("appVersion", .string(appVersion)), ("platform", .string("ios")),
            ("shotId", .string(idHex)),
            ("timeJst", .string(timeJst)),
            ("timeMs", .int(timeMs))
        ]
        if let loc = loc {
            f.append(("lat", .double(loc.coordinate.latitude)))
            f.append(("lon", .double(loc.coordinate.longitude)))
            if let a = Format.altitude(loc) { f.append(("alt", .double(a))); f.append(("altType", .string("msl"))) }
            f.append(("acc", .double(loc.horizontalAccuracy)))
            f.append(("gpsTimeMs", .int(Int64(loc.timestamp.timeIntervalSince1970 * 1000))))
            f.append(("mock", .bool(loc.sourceInformation?.isSimulatedBySoftware ?? false)))
        }
        f.append(("device", .string(model)))
        let osv = ProcessInfo.processInfo.operatingSystemVersion
        f.append(("os", .string("iOS \(osv.majorVersion).\(osv.minorVersion).\(osv.patchVersion)")))
        f.append(("lens", .string(s.frontLens ? "front" : "back")))
        f.append(("zoom", .double(s.zoom)))
        f.append(("ev", .double(s.ev)))
        f.append(("flash", .string(s.flash)))
        f.append(("stamp", .bool(s.stamp)))
        f.append(("qr", .bool(s.stamp && s.qr)))
        f.append(("ratio", .string(s.ratio)))
        f.append(("w", .int(Int64(w))))
        f.append(("h", .int(Int64(h))))
        f.append(("map", .string(Data(marked.map()).base64EncodedString())))

        let sealed = try Sealer.seal(jpeg: jpeg, fields: f, sign: { try DeviceKey.sign($0) }, publicKey: try DeviceKey.publicKey())

        // 6. 保存(バイト列をそのまま書く)と、端末内の控え
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { throw PipelineError.message("写真への保存が許可されていません") }
        let data = Data(sealed.file)
        let name = "MAKOTO_" + Format.jst(now, "yyyyMMdd_HHmmss") + "_" + String(idHex.prefix(4)) + ".jpg"
        try await PHPhotoLibrary.shared().performChanges {
            let req = PHAssetCreationRequest.forAsset()
            let opt = PHAssetResourceCreationOptions()
            opt.originalFilename = name
            req.addResource(with: .photo, data: data, options: opt)
        }
        RecordStore.save(idHex: idHex, packed: sealed.record.pack())

        // 原本のまま送れるように、同じバイト列を一時フォルダにも置く(前回のものは消す)
        let shareDir = FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
        try? FileManager.default.removeItem(at: shareDir)
        try? FileManager.default.createDirectory(at: shareDir, withIntermediateDirectories: true)
        let url = shareDir.appendingPathComponent(name)
        try data.write(to: url)
        return SavedShot(idHex: idHex, fileURL: url)
    }
}
