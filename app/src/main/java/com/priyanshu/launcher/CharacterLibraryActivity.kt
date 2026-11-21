package com.priyanshu.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * CHARACTER LIBRARY — create character profiles from your own images, wallpapers and 3D model
 * files, pick an effect, and choose which character takes over the home screen.
 * Everything imported is copied into this app's private storage on this phone only.
 */
class CharacterLibraryActivity : Activity() {

    private lateinit var list: LinearLayout
    private var editing: CharacterProfile? = null
    private var pendingKind = ""
    private val cyan = Color.rgb(64, 217, 255)

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(3, 8, 14)) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(36), dp(18), dp(36))
        }
        scroll.addView(col)
        col.addView(TextView(this).apply {
            text = "CHARACTER LIBRARY"
            textSize = 24f
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setTextColor(cyan)
            setShadowLayer(16f, 0f, 0f, cyan)
        })
        col.addView(TextView(this).apply {
            text = "Import your own character images, wallpapers and 3D models. They are copied into this app's private storage on this phone, kept separate from the app's built-in art, and never uploaded or shared. Only import artwork you have the right to use."
            textSize = 12.5f
            setTextColor(Color.argb(190, 220, 240, 255))
            setPadding(0, dp(8), 0, dp(14))
        })
        col.addView(button("+  NEW CHARACTER") { newCharacter() })
        col.addView(button("USE REACTOR ONLY (NO CHARACTER)") {
            CharacterStore.setActive(this, null); refresh()
            Toast.makeText(this, "Home screen shows the reactor without a character", Toast.LENGTH_SHORT).show()
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
        col.addView(list)
        setContentView(scroll)
        refresh()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(cyan)
        textSize = 13f
        isAllCaps = false
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.argb(40, 64, 217, 255))
            setStroke(dp(1), Color.argb(160, 64, 217, 255))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun refresh() {
        list.removeAllViews()
        val all = CharacterStore.list(this)
        val activeId = CharacterStore.activeId(this)
        if (all.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No characters yet. Tap NEW CHARACTER, give it a name, then import a character image (a transparent PNG cutout looks best)."
                setTextColor(Color.argb(170, 220, 240, 255))
                setPadding(0, dp(16), 0, 0)
            })
        }
        for (p in all) list.addView(card(p, p.id == activeId))
    }

    private fun card(p: CharacterProfile, active: Boolean): View {
        val accent = if (p.accent != 0) p.accent else cyan
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(6, 18, 28), Color.rgb(2, 6, 10))).apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(if (active) 3 else 1), if (active) accent else Color.argb(90, 64, 217, 255))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.argb(60, 64, 217, 255)) }
        }
        val bmp = CharacterStore.loadBitmap(this, p, p.image, 360)
        if (bmp != null) thumb.setImageBitmap(bmp)
        row.addView(thumb, LinearLayout.LayoutParams(dp(92), dp(122)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        info.addView(TextView(this).apply {
            text = if (active) "${p.name}   ● ACTIVE" else p.name
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (active) accent else Color.WHITE)
        })
        val details = mutableListOf<String>()
        if (p.universe.isNotBlank()) details += "Universe: ${p.universe}"
        details += "Effect: ${p.effect.label}"
        details += when {
            bmp == null -> "Image: none yet"
            CharacterStore.isCutout(bmp) -> "Image: transparent cutout"
            else -> "Image: rectangular (shown as a portrait)"
        }
        details += if (p.wallpaper.isNotBlank()) (if (p.useWallpaper) "Wallpaper: on home screen" else "Wallpaper: stored, not in use") else "Wallpaper: none"
        if (p.model.isNotBlank()) {
            val kb = CharacterStore.fileSize(this, p, p.model) / 1024
            details += "3D model: stored ($kb KB); 3D display comes in a later build"
        }
        info.addView(TextView(this).apply {
            text = details.joinToString("\n")
            textSize = 12f
            setTextColor(Color.argb(200, 210, 230, 245))
        })
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        fun small(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; textSize = 12f; isAllCaps = false; setTextColor(accent)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setStroke(dp(1), withAlpha(accent, 0.6f)) }
            setOnClickListener { onClick() }
        }
        buttons.addView(small(if (active) "Active" else "Activate") {
            if (p.image.isBlank()) {
                Toast.makeText(this, "Import a character image first (Edit)", Toast.LENGTH_SHORT).show()
            } else {
                CharacterStore.setActive(this, p.id); refresh()
                Toast.makeText(this, "${p.name} is now on your home screen", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(small("Edit") { edit(p) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        buttons.addView(small("Delete") {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Delete ${p.name}?")
                .setMessage("Removes this profile and its imported files from the app's storage. Your original files in the gallery are not touched.")
                .setPositiveButton("Delete") { _, _ -> CharacterStore.delete(this, p.id); refresh() }
                .setNegativeButton("Cancel", null).show()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        card.addView(buttons)
        return card
    }

    private fun newCharacter() {
        val input = EditText(this).apply { hint = "Character name"; setSingleLine() }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("New character")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Character" }
                val p = CharacterStore.create(this, name)
                refresh()
                edit(p)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun edit(p: CharacterProfile) {
        editing = p
        val options = arrayOf(
            "Import character image (PNG, JPG, WebP)",
            "Import wallpaper",
            if (p.useWallpaper) "Wallpaper on home screen: ON (tap to turn off)" else "Wallpaper on home screen: OFF (tap to turn on)",
            "Import 3D model (GLB / glTF)",
            "Effect: ${p.effect.label}",
            "Glow colour: ${if (p.accent == 0) "automatic from image" else "custom"}",
            "Size and position on the home screen",
            "Rename / universe"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Edit ${p.name}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickImage("image")
                    1 -> pickImage("wallpaper")
                    2 -> {
                        if (p.wallpaper.isBlank()) Toast.makeText(this, "Import a wallpaper first", Toast.LENGTH_SHORT).show()
                        else { p.useWallpaper = !p.useWallpaper; CharacterStore.save(this, p); refresh() }
                    }
                    3 -> pickModel()
                    4 -> pickEffect(p)
                    5 -> pickAccent(p)
                    6 -> placement(p)
                    7 -> rename(p)
                }
            }
            .setNegativeButton("Done", null).show()
    }

    @Suppress("DEPRECATION")
    private fun pickImage(kind: String) {
        pendingKind = kind
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply { type = "image/*" }
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }
        }
        try { startActivityForResult(intent, REQ_PICK) } catch (_: Exception) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }, REQ_PICK)
        }
    }

    @Suppress("DEPRECATION")
    private fun pickModel() {
        pendingKind = "model"
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("model/gltf-binary", "model/gltf+json", "application/octet-stream", "application/json"))
        }
        startActivityForResult(intent, REQ_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val p = editing ?: return
        val uri = data?.data ?: return
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        if (pendingKind == "model") {
            val lower = (uri.lastPathSegment ?: "").lowercase()
            if (!lower.endsWith(".glb") && !lower.endsWith(".gltf") && !lower.contains("glb") && !lower.contains("gltf")) {
                Toast.makeText(this, "That does not look like a .glb or .gltf file; storing it anyway", Toast.LENGTH_SHORT).show()
            }
        }
        val stored = CharacterStore.importFile(this, uri, p.id, pendingKind)
        if (stored == null) {
            Toast.makeText(this, "Could not import that file", Toast.LENGTH_SHORT).show()
            return
        }
        when (pendingKind) {
            "image" -> {
                p.image = stored
                val bmp = CharacterStore.loadBitmap(this, p, stored, 800)
                if (bmp == null) {
                    Toast.makeText(this, "That image could not be read", Toast.LENGTH_SHORT).show(); return
                }
                val msg = if (CharacterStore.isCutout(bmp)) "Transparent cutout found: the character will stand in the 3D scene"
                else "No transparent background: it will float as a holographic portrait. A PNG cutout looks best."
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (CharacterStore.activeId(this) == null) CharacterStore.setActive(this, p.id)
            }
            "wallpaper" -> { p.wallpaper = stored; p.useWallpaper = true }
            "model" -> p.model = stored
        }
        CharacterStore.save(this, p)
        refresh()
    }

    private fun pickEffect(p: CharacterProfile) {
        val effects = CharEffect.values()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Effect around ${p.name}")
            .setItems(effects.map { it.label }.toTypedArray()) { _, which ->
                p.effect = effects[which]; CharacterStore.save(this, p); refresh()
            }.show()
    }

    private fun pickAccent(p: CharacterProfile) {
        val names = arrayOf("Automatic (from the image)", "Cyan", "Red", "Gold", "Green", "Purple", "Orange", "White")
        val colours = intArrayOf(0, Color.rgb(64, 217, 255), Color.rgb(255, 60, 60), Color.rgb(255, 205, 60),
            Color.rgb(80, 255, 140), Color.rgb(170, 110, 255), Color.rgb(255, 130, 40), Color.rgb(235, 240, 255))
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Glow colour")
            .setItems(names) { _, which -> p.accent = colours[which]; CharacterStore.save(this, p); refresh() }
            .show()
    }

    private fun placement(p: CharacterProfile) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), 0) }
        val sizeLabel = TextView(this).apply { setTextColor(Color.WHITE) }
        val size = SeekBar(this).apply { max = 90; progress = ((p.size - 0.5f) * 100).toInt().coerceIn(0, 90) }
        val liftLabel = TextView(this).apply { setTextColor(Color.WHITE) }
        val lift = SeekBar(this).apply { max = 40; progress = ((p.lift + 0.2f) * 100).toInt().coerceIn(0, 40) }
        fun labels() {
            sizeLabel.text = "Size: ${50 + size.progress}%"
            liftLabel.text = "Height on screen: ${lift.progress - 20}"
        }
        labels()
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) = labels()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        size.setOnSeekBarChangeListener(listener)
        lift.setOnSeekBarChangeListener(listener)
        box.addView(sizeLabel); box.addView(size); box.addView(liftLabel); box.addView(lift)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Size and position")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                p.size = 0.5f + size.progress / 100f
                p.lift = (lift.progress - 20) / 100f
                CharacterStore.save(this, p); refresh()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun rename(p: CharacterProfile) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), 0) }
        val name = EditText(this).apply { setText(p.name); hint = "Name"; setSingleLine() }
        val uni = EditText(this).apply { setText(p.universe); hint = "Universe or team (optional)"; setSingleLine() }
        box.addView(name); box.addView(uni)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Rename")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                p.name = name.text.toString().trim().ifBlank { p.name }
                p.universe = uni.text.toString().trim()
                CharacterStore.save(this, p); refresh()
            }
            .setNegativeButton("Cancel", null).show()
    }

    companion object { private const val REQ_PICK = 41 }
}
