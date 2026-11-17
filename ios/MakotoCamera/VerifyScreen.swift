import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import CoreImage

/// 他のアプリから渡された写真(「共有」や「このアプリで開く」)
struct IncomingFile: Equatable {
    let id = UUID()
    let data: Data
}

private struct Checked {
    let result: VerifyResult
    let preview: UIImage?
    let ownKeyId: String?
    let qr: QrInfo?
}

/// 署名した端末をどこまで信頼できるか
private enum Trust { case own, registered, unregistered }

/// 画像から QR を探す(見つかった文字列を順に返す)
private func readQrs(_ img: CGImage) -> [String] {
    let det = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
    return (det?.features(in: CIImage(cgImage: img)) ?? []).compactMap { ($0 as? CIQRCodeFeature)?.messageString }
}

private func inspect(_ data: Data) -> Checked {
    let file = [UInt8](data)
    var analyzer: ImageAnalyzer?
    var qr: QrInfo?
    if let buf = try? PixelBuffer.raw(from: data) {
        let a = ImageAnalyzer(w: buf.width, h: buf.height)
        a.feed(buf.pixels, bytesPerRow: buf.bytesPerRow)
        analyzer = a
        // QR は下端の帯の右側にあるので、その周辺を元の解像度のまま読む
        if let full = buf.context.makeImage() {
            let rect = CGRect(x: full.width * 4 / 10, y: full.height * 5 / 10, width: full.width - full.width * 4 / 10, height: full.height - full.height * 5 / 10)
            let texts = (full.cropping(to: rect).map { readQrs($0) } ?? []) + readQrs(full)
            qr = texts.lazy.compactMap { QrPayload.parse($0) }.first
        }
    }
    let result = Verifier.verify(file: file, analyzer: analyzer, localRecord: { RecordStore.load(idHex: $0) }, allLocal: { RecordStore.all() })
    return Checked(result: result, preview: UIImage(data: data), ownKeyId: ownDeviceId(), qr: qr)
}

struct VerifyScreen: View {
    let incoming: IncomingFile?
    let onBack: () -> Void
    let onOpenDevices: () -> Void
    @State private var item: PhotosPickerItem?
    @State private var importing = false
    @State private var busy = false
    @State private var checked: Checked?
    @State private var error: String?

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Chip(label: "戻る", action: onBack)
                Text("写真の判定").font(.system(size: 16, weight: .semibold)).foregroundColor(Palette.text).padding(.leading, 4)
                Spacer()
                // 写真アプリから選ぶ。変換せず、元のファイルのまま受け取る
                PhotosPicker(selection: $item, matching: .images, preferredItemEncoding: .current) {
                    Text("写真から").font(.system(size: 13, weight: .semibold)).foregroundColor(Palette.text)
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .background(Palette.accent).clipShape(RoundedRectangle(cornerRadius: 6))
                }
                .disabled(busy)
                Chip(label: "ファイルから", enabled: !busy) { importing = true }
            }
            .padding(.horizontal, 16).padding(.vertical, 10)

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if busy {
                        HStack(spacing: 12) { ProgressView().tint(Palette.accent); Text("判定中").font(.system(size: 14)).foregroundColor(Palette.text) }
                    } else if let e = error {
                        Text(e).font(.system(size: 14)).foregroundColor(Palette.bad)
                    } else if let c = checked {
                        ResultView(c: c, onOpenDevices: onOpenDevices)
                    } else {
                        Text("写真を選ぶと、このアプリで撮影されたままかどうかを調べます。\n\nメールの添付やファイルアプリの写真は、「共有」から「マコトカメラ」を選んでも調べられます。\n\nLINE の通常の写真送信や SNS では再圧縮されるため、原本とは判定されません。原本を渡すときは、メールの添付などファイルとして送ってください。")
                            .font(.system(size: 14)).foregroundColor(Palette.sub).lineSpacing(6)
                        Chip(label: "端末の登録と、この端末の ID", action: onOpenDevices)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
            }
        }
        .background(Palette.ink)
        .onChange(of: item) { newItem in
            guard let newItem = newItem else { return }
            run { try await newItem.loadTransferable(type: Data.self) }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.jpeg, .image]) { result in
            guard case .success(let url) = result else { return }
            run {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                return try Data(contentsOf: url)
            }
        }
        // 他のアプリから渡された写真は、すぐに判定する
        .task(id: incoming?.id) {
            if let f = incoming { let d = f.data; run { d } }
        }
    }

    private func run(_ load: @escaping () async throws -> Data?) {
        busy = true; error = nil; checked = nil
        Task.detached(priority: .userInitiated) {
            do {
                guard let data = try await load() else { throw PipelineError.message("データがありません") }
                let c = inspect(data)
                await MainActor.run { checked = c; busy = false }
            } catch {
                await MainActor.run { self.error = "読み込めませんでした: \(error.localizedDescription)"; busy = false }
            }
        }
    }
}

private struct DetailRow: Identifiable {
    let id: String      // 項目名
    let value: String
}

/// 変わった場所を赤く塗る(32x32 マス)
private struct ChangeMap: View {
    let changed: [Bool]

    var body: some View {
        Canvas { ctx, size in
            let cw = size.width / CGFloat(Spec.map)
            let ch = size.height / CGFloat(Spec.map)
            for i in 0..<changed.count where changed[i] {
                let rect = CGRect(x: CGFloat(i % Spec.map) * cw, y: CGFloat(i / Spec.map) * ch, width: cw, height: ch)
                ctx.fill(Path(rect), with: .color(Color(red: 0.9, green: 0.28, blue: 0.3).opacity(0.53)))
            }
        }
        .allowsHitTesting(false)
    }
}

/// 左端に色の線がある、短い知らせ
private struct Notice: View {
    let color: Color
    let text: String

    var body: some View {
        HStack(spacing: 0) {
            color.frame(width: 4)
            Text(text).font(.system(size: 13)).foregroundColor(Palette.text).lineSpacing(4).padding(12)
            Spacer(minLength: 0)
        }
        .fixedSize(horizontal: false, vertical: true)
        .background(Palette.panel).clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

private struct ResultView: View {
    let c: Checked
    let onOpenDevices: () -> Void
    @State private var version = 0
    @State private var newName = ""

    private var trustedName: String? { _ = version; return TrustStore.name(for: c.result.keyId) }

    private var trust: Trust {
        if let k = c.result.keyId, k == c.ownKeyId { return .own }
        return trustedName != nil ? .registered : .unregistered
    }

    private var summary: (Color, String, String) {
        let r = c.result
        switch r.verdict {
        case .original:
            switch trust {
            case .own: return (Palette.ok, "原本です", "この端末で撮影されたままです。画像は 1 画素も変わっていません。")
            case .registered: return (Palette.ok, "原本です", "登録済みの端末「\(trustedName ?? "")」で撮影されたままです。画像は 1 画素も変わっていません。")
            case .unregistered: return (Palette.warn, "原本ですが、未登録の端末です", "撮影後に変わっていないことは確認できました。ただし、署名した端末が登録されていないため、誰の端末で撮ったものかは確認できていません。")
            }
        case .edited:
            return (Palette.bad, "撮影後に変更されています",
                    r.changedCount > 0 ? "署名付きの記録は本物ですが、画像が撮影時と違います。赤い部分が変わった場所です。"
                                       : "署名付きの記録は本物ですが、画像が撮影時と違います。見た目の変化は小さく、再保存や再圧縮の可能性があります。")
        case .forgedRecord:
            return (Palette.bad, "記録が壊れているか、偽造されています", "記録はありますが、署名が合いません。")
        case .watermarkOnly:
            return (Palette.warn, "このアプリの写真ですが、原本ではありません",
                    "透かしから撮影ID \(r.watermark?.idHex ?? "") を読み取りました。再保存、SNS 送信、編集のいずれかを経ています。"
                    + (r.fromLocalCopy ? "この端末に残っている控えと照合しました。" : "この端末に控えがないため、撮影時の記録は表示できません。")
                    + (r.changedCount > 0 ? "赤い部分が変わった場所です。" : ""))
        case .similarLocal:
            return (Palette.bad, "この端末で撮った写真を加工したものとみられます",
                    "記録も透かしも消えていますが、この端末の控えにある撮影ID \(r.fields["shotId"] ?? "") の写真と構図が一致します。"
                    + (r.changedCount > 0 ? "赤い部分が元の写真と違う場所です。" : "") + "下は元の写真の記録です。")
        case .unknown:
            return (Palette.bad, "原本とは認められません", "このアプリの記録も透かしも見つかりません。他のカメラの写真か、加工で記録と透かしが消えた写真です。撮影時のままであることは確認できません。")
        }
    }

    /// 見える QR があれば、写真本体(記録または透かし)の撮影ID と一致するか
    private var qrNotice: (Color, String)? {
        guard let q = c.qr else { return nil }
        let r = c.result
        let bodyId = r.fields["shotId"] ?? ((r.watermark?.found ?? false) ? r.watermark?.idHex : nil)
        if !q.valid { return (Palette.bad, "QR の署名が合いません。QR が偽造されているか、壊れています。") }
        guard let b = bodyId else { return (Palette.bad, "写真に QR(撮影ID \(q.shotId))がありますが、写真本体からは撮影ID を読めません。QR が他の写真から複製された可能性があります。") }
        if b != q.shotId { return (Palette.bad, "QR は他の写真から複製されたものです。QR の撮影ID(\(q.shotId))が、写真本体の撮影ID(\(b))と一致しません。") }
        return (Palette.ok, "QR の撮影ID は、写真本体の撮影ID と一致しています。")
    }

    var body: some View {
        let s = summary
        VStack(alignment: .leading, spacing: 0) {
            s.0.frame(height: 4)
            VStack(alignment: .leading, spacing: 6) {
                Text(s.1).font(.system(size: 18, weight: .semibold)).foregroundColor(Palette.text)
                Text(s.2).font(.system(size: 13)).foregroundColor(Palette.sub).lineSpacing(4)
            }
            .padding(16)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.panel).clipShape(RoundedRectangle(cornerRadius: 8))

        if let p = c.preview {
            Image(uiImage: p).resizable().aspectRatio(contentMode: .fit)
                .overlay(ChangeMap(changed: c.result.changedCount > 0 ? (c.result.changed ?? []) : []))
                .clipShape(RoundedRectangle(cornerRadius: 8))
        }

        if let n = qrNotice { Notice(color: n.0, text: n.1) }

        // 未登録の端末の署名なら、その場で登録できるようにする
        if trust == .unregistered, let keyId = c.result.keyId, !c.result.fromLocalCopy, c.result.verdict != .forgedRecord {
            VStack(alignment: .leading, spacing: 8) {
                Text("この端末を登録する").font(.system(size: 14, weight: .semibold)).foregroundColor(Palette.text)
                Text("端末ID \(prettyId(keyId))\n相手から聞いた ID と同じであることを確かめてから登録してください。")
                    .font(.system(size: 12)).foregroundColor(Palette.sub).lineSpacing(3)
                TextField("名前(例: 田中さん)", text: $newName)
                    .textFieldStyle(.roundedBorder)
                HStack(spacing: 8) {
                    Chip(label: "登録する", selected: true) { TrustStore.add(keyId, name: newName); newName = ""; version += 1 }
                    Chip(label: "登録済みの一覧", action: onOpenDevices)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.panel).clipShape(RoundedRectangle(cornerRadius: 8))
        }

        let rows = detailRows
        if !rows.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text(c.result.fromLocalCopy ? "元の写真の記録(この端末の控え)" : "撮影時の記録(署名付き)")
                    .font(.system(size: 14, weight: .semibold)).foregroundColor(Palette.text)
                ForEach(rows) { row in
                    HStack(alignment: .top) {
                        Text(row.id).font(.system(size: 13)).foregroundColor(Palette.sub).frame(width: 96, alignment: .leading)
                        Text(row.value).font(.system(size: 13)).foregroundColor(Palette.text)
                        Spacer(minLength: 0)
                    }
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.panel).clipShape(RoundedRectangle(cornerRadius: 8))

            Text("注意: 未登録の端末の署名は、正規のアプリのものかどうかを確認できません。相手の端末ID を登録して使ってください。時刻は撮影した端末の時計、位置は端末の測位結果です。")
                .font(.system(size: 12)).foregroundColor(Palette.sub).lineSpacing(4)
        }
    }

    private var detailRows: [DetailRow] {
        let r = c.result
        let f = r.fields
        if f.isEmpty { return [] }
        var out = [DetailRow]()
        if let t = f["timeJst"] {
            out.append(DetailRow(id: "撮影日時", value: t.replacingOccurrences(of: "T", with: " ").replacingOccurrences(of: "+09:00", with: "") + "(日本時間)"))
        }
        if let lat = f["lat"].flatMap({ Double($0) }), let lon = f["lon"].flatMap({ Double($0) }) {
            let acc = f["acc"].flatMap { Double($0) }.map { String(format: "(誤差 約%.0f m)", $0) } ?? ""
            out.append(DetailRow(id: "場所", value: Format.latLon(lat, lon) + acc))
        } else {
            out.append(DetailRow(id: "場所", value: "記録なし"))
        }
        if let a = f["alt"].flatMap({ Double($0) }) {
            out.append(DetailRow(id: "標高", value: String(format: "%.1f m", a) + (f["altType"] == "ellipsoid" ? "(楕円体高)" : "")))
        }
        if f["mock"] == "true" { out.append(DetailRow(id: "位置の偽装", value: "疑似位置情報が使われていました")) }
        if let d = f["device"] { out.append(DetailRow(id: "機種", value: d + (f["os"].map { " / " + $0 } ?? ""))) }
        if let a = f["app"] { out.append(DetailRow(id: "アプリ", value: a + " " + (f["appVersion"] ?? ""))) }
        let lens = f["lens"] == "front" ? "内カメラ" : "外カメラ"
        let flash = f["flash"] == "auto" ? "自動" : (f["flash"] == "on" ? "入" : "切")
        let zoom = f["zoom"].flatMap { Double($0) }.map { String(format: "%.1fx", $0) } ?? "-"
        let ev = f["ev"].flatMap { Double($0) }.map { String(format: "%+.1f", $0) } ?? "-"
        out.append(DetailRow(id: "撮影設定", value: "\(lens) / ズーム \(zoom) / 明るさ \(ev) / フラッシュ \(flash)"))
        if let ratio = f["ratio"] { out.append(DetailRow(id: "比率", value: ratio)) }
        out.append(DetailRow(id: "画面の表示", value: f["stamp"] == "true" ? (f["qr"] == "true" ? "あり(QR 付き)" : "あり") : "なし"))
        if let id = f["shotId"] { out.append(DetailRow(id: "撮影ID", value: id)) }
        if let k = r.keyId {
            let who = k == c.ownKeyId ? "この端末" : (trustedName.map { "登録済み: " + $0 } ?? "未登録の端末")
            out.append(DetailRow(id: "署名した端末", value: prettyId(k) + "(\(who))"))
        }
        return out
    }
}
