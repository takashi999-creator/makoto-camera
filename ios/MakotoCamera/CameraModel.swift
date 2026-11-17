import AVFoundation
import CoreLocation
import SwiftUI
import UIKit

/// カメラの制御(ズーム、広角、明るさ、フラッシュ、内外切替、撮影)。
final class CameraModel: NSObject, ObservableObject, AVCapturePhotoCaptureDelegate, AVCaptureMetadataOutputObjectsDelegate {
    let session = AVCaptureSession()
    private let output = AVCapturePhotoOutput()
    private let metadata = AVCaptureMetadataOutput()
    private let queue = DispatchQueue(label: "makoto.camera")
    private var input: AVCaptureDeviceInput?
    private var configured = false

    /// 仮想カメラの倍率のうち、画面上の「1x」にあたる値(超広角付きの機種では 2.0)
    private var base: CGFloat = 1

    @Published var authorized = true
    @Published var front = false
    @Published var zoom: CGFloat = 1          // 画面上の倍率
    @Published var minZoom: CGFloat = 1
    @Published var maxZoom: CGFloat = 1
    @Published var ev: Float = 0
    @Published var flash = "off"              // off / auto / on
    @Published var stamp = true
    @Published var qr = false                 // 写真に QR を入れるか
    @Published var ratio = "4:3"              // 4:3 / 16:9 / 1:1
    @Published var lastShot: URL?             // 直前に撮った写真(原本のまま送る用)

    // QR の簡易確認
    @Published var scanning = false
    @Published var scanned: QrInfo?
    @Published var autoEvLabel: String?
    private var scanTimer: Timer?
    private var scanStep = 0
    private var evBeforeScan: Float = 0
    @Published var busy = false
    @Published var message: String?

    private var pendingLocation: CLLocation?

    func start() {
        AVCaptureDevice.requestAccess(for: .video) { ok in
            DispatchQueue.main.async { self.authorized = ok }
            guard ok else { return }
            self.queue.async {
                if !self.configured { self.configure(front: false); self.configured = true }
                if !self.session.isRunning { self.session.startRunning() }
            }
        }
    }

    func stop() {
        queue.async { if self.session.isRunning { self.session.stopRunning() } }
    }

    private func pickDevice(front: Bool) -> AVCaptureDevice? {
        if front {
            return AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front)
        }
        let types: [AVCaptureDevice.DeviceType] = [.builtInTripleCamera, .builtInDualWideCamera, .builtInDualCamera, .builtInWideAngleCamera]
        return AVCaptureDevice.DiscoverySession(deviceTypes: types, mediaType: .video, position: .back).devices.first
    }

    private func configure(front: Bool) {
        guard let device = pickDevice(front: front), let newInput = try? AVCaptureDeviceInput(device: device) else {
            DispatchQueue.main.async { self.message = "カメラを使えません" }
            return
        }
        session.beginConfiguration()
        session.sessionPreset = .photo
        if let old = input { session.removeInput(old) }
        if session.canAddInput(newInput) { session.addInput(newInput); input = newInput }
        if !session.outputs.contains(output), session.canAddOutput(output) {
            session.addOutput(output)
            output.maxPhotoQualityPrioritization = .quality
        }
        if !session.outputs.contains(metadata), session.canAddOutput(metadata) {
            session.addOutput(metadata)
            metadata.setMetadataObjectsDelegate(self, queue: .main)
            metadata.metadataObjectTypes = []
        }
        session.commitConfiguration()

        // 超広角を含む仮想カメラでは、広角レンズに切り替わる倍率を「1x」とする
        var b: CGFloat = 1
        let hasUltraWide = device.deviceType == .builtInTripleCamera || device.deviceType == .builtInDualWideCamera
        if hasUltraWide, let first = device.virtualDeviceSwitchOverVideoZoomFactors.first {
            b = CGFloat(truncating: first)
        }
        base = b
        let lo = device.minAvailableVideoZoomFactor / b
        let hi = min(device.maxAvailableVideoZoomFactor / b, 10)
        if (try? device.lockForConfiguration()) != nil {
            device.videoZoomFactor = max(device.minAvailableVideoZoomFactor, min(b, device.maxAvailableVideoZoomFactor))
            device.setExposureTargetBias(0, completionHandler: nil)
            device.unlockForConfiguration()
        }
        DispatchQueue.main.async {
            self.front = front
            self.minZoom = lo
            self.maxZoom = hi
            self.zoom = 1
            self.ev = 0
            if front { self.flash = "off" }
        }
    }

    func switchCamera() {
        let next = !front
        queue.async { self.configure(front: next) }
    }

    func setZoom(_ display: CGFloat) {
        let z = max(minZoom, min(display, maxZoom))
        zoom = z
        let b = base
        queue.async {
            guard let d = self.input?.device else { return }
            guard (try? d.lockForConfiguration()) != nil else { return }
            d.videoZoomFactor = max(d.minAvailableVideoZoomFactor, min(z * b, d.maxAvailableVideoZoomFactor))
            d.unlockForConfiguration()
        }
    }

    func setEv(_ value: Float) {
        ev = value
        queue.async {
            guard let d = self.input?.device else { return }
            let v = max(d.minExposureTargetBias, min(value, d.maxExposureTargetBias))
            guard (try? d.lockForConfiguration()) != nil else { return }
            d.setExposureTargetBias(v, completionHandler: nil)
            d.unlockForConfiguration()
        }
    }

    // MARK: QR の簡易確認

    /// かざして読むモードの開始/終了。読んでいる間は中央に明るさとピントを合わせ、
    /// 読めるまで明るさを暗い側から自動で変える(画面の QR は白飛びしやすいため)。終了時に元に戻す。
    func toggleScan() {
        if scanning { stopScan() } else { startScan() }
    }

    private func startScan() {
        scanning = true
        scanned = nil
        scanStep = 0
        evBeforeScan = ev
        queue.async {
            if self.metadata.availableMetadataObjectTypes.contains(.qr) { self.metadata.metadataObjectTypes = [.qr] }
            guard let d = self.input?.device, (try? d.lockForConfiguration()) != nil else { return }
            let center = CGPoint(x: 0.5, y: 0.5)
            if d.isFocusPointOfInterestSupported { d.focusPointOfInterest = center; if d.isFocusModeSupported(.continuousAutoFocus) { d.focusMode = .continuousAutoFocus } }
            if d.isExposurePointOfInterestSupported { d.exposurePointOfInterest = center; if d.isExposureModeSupported(.continuousAutoExposure) { d.exposureMode = .continuousAutoExposure } }
            d.unlockForConfiguration()
        }
        stepExposure()
        scanTimer = Timer.scheduledTimer(withTimeInterval: 1.2, repeats: true) { [weak self] _ in self?.stepExposure() }
    }

    private func stepExposure() {
        guard scanning, scanned == nil else { scanTimer?.invalidate(); scanTimer = nil; return }
        let seq: [Float] = [-1, -2, -3, 0, 1]
        let v = seq[scanStep % seq.count]
        scanStep += 1
        autoEvLabel = String(format: "%+.1f", v)
        applyBias(v)
    }

    func stopScan() {
        scanTimer?.invalidate()
        scanTimer = nil
        scanning = false
        scanned = nil
        autoEvLabel = nil
        let restore = evBeforeScan
        queue.async { self.metadata.metadataObjectTypes = [] }
        applyBias(restore)
    }

    private func applyBias(_ value: Float) {
        queue.async {
            guard let d = self.input?.device, (try? d.lockForConfiguration()) != nil else { return }
            d.setExposureTargetBias(max(d.minExposureTargetBias, min(value, d.maxExposureTargetBias)), completionHandler: nil)
            d.unlockForConfiguration()
        }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard scanning else { return }
        for o in objects {
            if let code = o as? AVMetadataMachineReadableCodeObject, let text = code.stringValue, let info = QrPayload.parse(text) {
                scanned = info
                autoEvLabel = nil
                return
            }
        }
    }

    func cycleFlash() {
        flash = flash == "off" ? "auto" : (flash == "auto" ? "on" : "off")
    }

    func shoot(location: CLLocation?) {
        guard !busy else { return }
        busy = true
        pendingLocation = location
        let mode: AVCaptureDevice.FlashMode = flash == "auto" ? .auto : (flash == "on" ? .on : .off)
        let orientation = UIDevice.current.orientation
        queue.async {
            let settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
            settings.photoQualityPrioritization = .quality
            if self.output.supportedFlashModes.contains(mode) { settings.flashMode = mode }
            if let c = self.output.connection(with: .video), c.isVideoOrientationSupported {
                switch orientation {
                case .landscapeLeft: c.videoOrientation = .landscapeRight
                case .landscapeRight: c.videoOrientation = .landscapeLeft
                case .portraitUpsideDown: c.videoOrientation = .portraitUpsideDown
                default: c.videoOrientation = .portrait
                }
            }
            self.output.capturePhoto(with: settings, delegate: self)
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        guard error == nil, let data = photo.fileDataRepresentation() else {
            DispatchQueue.main.async { self.busy = false; self.message = "撮影に失敗しました" }
            return
        }
        DispatchQueue.main.async {
            let settings = ShotSettings(stamp: self.stamp, qr: self.stamp && self.qr, frontLens: self.front, zoom: Double(self.zoom), ev: Double(self.ev), flash: self.flash, ratio: self.ratio)
            let loc = self.pendingLocation
            Task.detached(priority: .userInitiated) {
                do {
                    let shot = try await PhotoPipeline.process(captured: data, loc: loc, settings: settings)
                    await MainActor.run { self.busy = false; self.lastShot = shot.fileURL; self.message = "保存しました(撮影ID \(shot.idHex))" }
                } catch {
                    await MainActor.run { self.busy = false; self.message = "保存に失敗しました: \(error.localizedDescription)" }
                }
            }
        }
    }
}

/// カメラのプレビュー
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        v.backgroundColor = .black
        v.previewLayer.session = session
        v.previewLayer.videoGravity = .resizeAspectFill
        return v
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}
}
