package xyz.znix.xftl.game

import xyz.znix.xftl.Constants
import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.f
import xyz.znix.xftl.modding.ModManager
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.PlatformSpecific
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.roundToInt

/**
 * The main menu's mod list: install a mod from a file, turn mods on and off,
 * remove them, and restart the game to apply the changes.
 *
 * Android keeps the mods folder in the app's private storage, so without
 * this the user has no way to add a mod at all.
 */
class ModsPanel(
    private val titleFont: SILFontLoader,
    private val textFont: SILFontLoader,
    private val buttonFont: SILFontLoader,
    private val close: () -> Unit
) {
    private val modImport = PlatformSpecific.INSTANCE.modImport

    private val rows = ROWS
    private val lineHeight = (textFont.lineSpacing * textFont.scale).roundToInt() + 2

    private var mods: List<ModManager.Entry> = emptyList()
    private var infos: Map<String, ModManager.Info?> = emptyMap()
    private var firstRow = 0
    private var changed = false
    private var confirmDelete: String? = null
    private var status: String? = null
    private var statusIsError = false

    // Picked files arrive from the platform on another thread.
    private val pending = ConcurrentLinkedQueue<() -> Unit>()

    private class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val action: () -> Unit)

    private val hits = ArrayList<Hit>()

    /** A mod's description, shown in place of the list. */
    private class Details(val title: String, val subtitle: String?, val text: String)

    private var details: Details? = null
    private var detailsFirstLine = 0

    init {
        reload()
    }

    private fun reload() {
        mods = try {
            ModManager.list()
        } catch (e: Exception) {
            setStatus("Couldn't read the mods folder: ${e.message}", true)
            emptyList()
        }
        infos = mods.associate { it.name to ModManager.info(it.name) }
        firstRow = firstRow.coerceIn(0, (mods.size - rows).coerceAtLeast(0))
    }

    fun update() {
        while (true) {
            val action = pending.poll() ?: break
            action()
        }
    }

    fun draw(g: Graphics, mouseX: Int, mouseY: Int) {
        hits.clear()

        // Dim the menu behind us.
        g.colour = DIM
        g.fillRect(0, 0, 1280, 720)

        g.colour = Constants.SECTOR_CUTOUT_TEXT
        Buttons.drawRounded(g, X - 2, Y - 2, W + 4, H + 4, 7)
        g.colour = BACKGROUND
        Buttons.drawRounded(g, X, Y, W, H, 6)

        details?.let {
            drawDetails(g, mouseX, mouseY, it)
            return
        }

        titleFont.drawString(X + 20f, Y + 34f, "MODS", Constants.SECTOR_CUTOUT_TEXT)

        val listTop = Y + 54
        if (mods.isEmpty()) {
            val lines = textFont.wrapString(
                "No mods installed. Tap ADD MOD and pick a Slipstream mod file (.ftl or .zip) " +
                        "- for example from your Downloads folder.", W - 40
            )
            for ((i, line) in lines.withIndex())
                textFont.drawString(X + 20f, listTop + 30f + i * lineHeight, line, Constants.SECTOR_CUTOUT_TEXT)
        }

        for (row in 0 until rows) {
            val mod = mods.getOrNull(firstRow + row) ?: break
            val rowY = listTop + row * ROW_H

            g.colour = if (row % 2 == 0) ROW_A else ROW_B
            g.fillRect(X + 12, rowY, W - 24, ROW_H - 4)

            val info = infos[mod.name]
            val nameColour = if (mod.enabled) Constants.SECTOR_CUTOUT_TEXT else DISABLED_TEXT
            drawName(X + 24, rowY + 32, info?.title ?: mod.name, nameColour)
            hits += Hit(X + 12, rowY, NAME_W + 24, ROW_H - 4) { showDetails(mod.name, info) }

            val onX = X + W - 24 - DELETE_W - 12 - TOGGLE_W
            button(g, mouseX, mouseY, onX, rowY + 4, TOGGLE_W, ROW_H - 12, if (mod.enabled) "ON" else "OFF", mod.enabled) {
                ModManager.setEnabled(mod.name, !mod.enabled)
                changed = true
                confirmDelete = null
                reload()
            }

            val deleting = confirmDelete == mod.name
            button(g, mouseX, mouseY, X + W - 24 - DELETE_W, rowY + 4, DELETE_W, ROW_H - 12, if (deleting) "SURE?" else "REMOVE", deleting) {
                if (deleting) {
                    ModManager.delete(mod.name)
                    changed = true
                    confirmDelete = null
                    setStatus("Removed ${mod.name}.", false)
                    reload()
                } else {
                    confirmDelete = mod.name
                }
            }
        }

        // Paging, for long mod lists.
        if (mods.size > rows) {
            val pageY = listTop + rows * ROW_H
            button(g, mouseX, mouseY, X + W / 2 - 110, pageY, 100, 36, "UP", false, firstRow > 0) {
                firstRow = (firstRow - rows).coerceAtLeast(0)
            }
            button(g, mouseX, mouseY, X + W / 2 + 10, pageY, 100, 36, "DOWN", false, firstRow + rows < mods.size) {
                firstRow = (firstRow + rows).coerceAtMost(mods.size - 1)
            }
        }

        status?.let {
            val colour = if (statusIsError) Constants.SYS_ENERGY_BROKEN else Constants.SYS_ENERGY_ACTIVE
            textFont.drawString(X + 20f, Y + H - 126f, fitLine(it, W - 40), colour)
        }
        val note = if (changed) "Restart the game to apply the changes." else "Mods are applied when the game starts. Tap a name for details."
        for ((i, line) in textFont.wrapString(note, W - 40).take(2).withIndex()) {
            textFont.drawString(X + 20f, Y + H - 92f + i * lineHeight, line, DISABLED_TEXT)
        }

        val bottomY = Y + H - 48
        if (modImport != null) {
            button(g, mouseX, mouseY, X + 20, bottomY, 160, 36, "ADD MOD", false) { addMod() }
            button(g, mouseX, mouseY, X + W - 20 - 160 - 16 - 160, bottomY, 160, 36, "RESTART", changed) {
                modImport.restartGame()
            }
        }
        button(g, mouseX, mouseY, X + W - 20 - 160, bottomY, 160, 36, "CLOSE", false) { close() }
    }

    /** Draw a row's name, shortened with an ellipsis if it doesn't fit. */
    private fun drawName(x: Int, y: Int, full: String, colour: Colour) {
        textFont.drawString(x.f, y.f, fitLine(full, NAME_W), colour)
    }

    private fun fitLine(full: String, width: Int, font: SILFontLoader = textFont): String {
        if (font.getWidth(full) <= width) return full
        var lo = 0
        var hi = full.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (font.getWidth(full.take(mid) + "...") <= width) lo = mid else hi = mid - 1
        }
        return full.take(lo) + "..."
    }

    private fun showDetails(name: String, info: ModManager.Info?) {
        val about = listOfNotNull(info?.author?.let { "by $it" }, info?.version?.let { "version $it" }, name)
        showDetails(Details(info?.title ?: name, about.joinToString("  -  "), info?.description ?: "This mod has no description."))
    }

    private fun showDetails(newDetails: Details) {
        details = newDetails
        detailsFirstLine = 0
        confirmDelete = null
    }

    private fun drawDetails(g: Graphics, mouseX: Int, mouseY: Int, d: Details) {
        titleFont.drawString(X + 20f, Y + 34f, fitLine(d.title, W - 40, titleFont), Constants.SECTOR_CUTOUT_TEXT)
        d.subtitle?.let {
            for ((i, line) in textFont.wrapString(it, W - 40).take(2).withIndex()) {
                textFont.drawString(X + 20f, Y + 64f + i * lineHeight, line, DISABLED_TEXT)
            }
        }

        // Long descriptions page, like the mod list.
        val lines = textFont.wrapString(d.text, W - 40)
        val textTop = Y + 112
        val visible = (Y + H - 76 - textTop) / lineHeight
        for (i in 0 until visible) {
            val line = lines.getOrNull(detailsFirstLine + i) ?: break
            textFont.drawString(X + 20f, textTop + 18f + i * lineHeight, line, Constants.SECTOR_CUTOUT_TEXT)
        }

        val bottomY = Y + H - 48
        if (lines.size > visible) {
            button(g, mouseX, mouseY, X + 20, bottomY, 100, 36, "UP", false, detailsFirstLine > 0) {
                detailsFirstLine = (detailsFirstLine - visible).coerceAtLeast(0)
            }
            button(g, mouseX, mouseY, X + 20 + 116, bottomY, 100, 36, "DOWN", false, detailsFirstLine + visible < lines.size) {
                detailsFirstLine += visible
            }
        }
        button(g, mouseX, mouseY, X + W - 20 - 160, bottomY, 160, 36, "BACK", false) { details = null }
    }

    private fun button(
        g: Graphics, mouseX: Int, mouseY: Int,
        x: Int, y: Int, w: Int, h: Int,
        label: String, highlighted: Boolean, enabled: Boolean = true,
        action: () -> Unit
    ) {
        val hovering = enabled && mouseX in x until x + w && mouseY in y until y + h

        g.colour = if (enabled) Constants.SECTOR_CUTOUT_TEXT else DISABLED_TEXT
        Buttons.drawRounded(g, x, y, w, h, 4)
        g.colour = when {
            highlighted -> Constants.UI_BUTTON_HOVER
            hovering -> BUTTON_HOVER_FILL
            else -> BACKGROUND
        }
        Buttons.drawRounded(g, x + 2, y + 2, w - 4, h - 4, 3)

        val textColour = when {
            !enabled -> DISABLED_TEXT
            highlighted -> Constants.JUMP_DISABLED_TEXT
            hovering -> Constants.UI_BUTTON_HOVER
            else -> Constants.SECTOR_CUTOUT_TEXT
        }
        val baseline = y + h / 2f + buttonFont.baselineToTop * buttonFont.scale / 2f
        buttonFont.drawStringCentred(x.f, baseline, w.f, label, textColour)

        if (enabled)
            hits += Hit(x, y, w, h, action)
    }

    private fun addMod() {
        val import = modImport ?: return
        confirmDelete = null
        setStatus("Pick a mod file...", false)
        import.pickMod { name, file ->
            pending += {
                if (name == null || file == null) {
                    status = null
                } else {
                    try {
                        val saved = ModManager.install(name, file)
                        changed = true
                        setStatus("Installed $saved.", false)
                    } catch (e: ModManager.InvalidModException) {
                        setStatus(e.message ?: "Not a mod file.", true)
                    } catch (e: Exception) {
                        setStatus("Couldn't install the mod: ${e.message}", true)
                    }
                    reload()
                    firstRow = (mods.size - rows).coerceAtLeast(0)
                }
            }
        }
    }

    private fun setStatus(text: String, error: Boolean) {
        status = text
        statusIsError = error
    }

    /** The panel is modal: clicks outside its buttons do nothing. */
    fun mouseClicked(x: Int, y: Int) {
        val hit = hits.firstOrNull { x in it.x until it.x + it.w && y in it.y until it.y + it.h } ?: return
        try {
            hit.action()
        } catch (e: Exception) {
            setStatus("Error: ${e.message}", true)
        }
    }

    fun escapePressed() {
        hits.clear()
        if (details != null) details = null else close()
    }

    companion object {
        private const val W = 800
        private const val H = 560
        private const val X = (1280 - W) / 2
        private const val Y = (720 - H) / 2

        private const val ROWS = 6

        private const val ROW_H = 48
        private const val TOGGLE_W = 80
        private const val DELETE_W = 140
        private const val NAME_W = W - 24 - 24 - TOGGLE_W - 12 - DELETE_W - 24

        private val DIM = Colour(0, 0, 0, 160)
        private val BACKGROUND = Colour(32, 46, 51, 250)
        private val ROW_A = Colour(46, 63, 69, 255)
        private val ROW_B = Colour(39, 54, 60, 255)
        private val BUTTON_HOVER_FILL = Colour(55, 78, 86, 255)
        private val DISABLED_TEXT = Colour(205, 216, 212)
    }
}
