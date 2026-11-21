package com.priyanshu.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Visual effect played around a character on the home screen. */
enum class CharEffect(val label: String) {
    ENERGY("Energy aura"),
    LIGHTNING("Lightning storm"),
    PORTAL("Spinning portals"),
    TIMELINE("Timeline distortion"),
    PARTICLES("Floating particles"),
    NONE("No effect")
}

/**
 * One user-created character profile. Every file it points to was imported by the user and lives in
 * the app's private storage (filesDir/characters/<id>/), kept apart from the app's own assets.
 */
data class CharacterProfile(
    val id: String,
    var name: String,
    var universe: String = "",
    var effect: CharEffect = CharEffect.ENERGY,
    var image: String = "",       // character cutout or portrait
    var wallpaper: String = "",   // optional full-screen wallpaper
    var model: String = "",       // optional GLB / glTF 3D model (stored for a later 3D build)
    var useWallpaper: Boolean = false,
    var accent: Int = 0,          // 0 = take the colour automatically from the character image
    var size: Float = 1f,         // 0.5 .. 1.4 of the default height
    var lift: Float = 0f          // -0.2 .. 0.2 of screen height, moves the character up or down
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("universe", universe); put("effect", effect.name)
        put("image", image); put("wallpaper", wallpaper); put("model", model)
        put("useWallpaper", useWallpaper); put("accent", accent); put("size", size.toDouble()); put("lift", lift.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject) = CharacterProfile(
            id = o.getString("id"),
            name = o.optString("name", "Character"),
            universe = o.optString("universe", ""),
            effect = try { CharEffect.valueOf(o.optString("effect", "ENERGY")) } catch (_: Exception) { CharEffect.ENERGY },
            image = o.optString("image", ""),
            wallpaper = o.optString("wallpaper", ""),
            model = o.optString("model", ""),
            useWallpaper = o.optBoolean("useWallpaper", false),
            accent = o.optInt("accent", 0),
            size = o.optDouble("size", 1.0).toFloat(),
            lift = o.optDouble("lift", 0.0).toFloat()
        )
    }
}

object CharacterStore {

    private fun root(ctx: Context) = File(ctx.filesDir, "characters").apply { mkdirs() }
    fun folder(ctx: Context, id: String) = File(root(ctx), id).apply { mkdirs() }
    private fun index(ctx: Context) = File(root(ctx), "index.json")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun list(ctx: Context): MutableList<CharacterProfile> {
        val f = index(ctx)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            MutableList(arr.length()) { CharacterProfile.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { mutableListOf() }
    }

    private fun writeAll(ctx: Context, all: List<CharacterProfile>) {
        val arr = JSONArray()
        all.forEach { arr.put(it.toJson()) }
        index(ctx).writeText(arr.toString())
        bumpVersion(ctx)
    }

    fun create(ctx: Context, name: String): CharacterProfile {
        val p = CharacterProfile(id = UUID.randomUUID().toString().take(8), name = name)
        val all = list(ctx); all.add(p); writeAll(ctx, all)
        return p
    }

    fun save(ctx: Context, p: CharacterProfile) {
        val all = list(ctx)
        val i = all.indexOfFirst { it.id == p.id }
        if (i >= 0) all[i] = p else all.add(p)
        writeAll(ctx, all)
    }

    fun delete(ctx: Context, id: String) {
        writeAll(ctx, list(ctx).filter { it.id != id })
        File(root(ctx), id).deleteRecursively()
        if (activeId(ctx) == id) setActive(ctx, null)
    }

    fun activeId(ctx: Context): String? = prefs(ctx).getString("char_active", null)
    fun active(ctx: Context): CharacterProfile? = activeId(ctx)?.let { id -> list(ctx).find { it.id == id } }
    fun setActive(ctx: Context, id: String?) {
        prefs(ctx).edit().putString("char_active", id).apply()
        bumpVersion(ctx)
    }

    /** Changes whenever profiles or the active character change, so the home screen knows to reload. */
    fun version(ctx: Context): Long = prefs(ctx).getLong("char_version", 0L)
    private fun bumpVersion(ctx: Context) = prefs(ctx).edit().putLong("char_version", System.currentTimeMillis()).apply()

    /** Copies a picked file into the profile's private folder; returns the stored file name. */
    fun importFile(ctx: Context, uri: Uri, id: String, kind: String): String? {
        return try {
            val name = displayName(ctx, uri) ?: kind
            val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 }
                ?: when (ctx.contentResolver.getType(uri)) {
                    "image/png" -> "png"; "image/webp" -> "webp"; "image/jpeg" -> "jpg"; else -> "bin"
                }
            val fileName = "$kind.$ext"
            val dir = folder(ctx, id)
            dir.listFiles()?.filter { it.name.startsWith("$kind.") }?.forEach { it.delete() }
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                File(dir, fileName).outputStream().use { input.copyTo(it) }
            } ?: return null
            bumpVersion(ctx)
            fileName
        } catch (_: Exception) { null }
    }

    private fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null }

    fun file(ctx: Context, p: CharacterProfile, name: String): File? =
        if (name.isBlank()) null else File(folder(ctx, p.id), name).takeIf { it.exists() }

    fun fileSize(ctx: Context, p: CharacterProfile, name: String): Long = file(ctx, p, name)?.length() ?: 0L

    /** Decodes an image no larger than maxDim on its longest side (keeps memory low). */
    fun loadBitmap(ctx: Context, p: CharacterProfile, name: String, maxDim: Int): Bitmap? {
        val f = file(ctx, p, name) ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxDim * 2 || bounds.outHeight / sample > maxDim * 2) sample *= 2
            val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val longest = maxOf(bmp.width, bmp.height)
            if (longest > maxDim) {
                val s = maxDim.toFloat() / longest
                Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
            } else bmp
        } catch (_: Throwable) { null }
    }

    /** True when the image has see-through pixels around its edges (a cutout rather than a rectangle). */
    fun isCutout(b: Bitmap): Boolean {
        if (!b.hasAlpha()) return false
        var clear = 0
        var total = 0
        val step = maxOf(1, minOf(b.width, b.height) / 40)
        var x = 0
        while (x < b.width) {
            total += 2
            if (Color.alpha(b.getPixel(x, 0)) < 40) clear++
            if (Color.alpha(b.getPixel(x, b.height - 1)) < 40) clear++
            x += step
        }
        var y = 0
        while (y < b.height) {
            total += 2
            if (Color.alpha(b.getPixel(0, y)) < 40) clear++
            if (Color.alpha(b.getPixel(b.width - 1, y)) < 40) clear++
            y += step
        }
        return total > 0 && clear.toFloat() / total > 0.5f
    }

    /** Picks the most vivid colour in the character image, for matching the launcher's glow to it. */
    fun autoAccent(b: Bitmap): Int {
        val bins = FloatArray(12)
        val sums = Array(12) { FloatArray(3) }
        val hsv = FloatArray(3)
        val step = maxOf(1, minOf(b.width, b.height) / 60)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val c = b.getPixel(x, y)
                if (Color.alpha(c) > 128) {
                    Color.colorToHSV(c, hsv)
                    val w = hsv[1] * hsv[2] * hsv[1]
                    if (hsv[1] > 0.35f && hsv[2] > 0.35f) {
                        val bin = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
                        bins[bin] += w
                        sums[bin][0] += Color.red(c) * w; sums[bin][1] += Color.green(c) * w; sums[bin][2] += Color.blue(c) * w
                    }
                }
                x += step
            }
            y += step
        }
        val best = bins.indices.maxByOrNull { bins[it] } ?: return Color.rgb(64, 217, 255)
        if (bins[best] <= 0f) return Color.rgb(64, 217, 255)
        val r = sums[best][0] / bins[best]; val g = sums[best][1] / bins[best]; val bl = sums[best][2] / bins[best]
        // push to a bright, saturated version so it reads as a glow
        Color.RGBToHSV(r.toInt(), g.toInt(), bl.toInt(), hsv)
        hsv[1] = maxOf(hsv[1], 0.65f); hsv[2] = maxOf(hsv[2], 0.95f)
        return Color.HSVToColor(hsv)
    }

    // ---------- per-app character icons ----------

    private fun iconDir(ctx: Context) = File(ctx.filesDir, "icons").apply { mkdirs() }
    private fun iconFile(ctx: Context, appKey: String) = File(iconDir(ctx), appKey.replace('/', '_').replace(Regex("[^A-Za-z0-9._-]"), "_") + ".png")

    fun customIcon(ctx: Context, appKey: String): Bitmap? {
        val f = iconFile(ctx, appKey)
        return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    fun saveIcon(ctx: Context, appKey: String, bmp: Bitmap) {
        iconFile(ctx, appKey).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bumpVersion(ctx)
    }

    fun clearIcon(ctx: Context, appKey: String) {
        iconFile(ctx, appKey).delete()
        bumpVersion(ctx)
    }
}
