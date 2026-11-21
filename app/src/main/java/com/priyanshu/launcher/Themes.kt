package com.priyanshu.launcher

import android.graphics.Color

/** Which moving background a theme uses. */
enum class Fx { NONE, STARS, EMBERS, AURORA, GRID, RAIN, BUBBLES, VOLT, PETALS, GOLD }

/** One complete look for the launcher: colours, font and animated background. */
class LauncherTheme(
    val name: String,
    val tagline: String,
    val bg: IntArray,          // background gradient, top to bottom
    val accent: Int,           // main highlight colour
    val accent2: Int,          // second highlight colour
    val text: Int,             // normal text colour
    val fx: Fx,                // animated background effect
    val font: String,          // system font family
    val wallpaper: Boolean = false  // true = show the phone wallpaper instead of a gradient
)

private fun c(hex: String) = Color.parseColor(hex)

object Themes {
    val all: List<LauncherTheme> = listOf(
        LauncherTheme(
            "Classic", "Your own wallpaper, clean and minimal",
            intArrayOf(c("#000000"), c("#000000")), c("#FFFFFF"), c("#B0BEC5"), c("#FFFFFF"),
            Fx.NONE, "sans-serif-light", wallpaper = true
        ),
        LauncherTheme(
            "Holo Core", "Flagship 3D reactor command centre",
            intArrayOf(c("#00060C"), c("#021420"), c("#042233")), c("#40D9FF"), c("#B3F5FF"), c("#E6FCFF"),
            Fx.STARS, "sans-serif-light"
        ),
        LauncherTheme(
            "Nebula", "Twinkling stars and shooting stars",
            intArrayOf(c("#0B0420"), c("#1A0B3D"), c("#2B1055")), c("#B388FF"), c("#64B5F6"), c("#EDE7F6"),
            Fx.STARS, "sans-serif-thin"
        ),
        LauncherTheme(
            "Ember", "Glowing sparks rising from a fire",
            intArrayOf(c("#120402"), c("#2A0A04"), c("#4A1405")), c("#FF7A1A"), c("#FFD54F"), c("#FFF3E0"),
            Fx.EMBERS, "sans-serif-condensed"
        ),
        LauncherTheme(
            "Aurora", "Northern lights flowing across the sky",
            intArrayOf(c("#02101A"), c("#04212B"), c("#062E2E")), c("#4DFFB4"), c("#4FC3F7"), c("#E0F7FA"),
            Fx.AURORA, "sans-serif-light"
        ),
        LauncherTheme(
            "Synthwave", "Retro neon sun and endless grid",
            intArrayOf(c("#0D0221"), c("#261447"), c("#541B6B")), c("#FF2E97"), c("#00F0FF"), c("#FCE4FF"),
            Fx.GRID, "sans-serif-condensed"
        ),
        LauncherTheme(
            "Code Rain", "Falling green hacker code",
            intArrayOf(c("#000000"), c("#010A03"), c("#021206")), c("#39FF14"), c("#CCFFCC"), c("#C8FFC8"),
            Fx.RAIN, "monospace"
        ),
        LauncherTheme(
            "Deep Ocean", "Bubbles and light rays under the sea",
            intArrayOf(c("#001020"), c("#002240"), c("#003A63")), c("#4FC3F7"), c("#80DEEA"), c("#E1F5FE"),
            Fx.BUBBLES, "sans-serif-light"
        ),
        LauncherTheme(
            "Thunder", "Electric storm with lightning strikes",
            intArrayOf(c("#05060F"), c("#0B1030"), c("#141A4A")), c("#7DF9FF"), c("#FFFFFF"), c("#E3F2FD"),
            Fx.VOLT, "sans-serif-medium"
        ),
        LauncherTheme(
            "Sakura", "Cherry blossom petals drifting down",
            intArrayOf(c("#1A0A14"), c("#2E1024"), c("#4A1838")), c("#FF8FB8"), c("#FFD1E3"), c("#FFF0F5"),
            Fx.PETALS, "serif"
        ),
        LauncherTheme(
            "Midnight Gold", "Black and gold with sparkling dust",
            intArrayOf(c("#050505"), c("#0E0B05"), c("#1A1406")), c("#FFD700"), c("#FFF3B0"), c("#FFF8E1"),
            Fx.GOLD, "serif"
        )
    )
}
