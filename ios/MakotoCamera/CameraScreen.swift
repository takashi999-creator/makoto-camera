import SwiftUI
import UIKit

struct CameraScreen: View {
    let onOpenVerify: () -> Void
    @StateObject private var camera = CameraModel()
    @StateObject private var tracker = LocationTracker()
    @State private var pinchStart: CGFloat?
    @State private var now = Date()
    private let clock = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 0) {
            // 上段: アプリ名と設定
            HStack(spacing: 8) {
                Text("マコトカメラ").font(.system(size: 16, weight: .semibold)).foregroundColor(Palette.text)
                Spacer()
                Chip(label: "フラッシュ " + (camera.flash == "auto" ? "自動" : (camera.flash == "on" ? "入" : "切")),
                     selected: camera.flash != "off", enabled: !camera.front) { camera.cycleFlash() }
                Chip(label: camera.stamp ? "表示 入" : "表示 切", selected: camera.stamp) {
                    camera.stamp.toggle()
                    if !camera.stamp { camera.qr = false }
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 10)

            // 位置の状態
            HStack {
                Text(locationStatus).font(.system(size: 12)).foregroundColor(tracker.last != nil ? Palette.sub : Palette.warn)
                Spacer()
                // 直前に撮った写真を、ファイルのまま(原本のまま)他のアプリへ渡す
                if let url = camera.lastShot {
                    ShareLink(item: url) {
                        Text("直前の写真を送る").font(.system(size: 13)).foregroundColor(Palette.text)
                            .padding(.horizontal, 12).padding(.vertical, 8)
                            .overlay(RoundedRectangle(cornerRadius: 6).stroke(Palette.line, lineWidth: 1))
                    }
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 8)

            // プレビュー
            ZStack {
                Color.black
              ZStack(alignment: .bottomLeading) {
                Color.black
                if camera.authorized {
                    CameraPreview(session: camera.session)
                        .gesture(MagnificationGesture()
                            .onChanged { v in
                                if pinchStart == nil { pinchStart = camera.zoom }
                                camera.setZoom((pinchStart ?? 1) * v)
                            }
                            .onEnded { _ in pinchStart = nil })
                } else {
                    Text("設定アプリでこのアプリにカメラを許可してください。")
                        .font(.system(size: 14)).foregroundColor(Palette.sub).padding(32)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                if camera.stamp {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 8) {
                            Text("マコトカメラ").font(.system(size: 12, weight: .semibold))
                            Text("AI編集不可").font(.system(size: 11, weight: .semibold))
                                .padding(.horizontal, 5).padding(.vertical, 1)
                                .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color.white, lineWidth: 1))
                        }
                        ForEach(Stamp.lines(now, tracker.last), id: \.self) { Text($0).font(.system(size: 11)) }
                        if camera.qr { Text("QR 付き(右下に入ります)").font(.system(size: 11)) }
                    }
                    .foregroundColor(.white)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Palette.ink.opacity(0.6))
                }
                if camera.busy {
                    HStack(spacing: 12) {
                        ProgressView().tint(Palette.accent)
                        Text("署名と透かしを記録中").font(.system(size: 13)).foregroundColor(Palette.text)
                    }
                    .padding(16)
                    .background(Palette.ink.opacity(0.8))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
              }
              .aspectRatio(previewRatio, contentMode: .fit)
              .clipped()
              .overlay(alignment: .top) { if camera.scanning { scanPanel } }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            // QR
            HStack(spacing: 8) {
                Text("QR").font(.system(size: 12)).foregroundColor(Palette.sub).frame(width: 48, alignment: .leading)
                Chip(label: camera.qr ? "写真に入れる 入" : "写真に入れる 切", selected: camera.qr, enabled: !camera.busy) {
                    camera.qr.toggle()
                    if camera.qr { camera.stamp = true }
                }
                Chip(label: camera.scanning ? "簡易確認 中(押すと終了)" : "かざして簡易確認", selected: camera.scanning) { camera.toggleScan() }
                Spacer()
            }
            .padding(.horizontal, 16).padding(.top, 8)

            // 比率
            HStack(spacing: 8) {
                Text("比率").font(.system(size: 12)).foregroundColor(Palette.sub).frame(width: 48, alignment: .leading)
                ForEach(["4:3", "16:9", "1:1"], id: \.self) { r in
                    Chip(label: r, selected: camera.ratio == r, enabled: !camera.busy) { camera.ratio = r }
                }
                Spacer()
            }
            .padding(.horizontal, 16).padding(.top, 6)

            // 明るさ
            HStack {
                Text("明るさ").font(.system(size: 12)).foregroundColor(Palette.sub).frame(width: 48, alignment: .leading)
                Slider(value: Binding(get: { camera.ev }, set: { camera.setEv($0) }), in: -2...2, step: 0.1).tint(Palette.accent)
                Text(String(format: "%+.1f", camera.ev)).font(.system(size: 12)).foregroundColor(Palette.text).frame(width: 40, alignment: .trailing)
            }
            .padding(.horizontal, 16).padding(.top, 8)

            // ズーム(1 未満は広角レンズ)
            HStack(spacing: 8) {
                ForEach(zoomPresets, id: \.self) { z in
                    Chip(label: z < 0.95 ? String(format: "広角 %.1fx", Double(z)) : String(format: "%.0fx", Double(z)),
                         selected: abs(camera.zoom - z) < 0.05) { camera.setZoom(z) }
                }
                Text(String(format: "現在 %.1fx", Double(camera.zoom))).font(.system(size: 12)).foregroundColor(Palette.sub)
            }
            .padding(.horizontal, 16).padding(.top, 4)

            // 下段: 判定、シャッター、カメラ切替
            HStack {
                HStack { Chip(label: "写真を判定", action: onOpenVerify); Spacer() }.frame(maxWidth: .infinity)
                Button(action: { camera.shoot(location: tracker.last) }) {
                    ZStack {
                        Circle().stroke(Palette.text, lineWidth: 3).frame(width: 72, height: 72)
                        Circle().fill(camera.busy ? Palette.sub : Palette.text).frame(width: 56, height: 56)
                    }
                }
                .buttonStyle(.plain)
                .disabled(camera.busy || !camera.authorized)
                HStack { Spacer(); Chip(label: camera.front ? "外カメラへ" : "内カメラへ") { camera.switchCamera() } }.frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 24).padding(.vertical, 16)
        }
        .background(Palette.ink)
        .overlay(alignment: .top) {
            if let m = camera.message {
                Text(m).font(.system(size: 13)).foregroundColor(Palette.text)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(Palette.panel).clipShape(RoundedRectangle(cornerRadius: 8))
                    .padding(.top, 56)
                    .onAppear { DispatchQueue.main.asyncAfter(deadline: .now() + 3) { if camera.message == m { camera.message = nil } } }
                    .id(m)
            }
        }
        .onAppear {
            UIDevice.current.beginGeneratingDeviceOrientationNotifications()
            camera.start()
            tracker.start()
        }
        .onDisappear {
            if camera.scanning { camera.stopScan() }
            camera.stop()
            tracker.stop()
        }
        .onReceive(clock) { now = $0 }
    }

    /// プレビューの縦横比(縦持ちなので 幅 ÷ 高さ)
    private var previewRatio: CGFloat {
        camera.ratio == "16:9" ? 9.0 / 16.0 : (camera.ratio == "1:1" ? 1 : 3.0 / 4.0)
    }

    /// QR の簡易確認の結果
    @ViewBuilder private var scanPanel: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let s = camera.scanned {
                let own = ownDeviceId()
                let name = TrustStore.name(for: s.keyId)
                let isOwn = own != nil && own == s.keyId
                let good = s.valid && (name != nil || isOwn)
                Text(!s.valid ? "QR の署名が合いません(偽造または破損)" : (good ? "署名を確認しました" : "署名は正しいですが、未登録の端末です"))
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(!s.valid ? Palette.bad : (good ? Palette.ok : Palette.warn))
                if s.valid {
                    Text("撮影日時  " + s.timeJst.replacingOccurrences(of: "T", with: " ").replacingOccurrences(of: "+09:00", with: "") + "(日本時間)")
                    if let la = s.lat, let lo = s.lon { Text("場所  " + Format.latLon(la, lo)) } else { Text("場所  記録なし") }
                    if let a = s.alt { Text(String(format: "標高  %.1f m", a)) }
                    Text("端末  " + prettyId(s.keyId) + "(" + (isOwn ? "この端末" : (name.map { "登録済み: " + $0 } ?? "未登録の端末")) + ")")
                    Text("撮影ID  " + s.shotId)
                    Text("簡易確認です。QR は複製できるため、写真が原本かどうかはファイルを「写真を判定」で確認してください。")
                        .font(.system(size: 11)).foregroundColor(Palette.sub)
                }
            } else {
                Text("QR の簡易確認").font(.system(size: 14, weight: .semibold))
                Text("マコトカメラの写真に入っている QR にかざしてください。").foregroundColor(Palette.sub)
                if let l = camera.autoEvLabel { Text("明るさを自動で調整しながら探しています(\(l))").foregroundColor(Palette.sub) }
            }
        }
        .font(.system(size: 12))
        .foregroundColor(Palette.text)
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.panel.opacity(0.92))
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .padding(8)
    }

    private var locationStatus: String {
        if tracker.denied { return "位置情報: 許可なし(位置なしで記録します)" }
        guard let l = tracker.last else { return "位置情報: 取得中" }
        return String(format: "位置情報: 取得済み(誤差 約%.0f m)", l.horizontalAccuracy)
    }

    private var zoomPresets: [CGFloat] {
        var out = [CGFloat]()
        if camera.minZoom < 0.95 { out.append(camera.minZoom) }
        for z in [CGFloat(1), 2, 5, 10] where z <= camera.maxZoom + 0.01 { out.append(z) }
        return out
    }
}
