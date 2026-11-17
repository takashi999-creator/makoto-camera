package app.makoto.camera.core

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * マコトカメラ 共通仕様 v1 (Android / iPhone 共通。Swift 版 MakotoCore.swift と同じ計算をすること)
 *
 * 1. 頑丈な透かし : 画像を 64x64 のセルに分け、各セルに sin(2πu)·sin(2πv) の淡い模様を足す。
 *                   模様の向き(+/-)で 64bit (撮影ID 48bit + CRC16) を繰り返し埋める。
 *                   セルは画像サイズに比例するので、縮小や再圧縮後も読める。
 *                   埋める前に元画像を調べ、絵柄に負けそうなビットだけ模様を少し強くする。
 * 2. 署名付き記録 : JPEG の APP11 セグメントに「記録(JSON) + ECDSA署名 + 公開鍵」を入れる。
 *                   記録には、このセグメントと Exif(APP1) を除いたファイル全体の SHA-256 が入る。
 *                   Exif を除くのは、OS が写真を渡すときに位置情報を伏せることがあるため。
 *                   位置や時刻は署名付きの記録の中に同じものが入っているので、そちらが正となる。
 * 3. 変化マップ   : 32x32 マスの平均輝度を記録に入れ、編集された場所を示す。
 */
object Spec {
    const val GRID = 64          // 透かしセル数(縦横)
    const val ID_BYTES = 6       // 撮影ID 48bit
    const val BITS = 64          // 48bit ID + 16bit CRC
    const val AMP_MIN = 3.0      // 透かしの振幅(輝度)。ビットごとに AMP_MIN〜AMP_MAX で決める
    const val AMP_MAX = 6.0
    const val CLIP = 6.0         // 検出時、セルごとの推定値をこの範囲に切り詰める
    const val MARGIN = 2.0       // 埋め込み時に確保するビットごとの平均余裕
    const val MAP = 32           // 変化マップのマス数(縦横)
    const val MAP_THRESHOLD = 8  // これ以上平均輝度が違うマスを「変化あり」とする
    const val MIN_STRENGTH = 0.7 // 透かし検出の最低強度
    val MAGIC: ByteArray = byteArrayOf(0x4D, 0x41, 0x4B, 0x4F, 0x54, 0x4F, 0x31, 0x00) // "MAKOTO1\0"
    const val APP_NAME = "マコトカメラ"
}

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) { val v = b.toInt() and 0xFF; sb.append("0123456789abcdef"[v ushr 4]); sb.append("0123456789abcdef"[v and 15]) }
    return sb.toString()
}

fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

// ---------------------------------------------------------------- 透かし

object WmMath {
    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    /** id の下位 48bit を 6 バイト(上位から)にする */
    fun idBytes(id: Long): ByteArray = ByteArray(Spec.ID_BYTES) { i -> (id ushr (8 * (Spec.ID_BYTES - 1 - i))).toByte() }

    /** 64bit のペイロード(上位ビットから) */
    fun payload(id: Long): BooleanArray {
        val b = idBytes(id)
        val crc = crc16(b)
        val all = b + byteArrayOf((crc ushr 8).toByte(), crc.toByte())
        return BooleanArray(Spec.BITS) { i -> (all[i / 8].toInt() ushr (7 - i % 8)) and 1 == 1 }
    }

    /** セル(列 cx, 行 cy)が担当するビット。idx = cy * GRID + cx */
    fun cellBit(idx: Int): Int = ((idx % Spec.GRID) * 37 + (idx / Spec.GRID) * 21) % Spec.BITS

    /** 検出と同じ計算で、ビットごとの票を数える */
    fun votes(est: DoubleArray, extra: ((Int) -> Double)?): DoubleArray {
        val vote = DoubleArray(Spec.BITS)
        for (i in est.indices) {
            if (est[i].isNaN()) continue
            var e = est[i] + (extra?.invoke(i) ?: 0.0)
            if (e > Spec.CLIP) e = Spec.CLIP else if (e < -Spec.CLIP) e = -Spec.CLIP
            vote[cellBit(i)] += cellSign(i) * e
        }
        return vote
    }

    /** 元画像のセル推定値から、ビットごとの振幅を決める(絵柄に負けそうなビットだけ強くする) */
    fun planAmplitudes(hostEst: DoubleArray, id: Long): FloatArray {
        val bits = payload(id)
        val reps = Spec.GRID * Spec.GRID / Spec.BITS
        val amp = FloatArray(Spec.BITS) { Spec.AMP_MAX.toFloat() }
        var a = Spec.AMP_MIN
        val todo = BooleanArray(Spec.BITS) { true }
        while (a <= Spec.AMP_MAX + 1e-9) {
            val v = votes(hostEst) { i -> a * cellSign(i) * (if (bits[cellBit(i)]) 1 else -1) }
            for (k in 0 until Spec.BITS) if (todo[k]) {
                val m = (if (bits[k]) v[k] else -v[k]) / reps
                if (m >= Spec.MARGIN) { amp[k] = a.toFloat(); todo[k] = false }
            }
            a += 0.5
        }
        return amp
    }

    /** 32bit 整数のあふれを使う(Swift では &* と &+ を UInt32 で使う) */
    fun cellSign(idx: Int): Int {
        val v = idx * 1103515245 + 12345
        return if ((v ushr 16) and 1 == 1) 1 else -1
    }

    /** 各画素が属するセル番号と、セル内の sin 値 */
    fun axis(n: Int, cells: IntArray, wave: FloatArray) {
        for (i in 0 until n) {
            val u = (i + 0.5) * Spec.GRID / n
            var c = u.toInt()
            if (c >= Spec.GRID) c = Spec.GRID - 1
            cells[i] = c
            wave[i] = Math.sin(2.0 * Math.PI * (u - c)).toFloat()
        }
    }
}

/** 画像を帯(strip)ごとに渡して透かしを埋める。pixels は ARGB。 */
class WatermarkEmbedder(private val w: Int, private val h: Int, id: Long, amp: FloatArray) {
    private val cx = IntArray(w); private val sx = FloatArray(w)
    private val cy = IntArray(h); private val sy = FloatArray(h)
    private val t = FloatArray(Spec.GRID * Spec.GRID)

    init {
        WmMath.axis(w, cx, sx); WmMath.axis(h, cy, sy)
        val bits = WmMath.payload(id)
        for (i in t.indices) { val k = WmMath.cellBit(i); t[i] = amp[k] * WmMath.cellSign(i) * (if (bits[k]) 1 else -1) }
    }

    fun apply(strip: IntArray, y0: Int, rows: Int) {
        var p = 0
        for (y in y0 until y0 + rows) {
            val rowCell = cy[y] * Spec.GRID
            val wy = sy[y]
            for (x in 0 until w) {
                val d = Math.round(t[rowCell + cx[x]] * sx[x] * wy)
                if (d != 0) {
                    val c = strip[p]
                    var r = ((c ushr 16) and 0xFF) + d
                    var g = ((c ushr 8) and 0xFF) + d
                    var b = (c and 0xFF) + d
                    if (r < 0) r = 0 else if (r > 255) r = 255
                    if (g < 0) g = 0 else if (g > 255) g = 255
                    if (b < 0) b = 0 else if (b > 255) b = 255
                    strip[p] = (c and -0x1000000) or (r shl 16) or (g shl 8) or b
                }
                p++
            }
        }
    }
}

class WatermarkResult(val found: Boolean, val id: Long, val strength: Double) {
    val idHex: String get() = WmMath.idBytes(id).toHex()
}

/** 画像を帯ごとに渡して、透かしの読み取りと変化マップの計算を一度に行う。 */
class ImageAnalyzer(private val w: Int, private val h: Int) {
    private val cx = IntArray(w); private val sx = FloatArray(w)
    private val cy = IntArray(h); private val sy = FloatArray(h)
    private val acc = DoubleArray(Spec.GRID * Spec.GRID)
    private val norm = DoubleArray(Spec.GRID * Spec.GRID)
    private val mapSum = LongArray(Spec.MAP * Spec.MAP)
    private val mapCnt = IntArray(Spec.MAP * Spec.MAP)
    private val mx = IntArray(w) { minOf(Spec.MAP - 1, (it.toLong() * Spec.MAP / w).toInt()) }

    init { WmMath.axis(w, cx, sx); WmMath.axis(h, cy, sy) }

    fun feed(strip: IntArray, y0: Int, rows: Int) {
        var p = 0
        for (y in y0 until y0 + rows) {
            val rowCell = cy[y] * Spec.GRID
            val wy = sy[y]
            val mrow = minOf(Spec.MAP - 1, (y.toLong() * Spec.MAP / h).toInt()) * Spec.MAP
            for (x in 0 until w) {
                val c = strip[p++]
                val l = (77 * ((c ushr 16) and 0xFF) + 150 * ((c ushr 8) and 0xFF) + 29 * (c and 0xFF)) shr 8
                val k = sx[x] * wy
                val i = rowCell + cx[x]
                acc[i] += (l * k).toDouble()
                norm[i] += (k * k).toDouble()
                val m = mrow + mx[x]
                mapSum[m] += l.toLong(); mapCnt[m]++
            }
        }
    }

    /** セルごとの模様の強さの推定値(画素がないセルは NaN) */
    fun cellEstimates(): DoubleArray = DoubleArray(acc.size) { i -> if (norm[i] > 0.0) acc[i] / norm[i] else Double.NaN }

    fun watermark(): WatermarkResult {
        val vote = WmMath.votes(cellEstimates(), null)
        val reps = Spec.GRID * Spec.GRID / Spec.BITS
        val n = Spec.ID_BYTES
        val bytes = ByteArray(n + 2)
        var strength = 0.0
        for (k in 0 until Spec.BITS) {
            if (vote[k] > 0) bytes[k / 8] = (bytes[k / 8].toInt() or (1 shl (7 - k % 8))).toByte()
            strength += Math.abs(vote[k]) / reps
        }
        strength /= Spec.BITS
        var id = 0L
        for (i in 0 until n) id = (id shl 8) or (bytes[i].toLong() and 0xFF)
        val crc = ((bytes[n].toInt() and 0xFF) shl 8) or (bytes[n + 1].toInt() and 0xFF)
        val ok = crc == WmMath.crc16(bytes.copyOf(n)) && strength >= Spec.MIN_STRENGTH
        return WatermarkResult(ok, id, strength)
    }

    /** 32x32 マスの平均輝度 */
    fun map(): ByteArray = ByteArray(Spec.MAP * Spec.MAP) { i -> if (mapCnt[i] == 0) 0 else (mapSum[i] / mapCnt[i]).toByte() }
}

// ---------------------------------------------------------------- JPEG 内の記録

class Record(val manifest: ByteArray, val signature: ByteArray, val publicKey: ByteArray) {
    /** 端末の鍵ID(公開鍵の SHA-256 の先頭 8 バイト) */
    val keyId: String get() = sha256(publicKey).copyOf(8).toHex()

    fun signatureValid(): Boolean = try {
        if (publicKey.size != 65) false else {
            val spki = SPKI_PREFIX + publicKey
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
            val s = Signature.getInstance("SHA256withECDSA")
            s.initVerify(key); s.update(manifest); s.verify(signature)
        }
    } catch (e: Exception) { false }

    fun pack(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(Spec.MAGIC)
        out.write(be(manifest.size, 4)); out.write(manifest)
        out.write(be(signature.size, 2)); out.write(signature)
        out.write(be(publicKey.size, 2)); out.write(publicKey)
        return out.toByteArray()
    }

    companion object {
        /** P-256 公開鍵(65バイト)の前に付ける X.509 ヘッダ */
        val SPKI_PREFIX: ByteArray = byteArrayOf(
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01,
            0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00)

        private fun be(v: Int, n: Int) = ByteArray(n) { i -> (v ushr (8 * (n - 1 - i))).toByte() }

        fun unpack(p: ByteArray): Record? {
            try {
                var o = Spec.MAGIC.size
                fun u(n: Int): Int { var v = 0; repeat(n) { v = (v shl 8) or (p[o++].toInt() and 0xFF) }; return v }
                val ml = u(4); if (ml < 0 || o + ml > p.size) return null
                val m = p.copyOfRange(o, o + ml); o += ml
                val sl = u(2); if (o + sl > p.size) return null
                val s = p.copyOfRange(o, o + sl); o += sl
                val kl = u(2); if (o + kl > p.size) return null
                val k = p.copyOfRange(o, o + kl)
                return Record(m, s, k)
            } catch (e: Exception) { return null }
        }
    }
}

object JpegContainer {
    private const val APP11 = 0xEB

    private fun isOurs(f: ByteArray, payloadStart: Int, payloadLen: Int): Boolean {
        if (payloadLen < Spec.MAGIC.size) return false
        for (i in Spec.MAGIC.indices) if (f[payloadStart + i] != Spec.MAGIC[i]) return false
        return true
    }

    /**
     * ハッシュ対象(記録セグメントと Exif(APP1) を除いたファイル)と、記録の中身を返す。
     * JPEG でなければ null。
     */
    fun split(f: ByteArray): Pair<ByteArray, ByteArray?>? {
        if (f.size < 4 || (f[0].toInt() and 0xFF) != 0xFF || (f[1].toInt() and 0xFF) != 0xD8) return null
        val out = java.io.ByteArrayOutputStream(f.size)
        out.write(f, 0, 2)
        var payload: ByteArray? = null
        var o = 2
        while (o + 4 <= f.size) {
            if ((f[o].toInt() and 0xFF) != 0xFF) break
            val marker = f[o + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break
            val len = ((f[o + 2].toInt() and 0xFF) shl 8) or (f[o + 3].toInt() and 0xFF)
            if (len < 2 || o + 2 + len > f.size) break
            if (marker == APP11 && payload == null && isOurs(f, o + 4, len - 2)) {
                payload = f.copyOfRange(o + 4, o + 2 + len)
            } else if (marker != 0xE1) {
                out.write(f, o, 2 + len)
            }
            o += 2 + len
        }
        out.write(f, o, f.size - o)
        return Pair(out.toByteArray(), payload)
    }

    /** APPn / COM セグメントの並びの直後に記録を入れる。 */
    fun insert(f: ByteArray, payload: ByteArray): ByteArray {
        require(payload.size + 2 <= 0xFFFF) { "record too large" }
        var o = 2
        while (o + 4 <= f.size) {
            if ((f[o].toInt() and 0xFF) != 0xFF) break
            val marker = f[o + 1].toInt() and 0xFF
            val isMeta = (marker in 0xE0..0xEF) || marker == 0xFE
            if (!isMeta) break
            val len = ((f[o + 2].toInt() and 0xFF) shl 8) or (f[o + 3].toInt() and 0xFF)
            if (len < 2 || o + 2 + len > f.size) break
            o += 2 + len
        }
        val len = payload.size + 2
        val head = byteArrayOf(0xFF.toByte(), APP11.toByte(), (len ushr 8).toByte(), len.toByte())
        return f.copyOfRange(0, o) + head + payload + f.copyOfRange(o, f.size)
    }
}

// ---------------------------------------------------------------- かんたん JSON (平らなオブジェクトのみ)

object FlatJson {
    fun write(m: Map<String, Any?>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in m) {
            if (!first) sb.append(','); first = false
            sb.append(quote(k)).append(':')
            when (v) {
                null -> sb.append("null")
                is Boolean, is Int, is Long -> sb.append(v.toString())
                is Double -> sb.append(if (v.isNaN() || v.isInfinite()) "null" else v.toString())
                is Float -> sb.append(if (v.isNaN() || v.isInfinite()) "null" else v.toString())
                else -> sb.append(quote(v.toString()))
            }
        }
        return sb.append('}').toString()
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\n' -> sb.append("\\n")
            ch == '\r' -> sb.append("\\r")
            ch == '\t' -> sb.append("\\t")
            ch < ' ' -> sb.append(String.format("\\u%04x", ch.code))
            else -> sb.append(ch)
        }
        return sb.append('"').toString()
    }

    /** 値はすべて文字列にして返す(null は入れない)。壊れていたら空の Map。 */
    fun read(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            var i = 0
            fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
            fun str(): String {
                val sb = StringBuilder(); i++
                while (s[i] != '"') {
                    if (s[i] == '\\') {
                        i++
                        when (s[i]) {
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                            else -> sb.append(s[i])
                        }
                    } else sb.append(s[i])
                    i++
                }
                i++
                return sb.toString()
            }
            ws(); if (s[i] != '{') return out; i++
            while (true) {
                ws(); if (s[i] == '}') break
                val k = str(); ws(); i++ /* : */; ws()
                if (s[i] == '"') out[k] = str()
                else {
                    val st = i
                    while (i < s.length && s[i] != ',' && s[i] != '}') i++
                    val raw = s.substring(st, i).trim()
                    if (raw != "null") out[k] = raw
                }
                ws(); if (s[i] == ',') i++
            }
        } catch (e: Exception) { }
        return out
    }
}

// ---------------------------------------------------------------- 封印と判定

object Sealer {
    /**
     * @param jpeg     透かしと Exif が入った完成 JPEG(記録セグメントなし)。JPEG であること
     * @param fields   記録に入れる項目。imageHash はここで追加する
     * @param sign     記録のバイト列に端末の鍵で署名する関数(ECDSA P-256 / SHA-256 / DER)
     * @param publicKey 端末の公開鍵(65バイト, 04||X||Y)
     * @return 記録入りの JPEG と、記録(端末内の控え用)
     */
    fun seal(jpeg: ByteArray, fields: Map<String, Any?>, sign: (ByteArray) -> ByteArray, publicKey: ByteArray): Pair<ByteArray, Record> {
        val m = LinkedHashMap(fields)
        m["imageHash"] = sha256(JpegContainer.split(jpeg)!!.first).toHex()
        val manifest = FlatJson.write(m).toByteArray(Charsets.UTF_8)
        val rec = Record(manifest, sign(manifest), publicKey)
        return Pair(JpegContainer.insert(jpeg, rec.pack()), rec)
    }
}

enum class Verdict {
    ORIGINAL,        // 署名も内容も一致。撮影時のまま
    EDITED,          // 記録は本物だが、画像が撮影後に変わっている
    FORGED_RECORD,   // 記録はあるが署名が合わない(偽造または破損)
    WATERMARK_ONLY,  // 記録は消えているが、透かしからこのアプリの写真と分かる(再保存・編集あり)
    UNKNOWN          // 確認不能
}

class VerifyResult(
    val verdict: Verdict,
    val fields: Map<String, String>,     // 記録の項目(署名で守られたもの、または端末内の控え)
    val keyId: String?,                  // 撮影端末の鍵ID
    val watermark: WatermarkResult?,
    val changed: BooleanArray?,          // 32x32 マスの変化マップ
    val fromLocalCopy: Boolean           // 端末内の控えと照合したか
) {
    val changedCount: Int get() = changed?.count { it } ?: 0
}

object Verifier {
    fun mapFromBase64(s: String?, decode: (String) -> ByteArray): ByteArray? = try {
        if (s == null) null else decode(s).takeIf { it.size == Spec.MAP * Spec.MAP }
    } catch (e: Exception) { null }

    fun diff(a: ByteArray, b: ByteArray): BooleanArray = BooleanArray(a.size) { i ->
        Math.abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)) >= Spec.MAP_THRESHOLD
    }

    /**
     * @param file        判定するファイルのバイト列そのまま
     * @param analyzer    同じファイルをデコードして全画素を feed 済みのもの(デコードできなければ null)
     * @param localRecord 撮影IDから端末内の控え(pack 済み)を探す関数
     * @param b64         Base64 デコード関数(Android と JVM で実装が違うため外から渡す)
     */
    fun verify(file: ByteArray, analyzer: ImageAnalyzer?, localRecord: (String) -> ByteArray?, b64: (String) -> ByteArray): VerifyResult {
        val wm = analyzer?.watermark()
        val nowMap = analyzer?.map()
        val split = JpegContainer.split(file)
        val rec = split?.second?.let { Record.unpack(it) }
        if (split != null && rec != null) {
            val fields = FlatJson.read(String(rec.manifest, Charsets.UTF_8))
            if (!rec.signatureValid()) return VerifyResult(Verdict.FORGED_RECORD, emptyMap(), rec.keyId, wm, null, false)
            if (fields["imageHash"] == sha256(split.first).toHex())
                return VerifyResult(Verdict.ORIGINAL, fields, rec.keyId, wm, null, false)
            val orig = mapFromBase64(fields["map"], b64)
            val changed = if (orig != null && nowMap != null) diff(orig, nowMap) else null
            return VerifyResult(Verdict.EDITED, fields, rec.keyId, wm, changed, false)
        }
        if (wm != null && wm.found) {
            val local = localRecord(wm.idHex)?.let { Record.unpack(it) }
            if (local != null && local.signatureValid()) {
                val fields = FlatJson.read(String(local.manifest, Charsets.UTF_8))
                val orig = mapFromBase64(fields["map"], b64)
                val changed = if (orig != null && nowMap != null) diff(orig, nowMap) else null
                return VerifyResult(Verdict.WATERMARK_ONLY, fields, local.keyId, wm, changed, true)
            }
            return VerifyResult(Verdict.WATERMARK_ONLY, emptyMap(), null, wm, null, false)
        }
        return VerifyResult(Verdict.UNKNOWN, emptyMap(), null, wm, null, false)
    }
}
