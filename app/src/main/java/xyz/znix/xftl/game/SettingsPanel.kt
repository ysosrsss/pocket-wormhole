package xyz.znix.xftl.game

import xyz.znix.xftl.Constants
import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.f
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.AndroidPlatform
import xyz.znix.xftl.sys.PlatformSpecific
import kotlin.math.roundToInt

/** The same application settings on the title screen and in the pause menu. */
class SettingsPanel(
    private val titleFont: SILFontLoader,
    private val textFont: SILFontLoader,
    private val buttonFont: SILFontLoader,
    private val profile: SaveProfile,
    private val keybinds: (() -> Unit)? = null,
    private val close: () -> Unit
) {
    private enum class Tab(val label: String) {
        SOUND("SOUND"), GAMEPLAY("GAMEPLAY"), SAVES("SAVES"), APP("APP")
    }
    private class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val action: (Int) -> Unit)
    private val hits = ArrayList<Hit>()
    private var tab = Tab.SOUND
    private var details: Pair<String, String>? = null
    private var firstLine = 0
    private var status: String? = null

    fun draw(g: Graphics, mouseX: Int, mouseY: Int) {
        hits.clear()
        g.colour = Colour(0, 0, 0, 160)
        g.fillRect(0, 0, 1280, 720)
        g.colour = Constants.SECTOR_CUTOUT_TEXT
        Buttons.drawRounded(g, X - 2, Y - 2, W + 4, H + 4, 7)
        g.colour = BACKGROUND
        Buttons.drawRounded(g, X, Y, W, H, 6)
        details?.let {
            drawDetails(g, mouseX, mouseY, it)
            return
        }
        titleFont.drawString(X + 24f, Y + 35f, "OPTIONS", Constants.SECTOR_CUTOUT_TEXT)
        for ((i, item) in Tab.values().withIndex()) {
            button(g, mouseX, mouseY, X + 24 + i * 190, Y + 55, 182, 38, item.label, tab == item) {
                tab = item
                status = null
            }
        }
        when (tab) {
            Tab.SOUND -> {
                volume(g, mouseX, mouseY, CONTENT_Y, "Sound effects", profile.soundVolume) {
                    profile.soundVolume = it
                    profile.markDirty()
                }
                volume(g, mouseX, mouseY, CONTENT_Y + 88, "Music", profile.musicVolume) {
                    profile.musicVolume = it
                    profile.markDirty()
                }
                paragraph("Tap a volume bar to adjust the level.", CONTENT_Y + 194)
            }
            Tab.GAMEPLAY -> {
                if (PlatformSpecific.INSTANCE.isTouchUi) {
                    label("Touch command auto-pause", CONTENT_Y)
                    toggle(g, mouseX, mouseY, CONTENT_Y - 24, profile.touchAutoPause) {
                        profile.touchAutoPause = !profile.touchAutoPause
                    }
                    paragraph("Pause while aiming, moving crew or adjusting power.", CONTENT_Y + 32)
                } else if (keybinds != null) {
                    label("Keyboard controls", CONTENT_Y)
                    button(g, mouseX, mouseY, RIGHT - 170, CONTENT_Y - 24, 170, 36, "CONFIGURE") { keybinds.invoke() }
                }
                val y = CONTENT_Y + 120
                label("Game speed button", y)
                toggle(g, mouseX, mouseY, y - 24, profile.gameSpeedButton) {
                    profile.gameSpeedButton = !profile.gameSpeedButton
                }
                paragraph("Show the speed control beside the options wrench.", y + 32)
                label("Maximum game speed", y + 90)
                button(g, mouseX, mouseY, RIGHT - 110, y + 66, 110, 36, "${profile.gameSpeedMax}x",
                    enabled = profile.gameSpeedButton) {
                    val limits = MainGame.GAME_SPEED_LIMITS
                    val i = limits.indexOf(profile.gameSpeedMax)
                    profile.gameSpeedMax = limits[(i + 1) % limits.size]
                }
                button(g, mouseX, mouseY, X + 24, y + 125, 180, 34, "ABOUT SPEED") {
                    showDetails("Game speed", SPEED_DESCRIPTION)
                }
            }
            Tab.SAVES -> {
                val sync = PlatformSpecific.INSTANCE.saveSync
                if (sync != null) {
                    label("PC save folder", CONTENT_Y)
                    button(g, mouseX, mouseY, RIGHT - 150, CONTENT_Y - 24, 150, 36,
                        if (sync.enabled) "MANAGE" else "SET UP") { sync.showSettings() }
                    paragraph("Exchange your current run with FTL on a PC through a folder. " +
                        "Syncthing transfers the file between your devices.", CONTENT_Y + 40)
                }
                paragraph("To move a run manually, use EXPORT SAVE / IMPORT SAVE in the pause menu. " +
                    "For profile import and export, open STATS on the main menu.", CONTENT_Y + 160)
            }
            Tab.APP -> {
                val updates = PlatformSpecific.INSTANCE.updates
                if (updates != null) {
                    label("Look for updates", CONTENT_Y)
                    toggle(g, mouseX, mouseY, CONTENT_Y - 24, updates.enabled) { updates.enabled = !updates.enabled }
                    paragraph("Check for a new Pocket Wormhole+ release when the app starts.", CONTENT_Y + 32)
                    button(g, mouseX, mouseY, X + 24, CONTENT_Y + 62, 150, 36, "CHECK NOW") { updates.checkNow() }
                    button(g, mouseX, mouseY, X + 186, CONTENT_Y + 62, 220, 36, "ABOUT UPDATES") {
                        showDetails("App updates", UPDATES_DESCRIPTION)
                    }
                }
                if (PlatformSpecific.INSTANCE.saveTransfer != null) {
                    label("Session logs", CONTENT_Y + 172)
                    button(g, mouseX, mouseY, RIGHT - 150, CONTENT_Y + 148, 150, 36, "SEND LOG") { exportLogs() }
                    paragraph("Save recent session logs to a file for a bug report.", CONTENT_Y + 204)
                }
            }
        }
        status?.let { paragraph(it, Y + H - 78) }
        button(g, mouseX, mouseY, RIGHT - 150, Y + H - 50, 150, 34, "CLOSE") { escapePressed() }
    }

    private fun label(text: String, y: Int) {
        textFont.drawString(X + 24f, y.f, text, Constants.SECTOR_CUTOUT_TEXT)
    }

    private fun paragraph(text: String, y: Int) {
        for ((i, line) in textFont.wrapString(text, W - 48).withIndex()) {
            textFont.drawString(X + 24f, y + i * textFont.lineSpacing.f, line, MUTED)
        }
    }

    private fun volume(g: Graphics, mouseX: Int, mouseY: Int, y: Int, name: String, value: Float, set: (Float) -> Unit) {
        label(name, y)
        val x = X + 260
        val width = 360
        val top = y - 24
        val hovering = mouseX in x until x + width && mouseY in top until top + 40
        g.colour = if (hovering) Constants.UI_BUTTON_HOVER else Constants.SECTOR_CUTOUT_TEXT
        g.drawRect(x, top, width, 36)
        g.fillRect(x + 5, top + 15, width - 10, 6)
        val handleX = x + 5 + ((width - 25) * value).roundToInt()
        g.fillRect(handleX, top + 5, 15, 26)
        textFont.drawString(x + width + 20f, y.f, "${(value * 100).roundToInt()}%", Constants.SECTOR_CUTOUT_TEXT)
        hits += Hit(x, top, width, 40) { px -> set(((px - x - 12.5f) / (width - 25)).coerceIn(0f, 1f)) }
    }

    private fun toggle(g: Graphics, mouseX: Int, mouseY: Int, y: Int, on: Boolean, action: () -> Unit) {
        button(g, mouseX, mouseY, RIGHT - 110, y, 110, 36, if (on) "ON" else "OFF", on, action = action)
    }

    private fun button(
        g: Graphics, mouseX: Int, mouseY: Int, x: Int, y: Int, w: Int, h: Int,
        text: String, selected: Boolean = false, enabled: Boolean = true, action: () -> Unit
    ) {
        val hovering = enabled && mouseX in x until x + w && mouseY in y until y + h
        g.colour = if (enabled) Constants.SECTOR_CUTOUT_TEXT else MUTED
        Buttons.drawRounded(g, x, y, w, h, 4)
        g.colour = when {
            selected -> Constants.UI_BUTTON_HOVER
            hovering -> HOVER
            else -> BACKGROUND
        }
        Buttons.drawRounded(g, x + 2, y + 2, w - 4, h - 4, 3)
        val colour = when {
            !enabled -> MUTED
            selected -> Constants.JUMP_DISABLED_TEXT
            hovering -> Constants.UI_BUTTON_HOVER
            else -> Constants.SECTOR_CUTOUT_TEXT
        }
        buttonFont.drawStringCentred(x.f, y + h / 2f + 7f, w.f, text, colour)
        if (enabled) hits += Hit(x, y, w, h) { action() }
    }

    private fun showDetails(title: String, text: String) {
        details = title to text
        firstLine = 0
    }

    private fun drawDetails(g: Graphics, mouseX: Int, mouseY: Int, info: Pair<String, String>) {
        titleFont.drawString(X + 24f, Y + 35f, info.first, Constants.SECTOR_CUTOUT_TEXT)
        val lines = textFont.wrapString(info.second, W - 48)
        val visible = (H - 140) / textFont.lineSpacing
        for (i in 0 until visible) {
            val line = lines.getOrNull(firstLine + i) ?: break
            textFont.drawString(X + 24f, Y + 82f + i * textFont.lineSpacing, line, Constants.SECTOR_CUTOUT_TEXT)
        }
        if (lines.size > visible) {
            button(g, mouseX, mouseY, X + 24, Y + H - 50, 100, 34, "UP", enabled = firstLine > 0) {
                firstLine = (firstLine - visible).coerceAtLeast(0)
            }
            button(g, mouseX, mouseY, X + 136, Y + H - 50, 100, 34, "DOWN", enabled = firstLine + visible < lines.size) {
                firstLine += visible
            }
        }
        button(g, mouseX, mouseY, RIGHT - 150, Y + H - 50, 150, 34, "BACK") { escapePressed() }
    }

    private fun exportLogs() {
        val transfer = PlatformSpecific.INSTANCE.saveTransfer ?: return
        val logs = java.io.File(AndroidPlatform.baseDir.toString())
            .listFiles { f -> f.name.startsWith("log-session-") }?.sortedBy { it.name }?.takeLast(3) ?: emptyList()
        if (logs.isEmpty()) {
            status = "No logs found."
            return
        }
        val out = StringBuilder()
        for (log in logs) {
            val text = log.readText()
            out.append("===== ").append(log.name).append('\n')
            out.append(text.takeLast(300_000)).append('\n')
        }
        transfer.exportFile(out.toString().toByteArray(), "pw-test-logs.txt")
        status = "Saving ${logs.size} logs..."
    }

    fun mouseClicked(x: Int, y: Int) {
        val hit = hits.firstOrNull { x in it.x until it.x + it.w && y in it.y until it.y + it.h } ?: return
        try {
            hit.action(x)
            // A tab / detail / close may have replaced the screen. Discard its old hit areas.
            hits.clear()
        } catch (e: Exception) {
            status = "Error: ${e.message}"
        }
    }

    fun escapePressed() {
        hits.clear()
        if (details != null) details = null else close()
    }

    companion object {
        private const val W = 800
        private const val H = 540
        private const val X = (1280 - W) / 2
        private const val Y = (720 - H) / 2
        private const val RIGHT = X + W - 24
        private const val CONTENT_Y = Y + 150
        private val BACKGROUND = Colour(32, 46, 51, 250)
        private val HOVER = Colour(55, 78, 86, 255)
        private val MUTED = Colour(160, 171, 171)
        private const val SPEED_DESCRIPTION =
            "When ON, a button beside the options wrench speeds up the whole game: combat, fires, oxygen " +
                "and crew run faster together. Tap it to cycle speeds up to the maximum chosen here. " +
                "Long-press it to return to 1x.\n\n" +
                "Red text means your phone cannot keep up with that speed. Lower the maximum if this happens often.\n\n" +
                "This optional feature is not part of vanilla FTL. It is OFF by default."
        private const val UPDATES_DESCRIPTION =
            "When ON, the app checks GitHub for a newer Pocket Wormhole+ release at startup. Update downloads it, " +
                "then Android asks you to confirm installation. The first time, Android may require permission " +
                "to install apps from Pocket Wormhole+.\n\n" +
                "Not now asks again next time. Skip this version waits for a newer release. Your saves and " +
                "settings are kept. The download is checked against GitHub's size and checksum.\n\n" +
                "CHECK NOW checks immediately, even with automatic checking OFF."
    }
}
