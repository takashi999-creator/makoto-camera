import SwiftUI
import UIKit

/// 端末の登録: この端末の ID を相手に伝え、相手の端末の ID に名前を付けて登録する
struct DevicesScreen: View {
    let onBack: () -> Void
    @State private var name = ""
    @State private var id = ""
    @State private var note: String?
    @State private var version = 0
    private let own = ownDeviceId()

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Chip(label: "戻る", action: onBack)
                Text("端末の登録").font(.system(size: 16, weight: .semibold)).foregroundColor(Palette.text).padding(.leading, 4)
                Spacer()
            }
            .padding(.horizontal, 16).padding(.vertical, 10)

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    // この端末の ID
                    VStack(alignment: .leading, spacing: 8) {
                        Text("この端末のID").font(.system(size: 14, weight: .semibold))
                        Text(own.map { prettyId($0) } ?? "取得できません").font(.system(size: 20, design: .monospaced))
                        Text("写真を確認する相手にこの ID を伝え、登録してもらってください。登録された端末の写真だけが「登録済み」と表示されます。")
                            .font(.system(size: 12)).foregroundColor(Palette.sub)
                        if let own = own {
                            HStack(spacing: 8) {
                                Chip(label: "コピー") { UIPasteboard.general.string = own; note = "ID をコピーしました" }
                                ShareLink(item: "マコトカメラの端末ID: \(own)") {
                                    Text("送る").font(.system(size: 13)).foregroundColor(Palette.text)
                                        .padding(.horizontal, 12).padding(.vertical, 8)
                                        .overlay(RoundedRectangle(cornerRadius: 6).stroke(Palette.line, lineWidth: 1))
                                }
                            }
                        }
                    }
                    .panel()

                    // 登録
                    VStack(alignment: .leading, spacing: 8) {
                        Text("相手の端末を登録").font(.system(size: 14, weight: .semibold))
                        TextField("名前(例: 田中さん)", text: $name).textFieldStyle(.roundedBorder)
                        TextField("端末ID(16 桁)", text: $id).textFieldStyle(.roundedBorder)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                        Chip(label: "登録する", selected: true) {
                            // 「マコトカメラの端末ID: xxxx」をそのまま貼り付けても読めるようにする
                            let raw = id.components(separatedBy: ":").last ?? id
                            if let norm = TrustStore.normalize(raw) {
                                TrustStore.add(norm, name: name)
                                name = ""; id = ""; version += 1
                                note = "登録しました"
                            } else {
                                note = "端末ID は 16 桁の英数字(0-9, a-f)です"
                            }
                        }
                        if let n = note { Text(n).font(.system(size: 12)).foregroundColor(Palette.sub) }
                    }
                    .panel()

                    // 一覧
                    VStack(alignment: .leading, spacing: 8) {
                        Text("登録済みの端末").font(.system(size: 14, weight: .semibold))
                        let devices = { _ = version; return TrustStore.all() }()
                        if devices.isEmpty { Text("まだありません").font(.system(size: 13)).foregroundColor(Palette.sub) }
                        ForEach(devices, id: \.id) { d in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(d.name).font(.system(size: 14))
                                    Text(prettyId(d.id)).font(.system(size: 12, design: .monospaced)).foregroundColor(Palette.sub)
                                }
                                Spacer()
                                Chip(label: "削除") { TrustStore.remove(d.id); version += 1 }
                            }
                        }
                    }
                    .panel()
                }
                .foregroundColor(Palette.text)
                .padding(16)
            }
        }
        .background(Palette.ink)
    }
}

private extension View {
    func panel() -> some View {
        self.padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.panel)
            .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}
