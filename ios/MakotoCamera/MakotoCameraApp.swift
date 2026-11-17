import SwiftUI

@main
struct MakotoCameraApp: App {
    var body: some Scene {
        WindowGroup {
            RootView().preferredColorScheme(.dark)
        }
    }
}

struct RootView: View {
    @State private var verifying = false
    @State private var devices = false
    @State private var incoming: IncomingFile?

    var body: some View {
        ZStack {
            Palette.ink.ignoresSafeArea()
            if devices {
                DevicesScreen(onBack: { devices = false })
            } else if verifying {
                VerifyScreen(incoming: incoming, onBack: { verifying = false; incoming = nil }, onOpenDevices: { devices = true })
            } else {
                CameraScreen(onOpenVerify: { verifying = true })
            }
        }
        // 他のアプリの「共有」や「このアプリで開く」から写真を受け取って判定する
        .onOpenURL { url in
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url) else { return }
            // 受け取り用にコピーされたファイル(Inbox)は、読んだら消す
            if url.path.contains("/Inbox/") { try? FileManager.default.removeItem(at: url) }
            incoming = IncomingFile(data: data)
            devices = false
            verifying = true
        }
    }
}

enum Palette {
    static let ink = Color(red: 0x0E / 255.0, green: 0x14 / 255.0, blue: 0x1B / 255.0)
    static let panel = Color(red: 0x18 / 255.0, green: 0x21 / 255.0, blue: 0x2B / 255.0)
    static let line = Color(red: 0x2A / 255.0, green: 0x36 / 255.0, blue: 0x44 / 255.0)
    static let text = Color(red: 0xE8 / 255.0, green: 0xED / 255.0, blue: 0xF2 / 255.0)
    static let sub = Color(red: 0x93 / 255.0, green: 0xA1 / 255.0, blue: 0xB0 / 255.0)
    static let accent = Color(red: 0x2F / 255.0, green: 0x80 / 255.0, blue: 0xC2 / 255.0)
    static let ok = Color(red: 0x2E / 255.0, green: 0x9E / 255.0, blue: 0x6B / 255.0)
    static let warn = Color(red: 0xD0 / 255.0, green: 0x8A / 255.0, blue: 0x1E / 255.0)
    static let bad = Color(red: 0xC8 / 255.0, green: 0x47 / 255.0, blue: 0x3F / 255.0)
}

/// 角の小さい、枠線だけのボタン。選択中は塗りつぶす。
struct Chip: View {
    let label: String
    var selected = false
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 13, weight: selected ? .semibold : .regular))
                .foregroundColor(enabled ? Palette.text : Palette.sub)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(selected ? Palette.accent : Color.clear)
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(selected ? Palette.accent : Palette.line, lineWidth: 1))
                .clipShape(RoundedRectangle(cornerRadius: 6))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}
