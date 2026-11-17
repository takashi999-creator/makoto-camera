package app.makoto.camera

import android.content.Context

/** 信頼する端末の一覧(端末ID → 名前)。この端末の中だけに保存する。 */
object TrustStore {
    private fun prefs(c: Context) = c.getSharedPreferences("trusted_devices", Context.MODE_PRIVATE)

    /** 入力された端末IDを整える。16 桁の 16 進数でなければ null。 */
    fun normalize(id: String): String? {
        val s = id.trim().lowercase().replace(" ", "").replace("-", "")
        return if (s.matches(Regex("[0-9a-f]{16}"))) s else null
    }

    fun all(c: Context): List<Pair<String, String>> =
        prefs(c).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.sortedBy { it.second }

    fun nameFor(c: Context, id: String?): String? = if (id == null) null else prefs(c).getString(id, null)

    fun add(c: Context, id: String, name: String) {
        prefs(c).edit().putString(id, name.trim().ifEmpty { "名前なし" }).apply()
    }

    fun remove(c: Context, id: String) {
        prefs(c).edit().remove(id).apply()
    }
}
