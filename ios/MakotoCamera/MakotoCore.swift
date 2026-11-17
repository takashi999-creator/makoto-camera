import Foundation
import CryptoKit

// マコトカメラ 共通仕様 v1。Android 版 MakotoCore.kt と同じ計算をすること。
//
// 1. 頑丈な透かし : 画像を 64x64 のセルに分け、各セルに sin(2πu)·sin(2πv) の淡い模様を足す。
//                   模様の向き(+/-)で 64bit (撮影ID 48bit + CRC16) を繰り返し埋める。
//                   絵柄に負けそうなビットだけ強くし、空や壁のような平らな場所では弱くする。
// 2. 署名付き記録 : JPEG の APP11 セグメントに「記録(JSON) + ECDSA署名 + 公開鍵」を入れる。
//                   記録には、このセグメントと Exif(APP1) を除いたファイル全体の SHA-256 が入る。
// 3. 変化マップ   : 32x32 マスの平均輝度を記録に入れ、編集された場所を示す。
//
// 画素は RGBX(1 画素 4 バイト、R が先頭)、上の行から順に並んでいるものとする。

enum Spec {
    static let grid = 64
    static let idBytes = 6
    static let bits = 64
    static let ampMin = 3.0
    static let ampMax = 6.0
    static let clip = 6.0
    static let margin = 1.3
    static let flatFactor = 0.3
    static let textureStd = 10.0
    static let map = 32
    static let mapThreshold = 8
    static let minStrength = 0.5
    static let magic: [UInt8] = [0x4D, 0x41, 0x4B, 0x4F, 0x54, 0x4F, 0x31, 0x00] // "MAKOTO1\0"
    static let appName = "マコトカメラ"
}

func toHex(_ b: [UInt8]) -> String {
    let d = Array("0123456789abcdef".utf8)
    var out = [UInt8]()
    out.reserveCapacity(b.count * 2)
    for v in b { out.append(d[Int(v >> 4)]); out.append(d[Int(v & 15)]) }
    return String(decoding: out, as: UTF8.self)
}

func sha256(_ b: [UInt8]) -> [UInt8] { Array(SHA256.hash(data: Data(b))) }

// MARK: - 透かし

enum WmMath {
    static func crc16(_ data: [UInt8]) -> Int {
        var crc = 0xFFFF
        for b in data {
            crc ^= Int(b) << 8
            for _ in 0..<8 {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1
                crc &= 0xFFFF
            }
        }
        return crc
    }

    /// id の下位 48bit を 6 バイト(上位から)にする
    static func idBytes(_ id: UInt64) -> [UInt8] {
        (0..<Spec.idBytes).map { i in UInt8(truncatingIfNeeded: id >> UInt64(8 * (Spec.idBytes - 1 - i))) }
    }

    static func payload(_ id: UInt64) -> [Bool] {
        let b = idBytes(id)
        let crc = crc16(b)
        let all = b + [UInt8((crc >> 8) & 0xFF), UInt8(crc & 0xFF)]
        return (0..<Spec.bits).map { i in (Int(all[i / 8]) >> (7 - i % 8)) & 1 == 1 }
    }

    static func cellBit(_ idx: Int) -> Int {
        ((idx % Spec.grid) * 37 + (idx / Spec.grid) * 21) % Spec.bits
    }

    static func cellSign(_ idx: Int) -> Int {
        let v = UInt32(truncatingIfNeeded: idx) &* 1103515245 &+ 12345
        return ((v >> 16) & 1) == 1 ? 1 : -1
    }

    static func axis(_ n: Int) -> (cells: [Int], wave: [Float]) {
        var cells = [Int](repeating: 0, count: n)
        var wave = [Float](repeating: 0, count: n)
        for i in 0..<n {
            let u = (Double(i) + 0.5) * Double(Spec.grid) / Double(n)
            var c = Int(u)
            if c >= Spec.grid { c = Spec.grid - 1 }
            cells[i] = c
            wave[i] = Float(sin(2.0 * Double.pi * (u - Double(c))))
        }
        return (cells, wave)
    }

    static func votes(_ est: [Double], extra: ((Int) -> Double)?) -> [Double] {
        var vote = [Double](repeating: 0, count: Spec.bits)
        for i in 0..<est.count {
            if est[i].isNaN { continue }
            var e = est[i] + (extra?(i) ?? 0)
            if e > Spec.clip { e = Spec.clip } else if e < -Spec.clip { e = -Spec.clip }
            vote[cellBit(i)] += Double(cellSign(i)) * e
        }
        return vote
    }

    /// セル内の輝度のばらつきから、振幅の倍率を決める(平らな場所ほど弱く)
    static func cellFactor(_ activity: Double) -> Double {
        min(1.0, max(Spec.flatFactor, activity / Spec.textureStd))
    }

    /// 元画像のセル推定値とばらつきから、セルごとの模様(符号付きの振幅)を決める
    static func planCells(hostEst: [Double], activity: [Double], id: UInt64) -> [Float] {
        let bits = payload(id)
        let reps = Double(Spec.grid * Spec.grid / Spec.bits)
        let n = Spec.grid * Spec.grid
        let unit: [Double] = (0..<n).map { i in cellFactor(activity[i]) * Double(cellSign(i)) * (bits[cellBit(i)] ? 1 : -1) }
        var amp = [Double](repeating: Spec.ampMax, count: Spec.bits)
        var todo = [Bool](repeating: true, count: Spec.bits)
        var a = Spec.ampMin
        while a <= Spec.ampMax + 1e-9 {
            let v = votes(hostEst) { i in a * unit[i] }
            for k in 0..<Spec.bits where todo[k] {
                let m = (bits[k] ? v[k] : -v[k]) / reps
                if m >= Spec.margin { amp[k] = a; todo[k] = false }
            }
            a += 0.5
        }
        return (0..<n).map { i in Float(amp[cellBit(i)] * unit[i]) }
    }

    /// 小数の振幅を整数の画素に散らすための 4x4 の表(0〜15)
    static let dither: [Float] = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5]
}

final class WatermarkEmbedder {
    private let w: Int, h: Int
    private let cx: [Int], sx: [Float], cy: [Int], sy: [Float]
    private let t: [Float]

    /// cells: WmMath.planCells の結果
    init(w: Int, h: Int, cells: [Float]) {
        self.w = w; self.h = h
        let ax = WmMath.axis(w)
        let ay = WmMath.axis(h)
        cx = ax.cells; sx = ax.wave
        cy = ay.cells; sy = ay.wave
        t = cells
    }

    func apply(_ px: UnsafeMutablePointer<UInt8>, bytesPerRow: Int) {
        let dither = WmMath.dither
        cx.withUnsafeBufferPointer { cx in sx.withUnsafeBufferPointer { sx in t.withUnsafeBufferPointer { t in
            for y in 0..<h {
                let rowCell = cy[y] * Spec.grid
                let wy = sy[y]
                let drow = (y & 3) * 4
                var p = px + y * bytesPerRow
                for x in 0..<w {
                    // 小数部分は 4x4 の表で散らす(平均すると元の小数の値になる)
                    let d = Int((t[rowCell + cx[x]] * sx[x] * wy + (dither[drow + (x & 3)] + 0.5) / 16).rounded(.down))
                    if d != 0 {
                        p[0] = UInt8(max(0, min(255, Int(p[0]) + d)))
                        p[1] = UInt8(max(0, min(255, Int(p[1]) + d)))
                        p[2] = UInt8(max(0, min(255, Int(p[2]) + d)))
                    }
                    p += 4
                }
            }
        } } }
    }
}

struct WatermarkResult {
    let found: Bool
    let id: UInt64
    let strength: Double
    var idHex: String { toHex(WmMath.idBytes(id)) }
}

final class ImageAnalyzer {
    private let w: Int, h: Int
    private let cx: [Int], sx: [Float], cy: [Int], sy: [Float]
    private var acc = [Double](repeating: 0, count: Spec.grid * Spec.grid)
    private var norm = [Double](repeating: 0, count: Spec.grid * Spec.grid)
    private var mapSum = [Int](repeating: 0, count: Spec.map * Spec.map)
    private var mapCnt = [Int](repeating: 0, count: Spec.map * Spec.map)
    private var lumSum = [Double](repeating: 0, count: Spec.grid * Spec.grid)
    private var lumSq = [Double](repeating: 0, count: Spec.grid * Spec.grid)
    private var lumCnt = [Int](repeating: 0, count: Spec.grid * Spec.grid)
    private let mx: [Int]

    init(w: Int, h: Int) {
        self.w = w; self.h = h
        let ax = WmMath.axis(w)
        let ay = WmMath.axis(h)
        cx = ax.cells; sx = ax.wave
        cy = ay.cells; sy = ay.wave
        mx = (0..<w).map { min(Spec.map - 1, $0 * Spec.map / w) }
    }

    func feed(_ px: UnsafePointer<UInt8>, bytesPerRow: Int) {
        cx.withUnsafeBufferPointer { cx in sx.withUnsafeBufferPointer { sx in mx.withUnsafeBufferPointer { mx in
        acc.withUnsafeMutableBufferPointer { acc in norm.withUnsafeMutableBufferPointer { norm in
        mapSum.withUnsafeMutableBufferPointer { mapSum in mapCnt.withUnsafeMutableBufferPointer { mapCnt in
        lumSum.withUnsafeMutableBufferPointer { lumSum in lumSq.withUnsafeMutableBufferPointer { lumSq in
        lumCnt.withUnsafeMutableBufferPointer { lumCnt in
            for y in 0..<h {
                let rowCell = cy[y] * Spec.grid
                let wy = sy[y]
                let mrow = min(Spec.map - 1, y * Spec.map / h) * Spec.map
                var p = px + y * bytesPerRow
                for x in 0..<w {
                    let l = (77 * Int(p[0]) + 150 * Int(p[1]) + 29 * Int(p[2])) >> 8
                    let k = sx[x] * wy
                    let i = rowCell + cx[x]
                    acc[i] += Double(Float(l) * k)
                    norm[i] += Double(k * k)
                    lumSum[i] += Double(l)
                    lumSq[i] += Double(l * l)
                    lumCnt[i] += 1
                    let m = mrow + mx[x]
                    mapSum[m] += l
                    mapCnt[m] += 1
                    p += 4
                }
            }
        } } } } } } } } } }
    }

    func cellEstimates() -> [Double] {
        (0..<acc.count).map { norm[$0] > 0 ? acc[$0] / norm[$0] : Double.nan }
    }

    /// セルごとの輝度のばらつき(標準偏差)
    func cellActivity() -> [Double] {
        (0..<lumCnt.count).map { i in
            if lumCnt[i] == 0 { return 0 }
            let m = lumSum[i] / Double(lumCnt[i])
            return (max(0, lumSq[i] / Double(lumCnt[i]) - m * m)).squareRoot()
        }
    }

    func watermark() -> WatermarkResult {
        let vote = WmMath.votes(cellEstimates(), extra: nil)
        let reps = Double(Spec.grid * Spec.grid / Spec.bits)
        let n = Spec.idBytes
        var bytes = [UInt8](repeating: 0, count: n + 2)
        var strength = 0.0
        for k in 0..<Spec.bits {
            if vote[k] > 0 { bytes[k / 8] |= UInt8(1 << (7 - k % 8)) }
            strength += abs(vote[k]) / reps
        }
        strength /= Double(Spec.bits)
        var id: UInt64 = 0
        for i in 0..<n { id = (id << 8) | UInt64(bytes[i]) }
        let crc = (Int(bytes[n]) << 8) | Int(bytes[n + 1])
        let ok = crc == WmMath.crc16(Array(bytes[0..<n])) && strength >= Spec.minStrength
        return WatermarkResult(found: ok, id: id, strength: strength)
    }

    func map() -> [UInt8] {
        (0..<mapSum.count).map { mapCnt[$0] == 0 ? 0 : UInt8(truncatingIfNeeded: mapSum[$0] / mapCnt[$0]) }
    }
}

// MARK: - JPEG 内の記録

struct Record {
    let manifest: [UInt8]
    let signature: [UInt8]
    let publicKey: [UInt8]   // 65 バイト: 04 || X || Y

    var keyId: String { toHex(Array(sha256(publicKey)[0..<8])) }

    func signatureValid() -> Bool {
        guard publicKey.count == 65,
              let key = try? P256.Signing.PublicKey(x963Representation: Data(publicKey)),
              let sig = try? P256.Signing.ECDSASignature(derRepresentation: Data(signature)) else { return false }
        return key.isValidSignature(sig, for: Data(manifest))
    }

    func pack() -> [UInt8] {
        func be(_ v: Int, _ n: Int) -> [UInt8] { (0..<n).map { UInt8((v >> (8 * (n - 1 - $0))) & 0xFF) } }
        return Spec.magic + be(manifest.count, 4) + manifest + be(signature.count, 2) + signature + be(publicKey.count, 2) + publicKey
    }

    static func unpack(_ p: [UInt8]) -> Record? {
        var o = Spec.magic.count
        func u(_ n: Int) -> Int? {
            guard o + n <= p.count else { return nil }
            var v = 0
            for _ in 0..<n { v = (v << 8) | Int(p[o]); o += 1 }
            return v
        }
        guard p.count >= o, let ml = u(4), o + ml <= p.count else { return nil }
        let m = Array(p[o..<(o + ml)]); o += ml
        guard let sl = u(2), o + sl <= p.count else { return nil }
        let s = Array(p[o..<(o + sl)]); o += sl
        guard let kl = u(2), o + kl <= p.count else { return nil }
        let k = Array(p[o..<(o + kl)])
        return Record(manifest: m, signature: s, publicKey: k)
    }
}

enum JpegContainer {
    private static let app11: UInt8 = 0xEB

    private static func isOurs(_ f: [UInt8], _ start: Int, _ len: Int) -> Bool {
        if len < Spec.magic.count { return false }
        for i in 0..<Spec.magic.count where f[start + i] != Spec.magic[i] { return false }
        return true
    }

    /// ハッシュ対象(記録セグメントと Exif(APP1) を除いたファイル)と、記録の中身を返す。JPEG でなければ nil。
    static func split(_ f: [UInt8]) -> (hashable: [UInt8], payload: [UInt8]?)? {
        guard f.count >= 4, f[0] == 0xFF, f[1] == 0xD8 else { return nil }
        var out = [UInt8]()
        out.reserveCapacity(f.count)
        out.append(contentsOf: f[0..<2])
        var payload: [UInt8]? = nil
        var o = 2
        while o + 4 <= f.count {
            if f[o] != 0xFF { break }
            let marker = f[o + 1]
            if marker == 0xDA || marker == 0xD9 { break }
            let len = (Int(f[o + 2]) << 8) | Int(f[o + 3])
            if len < 2 || o + 2 + len > f.count { break }
            if marker == app11 && payload == nil && isOurs(f, o + 4, len - 2) {
                payload = Array(f[(o + 4)..<(o + 2 + len)])
            } else if marker != 0xE1 {
                out.append(contentsOf: f[o..<(o + 2 + len)])
            }
            o += 2 + len
        }
        out.append(contentsOf: f[o..<f.count])
        return (out, payload)
    }

    /// APPn / COM セグメントの並びの直後に記録を入れる。
    static func insert(_ f: [UInt8], payload: [UInt8]) -> [UInt8] {
        precondition(payload.count + 2 <= 0xFFFF, "record too large")
        var o = 2
        while o + 4 <= f.count {
            if f[o] != 0xFF { break }
            let marker = f[o + 1]
            let isMeta = (marker >= 0xE0 && marker <= 0xEF) || marker == 0xFE
            if !isMeta { break }
            let len = (Int(f[o + 2]) << 8) | Int(f[o + 3])
            if len < 2 || o + 2 + len > f.count { break }
            o += 2 + len
        }
        let len = payload.count + 2
        let head: [UInt8] = [0xFF, app11, UInt8(len >> 8), UInt8(len & 0xFF)]
        return Array(f[0..<o]) + head + payload + Array(f[o..<f.count])
    }
}

// MARK: - かんたん JSON(平らなオブジェクトのみ)

enum JsonValue {
    case string(String), int(Int64), double(Double), bool(Bool)
}

enum FlatJson {
    static func write(_ fields: [(String, JsonValue)]) -> String {
        var s = "{"
        var first = true
        for (k, v) in fields {
            if !first { s += "," }
            first = false
            s += quote(k) + ":"
            switch v {
            case .string(let x): s += quote(x)
            case .int(let x): s += String(x)
            case .double(let x): s += (x.isNaN || x.isInfinite) ? "null" : "\(x)"
            case .bool(let x): s += x ? "true" : "false"
            }
        }
        return s + "}"
    }

    private static func quote(_ str: String) -> String {
        var s = "\""
        for ch in str.unicodeScalars {
            switch ch {
            case "\"": s += "\\\""
            case "\\": s += "\\\\"
            case "\n": s += "\\n"
            case "\r": s += "\\r"
            case "\t": s += "\\t"
            default:
                if ch.value < 0x20 { s += String(format: "\\u%04x", ch.value) } else { s.unicodeScalars.append(ch) }
            }
        }
        return s + "\""
    }

    /// 値はすべて文字列にして返す。壊れていたら空。
    static func read(_ bytes: [UInt8]) -> [String: String] {
        guard let obj = try? JSONSerialization.jsonObject(with: Data(bytes)) as? [String: Any] else { return [:] }
        var out = [String: String]()
        for (k, v) in obj {
            if v is NSNull { continue }
            if let s = v as? String { out[k] = s }
            else if let n = v as? NSNumber {
                if CFGetTypeID(n) == CFBooleanGetTypeID() { out[k] = n.boolValue ? "true" : "false" }
                else { out[k] = n.stringValue }
            }
        }
        return out
    }
}

// MARK: - 封印と判定

enum Sealer {
    /// jpeg: 透かしと Exif が入った完成 JPEG(記録セグメントなし)
    static func seal(jpeg: [UInt8], fields: [(String, JsonValue)], sign: ([UInt8]) throws -> [UInt8], publicKey: [UInt8]) throws -> (file: [UInt8], record: Record) {
        guard let parts = JpegContainer.split(jpeg) else { throw NSError(domain: "Makoto", code: 1, userInfo: [NSLocalizedDescriptionKey: "JPEG ではありません"]) }
        var m = fields
        m.append(("imageHash", .string(toHex(sha256(parts.hashable)))))
        let manifest = Array(FlatJson.write(m).utf8)
        let rec = Record(manifest: manifest, signature: try sign(manifest), publicKey: publicKey)
        return (JpegContainer.insert(jpeg, payload: rec.pack()), rec)
    }
}

enum Verdict {
    case original        // 署名も内容も一致。撮影時のまま
    case edited          // 記録は本物だが、画像が撮影後に変わっている
    case forgedRecord    // 記録はあるが署名が合わない
    case watermarkOnly   // 記録は消えているが、透かしからこのアプリの写真と分かる
    case similarLocal    // 記録も透かしもないが、この端末の控えに構図がよく似た写真がある(加工された可能性)
    case unknown         // 原本とは認められない(記録も透かしもない)
}

struct VerifyResult {
    let verdict: Verdict
    let fields: [String: String]
    let keyId: String?
    let watermark: WatermarkResult?
    let changed: [Bool]?
    let fromLocalCopy: Bool
    var changedCount: Int { changed?.filter { $0 }.count ?? 0 }
}

enum Verifier {
    static func originalMap(_ fields: [String: String]) -> [UInt8]? {
        guard let s = fields["map"], let d = Data(base64Encoded: s), d.count == Spec.map * Spec.map else { return nil }
        return Array(d)
    }

    static func diff(_ a: [UInt8], _ b: [UInt8]) -> [Bool] {
        (0..<a.count).map { abs(Int(a[$0]) - Int(b[$0])) >= Spec.mapThreshold }
    }

    /// 似ているとみなす、変化マスの割合の上限
    static let similarLimit = 0.30

    /// 全体の明るさのずれ(中央値)を引いてから比べる
    static func diffNormalized(_ orig: [UInt8], _ now: [UInt8]) -> [Bool] {
        let d = (0..<orig.count).map { Int(now[$0]) - Int(orig[$0]) }
        let med = d.sorted()[d.count / 2]
        return d.map { abs($0 - med) >= Spec.mapThreshold }
    }

    /// 控えの中から、構図が最も似ている写真を探す
    static func findSimilar(_ nowMap: [UInt8], _ records: [[UInt8]]) -> (record: Record, changed: [Bool])? {
        var best: (Record, [Bool], Int)?
        for packed in records {
            guard let rec = Record.unpack(packed), let orig = originalMap(FlatJson.read(rec.manifest)) else { continue }
            let d = diffNormalized(orig, nowMap)
            let c = d.filter { $0 }.count
            if best == nil || c < best!.2 { best = (rec, d, c) }
        }
        guard let b = best, Double(b.2) <= Double(nowMap.count) * similarLimit, b.0.signatureValid() else { return nil }
        return (b.0, b.1)
    }

    static func verify(file: [UInt8], analyzer: ImageAnalyzer?, localRecord: (String) -> [UInt8]?,
                       allLocal: () -> [[UInt8]] = { [] }) -> VerifyResult {
        let wm = analyzer?.watermark()
        let nowMap = analyzer?.map()
        func changes(_ fields: [String: String]) -> [Bool]? {
            guard let o = originalMap(fields), let n = nowMap else { return nil }
            return diff(o, n)
        }
        if let parts = JpegContainer.split(file), let payload = parts.payload, let rec = Record.unpack(payload) {
            if !rec.signatureValid() {
                return VerifyResult(verdict: .forgedRecord, fields: [:], keyId: rec.keyId, watermark: wm, changed: nil, fromLocalCopy: false)
            }
            let fields = FlatJson.read(rec.manifest)
            if fields["imageHash"] == toHex(sha256(parts.hashable)) {
                return VerifyResult(verdict: .original, fields: fields, keyId: rec.keyId, watermark: wm, changed: nil, fromLocalCopy: false)
            }
            return VerifyResult(verdict: .edited, fields: fields, keyId: rec.keyId, watermark: wm, changed: changes(fields), fromLocalCopy: false)
        }
        if let wm = wm, wm.found {
            if let packed = localRecord(wm.idHex), let local = Record.unpack(packed), local.signatureValid() {
                let fields = FlatJson.read(local.manifest)
                return VerifyResult(verdict: .watermarkOnly, fields: fields, keyId: local.keyId, watermark: wm, changed: changes(fields), fromLocalCopy: true)
            }
            return VerifyResult(verdict: .watermarkOnly, fields: [:], keyId: nil, watermark: wm, changed: nil, fromLocalCopy: false)
        }
        if let n = nowMap, let s = findSimilar(n, allLocal()) {
            return VerifyResult(verdict: .similarLocal, fields: FlatJson.read(s.record.manifest), keyId: s.record.keyId, watermark: wm, changed: s.changed, fromLocalCopy: true)
        }
        return VerifyResult(verdict: .unknown, fields: [:], keyId: nil, watermark: wm, changed: nil, fromLocalCopy: false)
    }
}

// MARK: - 見える QR(簡易確認用)

struct QrInfo {
    let valid: Bool
    let shotId: String
    let timeJst: String
    let lat: Double?
    let lon: Double?
    let alt: Double?
    let keyId: String
}

enum B64Url {
    static func enc(_ b: [UInt8]) -> String {
        Data(b).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    static func dec(_ s: String) -> [UInt8]? {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        return Data(base64Encoded: t).map { [UInt8]($0) }
    }
}

/// QR の中身(Android 版と同じ v2): MK2|撮影ID|撮影時刻(秒, 36進)|緯度|経度|標高|公開鍵(圧縮33バイト)|署名(64バイト)
/// 署名は「署名」より前の文字列全体に対するもの。QR は複製できるので、写真本体の撮影ID との一致を別に確かめること。
enum QrPayload {
    static func build(shotId: [UInt8], timeMs: Int64, lat: Double?, lon: Double?, alt: Double?, sign: ([UInt8]) throws -> [UInt8], publicKey: [UInt8]) throws -> String {
        let pub = try P256.Signing.PublicKey(x963Representation: Data(publicKey))
        let head = [
            "MK2", B64Url.enc(shotId), String(timeMs / 1000, radix: 36),
            lat.map { String(format: "%.5f", $0) } ?? "",
            lon.map { String(format: "%.5f", $0) } ?? "",
            alt.map { String(format: "%.0f", $0) } ?? "",
            B64Url.enc([UInt8](pub.compressedRepresentation))
        ].joined(separator: "|")
        let der = try sign(Array(head.utf8))
        let raw = try P256.Signing.ECDSASignature(derRepresentation: Data(der)).rawRepresentation
        return head + "|" + B64Url.enc([UInt8](raw))
    }

    /// マコトカメラの QR でなければ nil
    static func parse(_ text: String) -> QrInfo? {
        let p = text.components(separatedBy: "|")
        guard p.count == 8, let cut = text.range(of: "|", options: .backwards) else { return nil }
        let head = Array(text[text.startIndex..<cut.lowerBound].utf8)
        switch p[0] {
        case "MK2":
            guard let id = B64Url.dec(p[1]), let sec = Int64(p[2], radix: 36),
                  let pc = B64Url.dec(p[6]), let sr = B64Url.dec(p[7]),
                  let pub = try? P256.Signing.PublicKey(compressedRepresentation: Data(pc)),
                  let sig = try? P256.Signing.ECDSASignature(rawRepresentation: Data(sr)) else { return nil }
            let rec = Record(manifest: head, signature: [UInt8](sig.derRepresentation), publicKey: [UInt8](pub.x963Representation))
            let time = Format.jst(Date(timeIntervalSince1970: TimeInterval(sec)), "yyyy-MM-dd'T'HH:mm:ss") + "+09:00"
            return QrInfo(valid: rec.signatureValid(), shotId: toHex(id), timeJst: time, lat: Double(p[3]), lon: Double(p[4]), alt: Double(p[5]), keyId: rec.keyId)
        case "MK1":
            guard let pk = B64Url.dec(p[6]), let sg = B64Url.dec(p[7]) else { return nil }
            let rec = Record(manifest: head, signature: sg, publicKey: pk)
            return QrInfo(valid: rec.signatureValid(), shotId: p[1], timeJst: p[2], lat: Double(p[3]), lon: Double(p[4]), alt: Double(p[5]), keyId: rec.keyId)
        default:
            return nil
        }
    }
}
