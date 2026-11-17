package app.makoto.camera

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * 端末ごとの署名鍵。秘密鍵は Android Keystore の中で作られ、アプリからも取り出せない。
 * StrongBox(専用チップ)があればそこに、なければ TEE に置く。
 */
object DeviceKey {
    private const val ALIAS = "makoto_device_key_v1"

    private fun generate(strongBox: Boolean) {
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
        if (strongBox) b.setIsStrongBoxBacked(true)
        val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        g.initialize(b.build())
        g.generateKeyPair()
    }

    @Synchronized
    private fun entry(): KeyStore.PrivateKeyEntry {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            try { generate(true) } catch (e: Exception) { generate(false) }
        }
        return ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
    }

    /** 公開鍵(65 バイト: 04 || X || Y) */
    fun publicKey(): ByteArray {
        val enc = entry().certificate.publicKey.encoded
        return enc.copyOfRange(enc.size - 65, enc.size)
    }

    /** ECDSA P-256 / SHA-256 / DER 形式 */
    fun sign(data: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(entry().privateKey)
        s.update(data)
        return s.sign()
    }
}
