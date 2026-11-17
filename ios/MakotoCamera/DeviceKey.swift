import Foundation
import CryptoKit
import Security

/// 端末ごとの署名鍵。秘密鍵は Secure Enclave の中で作られ、アプリからも取り出せない。
/// (シミュレータなど Secure Enclave がない環境では、通常の鍵をキーチェーンに置く)
enum DeviceKey {
    private static let account = "makoto_device_key_v1"
    private static let lock = NSLock()

    private enum Key {
        case enclave(SecureEnclave.P256.Signing.PrivateKey)
        case software(P256.Signing.PrivateKey)
    }

    private static var cached: Key?

    private static func load() throws -> Key {
        lock.lock(); defer { lock.unlock() }
        if let k = cached { return k }
        let key: Key
        if SecureEnclave.isAvailable {
            if let blob = keychainRead(account + "_se"), let k = try? SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: blob) {
                key = .enclave(k)
            } else {
                let k = try SecureEnclave.P256.Signing.PrivateKey()
                keychainWrite(account + "_se", k.dataRepresentation)
                key = .enclave(k)
            }
        } else {
            if let raw = keychainRead(account + "_sw"), let k = try? P256.Signing.PrivateKey(rawRepresentation: raw) {
                key = .software(k)
            } else {
                let k = P256.Signing.PrivateKey()
                keychainWrite(account + "_sw", k.rawRepresentation)
                key = .software(k)
            }
        }
        cached = key
        return key
    }

    /// 公開鍵(65 バイト: 04 || X || Y)
    static func publicKey() throws -> [UInt8] {
        switch try load() {
        case .enclave(let k): return Array(k.publicKey.x963Representation)
        case .software(let k): return Array(k.publicKey.x963Representation)
        }
    }

    /// ECDSA P-256 / SHA-256 / DER 形式
    static func sign(_ data: [UInt8]) throws -> [UInt8] {
        switch try load() {
        case .enclave(let k): return Array(try k.signature(for: Data(data)).derRepresentation)
        case .software(let k): return Array(try k.signature(for: Data(data)).derRepresentation)
        }
    }

    private static func keychainRead(_ account: String) -> Data? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "app.makoto.camera",
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var out: CFTypeRef?
        return SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess ? out as? Data : nil
    }

    private static func keychainWrite(_ account: String, _ data: Data) {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "app.makoto.camera",
            kSecAttrAccount as String: account
        ]
        SecItemDelete(q as CFDictionary)
        var add = q
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }
}

/// 撮影した写真の記録の控え。記録が消された写真でも、撮影IDから照合できるようにする。
enum RecordStore {
    private static var dir: URL {
        let d = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("records", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    static func save(idHex: String, packed: [UInt8]) {
        try? Data(packed).write(to: dir.appendingPathComponent(idHex + ".bin"))
    }

    /// すべての控え(新しい順)
    static func all() -> [[UInt8]] {
        let fm = FileManager.default
        guard let urls = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey]) else { return [] }
        let dated = urls.filter { $0.pathExtension == "bin" }.map { u -> (URL, Date) in
            (u, (try? u.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast)
        }
        return dated.sorted { $0.1 > $1.1 }.compactMap { try? Data(contentsOf: $0.0) }.map { [UInt8]($0) }
    }

    static func load(idHex: String) -> [UInt8]? {
        guard !idHex.isEmpty, idHex.count <= 32, idHex.allSatisfy({ $0.isHexDigit }) else { return nil }
        guard let d = try? Data(contentsOf: dir.appendingPathComponent(idHex + ".bin")) else { return nil }
        return Array(d)
    }
}

/// この端末の鍵ID(公開鍵の SHA-256 の先頭 8 バイト)
func ownDeviceId() -> String? {
    (try? DeviceKey.publicKey()).map { toHex(Array(sha256($0)[0..<8])) }
}

/// 読みやすいように 4 桁ごとに区切る
func prettyId(_ id: String) -> String {
    stride(from: 0, to: id.count, by: 4).map { i -> String in
        let a = id.index(id.startIndex, offsetBy: i)
        let b = id.index(a, offsetBy: min(4, id.count - i))
        return String(id[a..<b])
    }.joined(separator: " ")
}

/// 信頼する端末の一覧(端末ID → 名前)。この端末の中だけに保存する。
enum TrustStore {
    private static let key = "trusted_devices"

    /// 入力された端末IDを整える。16 桁の 16 進数でなければ nil。
    static func normalize(_ id: String) -> String? {
        let s = id.trimmingCharacters(in: .whitespaces).lowercased().replacingOccurrences(of: " ", with: "").replacingOccurrences(of: "-", with: "")
        return (s.count == 16 && s.allSatisfy { $0.isHexDigit }) ? s : nil
    }

    private static var map: [String: String] {
        get { UserDefaults.standard.dictionary(forKey: key) as? [String: String] ?? [:] }
        set { UserDefaults.standard.set(newValue, forKey: key) }
    }

    static func all() -> [(id: String, name: String)] { map.map { ($0.key, $0.value) }.sorted { $0.name < $1.name } }
    static func name(for id: String?) -> String? { id.flatMap { map[$0] } }
    static func add(_ id: String, name: String) {
        let n = name.trimmingCharacters(in: .whitespaces)
        map[id] = n.isEmpty ? "名前なし" : n
    }
    static func remove(_ id: String) { map[id] = nil }
}
