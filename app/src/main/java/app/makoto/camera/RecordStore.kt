package app.makoto.camera

import android.content.Context
import java.io.File

/** 撮影した写真の記録の控え。記録が消された写真でも、撮影IDから照合できるようにする。 */
object RecordStore {
    private fun dir(c: Context) = File(c.filesDir, "records").apply { mkdirs() }

    fun save(c: Context, idHex: String, packed: ByteArray) {
        File(dir(c), "$idHex.bin").writeBytes(packed)
    }

    /** すべての控え(新しい順) */
    fun all(c: Context): List<ByteArray> =
        (dir(c).listFiles() ?: emptyArray()).filter { it.name.endsWith(".bin") }.sortedByDescending { it.lastModified() }.map { it.readBytes() }

    fun load(c: Context, idHex: String): ByteArray? {
        if (!idHex.matches(Regex("[0-9a-f]{1,32}"))) return null
        val f = File(dir(c), "$idHex.bin")
        return if (f.exists()) f.readBytes() else null
    }
}
