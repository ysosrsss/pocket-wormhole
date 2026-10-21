package xyz.znix.xftl.game

import xyz.znix.xftl.Constants
import xyz.znix.xftl.Datafile
import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.f
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.modding.ModManager
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rendering.WindowRenderer
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.sys.ResourceContext
import xyz.znix.xftl.ui.Label
import xyz.znix.xftl.ui.SpecDeserialiser
import xyz.znix.xftl.ui.UIProvider
import xyz.znix.xftl.ui.Widget
import xyz.znix.xftl.ui.WidgetContainer
import xyz.znix.xftl.sys.XftlResources

/**
 * The game's title screen, shown when the game opens (issue #2).
 *
 * Vanilla-look layout: the logo in the top-right and a column of menu
 * buttons under it, right-aligned. 'Continue' only appears when there's a
 * saved run to resume; STATS opens the [ProfilePanel]; the screens we don't
 * implement yet (tutorial, options, credits) are drawn like vanilla but do
 * nothing.
 */
class MainMenuState(private val main: MainGame, private val datafile: Datafile) : MainGame.GameState() {
    private val resourceContext = ResourceContext()
    private val images = HashMap<String, Image>()

    private val background = getImg("img/main_menus/main_base2.png")
    private val logo = getImg("img/main_menus/main_FTL2.png")

    private val buttons = ArrayList<MenuButton>()

    // All the buttons share a right edge (under the logo), and are stacked
    // one image-height apart - the images include their own visual padding.
    private val buttonsRightX = 1216
    private val firstButtonY = 274
    private val buttonSpacing = 62

    private var mouseX = 0
    private var mouseY = 0

    private var musicStarted = false

    private var modsPanel: ModsPanel? = null

    // Vanilla's "Are you sure you want to start a new game? Your previous
    // progress will be lost." box, shown by NEW GAME while there's a saved
    // run, with CONFIRM and CANCEL buttons.
    private var confirmNewGame: WidgetContainer? = null
    private var confirmX = 0
    private var confirmY = 0

    private var profilePanel: ProfilePanel? = null

    init {
        var y = firstButtonY

        // 'Continue' only exists if there's a saved run to go back to.
        if (main.hasRunSave()) {
            buttons.add(makeButton("continue", y) { main.continueSavedRun() })
            y += buttonSpacing
        }

        buttons.add(makeButton("start", y) {
            if (main.hasRunSave()) showConfirmNewGame() else main.switchToShipSelect()
        })
        y += buttonSpacing

        // Apart from STATS (the profile), these screens don't exist yet -
        // draw them like vanilla, but they don't do anything when clicked.
        for (name in listOf("tutorial", "stats", "options", "credits")) {
            val action: (() -> Unit)? = if (name == "stats") ({ openProfilePanel() }) else null
            buttons.add(makeButton(name, y, action))
            y += buttonSpacing
        }

        buttons.add(makeButton("quit", y) { main.exitGame() })
    }

    override fun shutdown() {
        resourceContext.freeAll()
    }

    override fun update(container: GameContainer, delta: Float) {
        mouseX = container.input.mouseX
        mouseY = container.input.mouseY

        profilePanel?.update()

        // Play the title music. Switching is a one-off, but the streaming
        // refills need pumping every frame like InGameState does.
        val sounds = main.gameContent.sounds
        if (!musicStarted) {
            musicStarted = true
            sounds.switchMusicList(listOf(sounds.getTrack("title")))
        }
        (sounds as? RealSoundManager)?.let {
            it.updateLoopedSounds(false)
            it.updateMusic(delta)
        }

        modsPanel?.update()
    }

    override fun render(container: GameContainer, g: Graphics) {
        background.draw(0f, 0f)
        logo.draw(LOGO_X.f, LOGO_Y.f)

        for (button in buttons) {
            // Nothing lights up behind the confirmation box.
            button.draw(g, if (confirmNewGame == null) mouseX else -1, mouseY)
        }

        drawModsButton(g)
        modsPanel?.draw(g, mouseX, mouseY)

        confirmNewGame?.let {
            g.colour = CONFIRM_DIM
            g.fillRect(0, 0, container.width, container.height)
            it.updateMouse(mouseX - confirmX, mouseY - confirmY)
            g.pushTransform()
            g.translate(confirmX.f, confirmY.f)
            it.draw(g)
            g.popTransform()
        }

        profilePanel?.draw(g, mouseX, mouseY)
    }

    // Vanilla has no mods button: this is a small one in the bottom-left
    // corner, opening the mod list (Android's mods folder is otherwise out
    // of the user's reach).
    private fun drawModsButton(g: Graphics) {
        val hovering = modsPanel == null && isOverModsButton(mouseX, mouseY)
        g.colour = Constants.SECTOR_CUTOUT_TEXT
        Buttons.drawRounded(g, MODS_X, MODS_Y, MODS_W, MODS_H, 4)
        g.colour = if (hovering) MODS_HOVER_FILL else MODS_FILL
        Buttons.drawRounded(g, MODS_X + 2, MODS_Y + 2, MODS_W - 4, MODS_H - 4, 3)
        val colour = if (hovering) Constants.UI_BUTTON_HOVER else Constants.SECTOR_CUTOUT_TEXT
        modsButtonFont.drawStringCentred(MODS_X.f, MODS_Y + MODS_H / 2f + 7f, MODS_W.f, modsButtonLabel, colour)
    }

    private fun isOverModsButton(x: Int, y: Int): Boolean =
        x in MODS_X until MODS_X + MODS_W && y in MODS_Y until MODS_Y + MODS_H

    private val modsButtonFont by lazy { buttonFont() }

    // Counted once: the mods only change from the panel, which needs a restart.
    private val modsButtonLabel by lazy {
        val enabled = try {
            ModManager.list().count { it.enabled }
        } catch (e: Exception) {
            0
        }
        if (enabled == 0) "MODS" else "MODS ($enabled)"
    }

    private fun getFont(name: String): SILFontLoader =
        SILFontLoader(resourceContext, datafile, datafile["fonts/$name.font"])

    private fun buttonFont(): SILFontLoader = getFont("HL2").apply { scale = 2f }

    private fun showConfirmNewGame() {
        val widget = SpecDeserialiser(uiProvider).load("confirm_new_game").mainWidget
        // Labels don't wrap by themselves: wrap to the box, like vanilla's lines.
        val message = widget.byId["message"] as Label
        message.text = main.gameContent.translator["confirm_new_game"].split("\n")
            .flatMap { message.font.wrapString(it, CONFIRM_TEXT_WIDTH) }
            .joinToString("\n")
        widget.root.updateSizes()
        widget.root.expandToParent(ConstPoint.ZERO)
        widget.root.updateLayout()
        confirmX = (1280 - widget.root.size.x) / 2
        confirmY = (720 - widget.root.size.y) / 2
        widget.addButtonListener("yes") {
            confirmNewGame = null
            main.switchToShipSelect()
        }
        widget.addButtonListener("no") { confirmNewGame = null }
        confirmNewGame = widget
    }

    private val uiProvider = object : UIProvider {
        private val renderer by lazy {
            WindowRenderer(getImg("img/window_base.png"), getImg("img/window_outline.png"), getImg("img/window_mask.png"))
        }

        override fun getFont(name: String): SILFontLoader =
            SILFontLoader(resourceContext, datafile, datafile["fonts/$name.font"])

        override fun getImg(path: String): Image = this@MainMenuState.getImg(path)

        override fun translate(key: String): String? = main.gameContent.translator.translations[key]

        override fun getDebugOutlineColour(widget: Widget): Colour? = null

        override fun getWindowRenderer(): WindowRenderer = renderer
    }

    override fun mouseClicked(button: Int, x: Int, y: Int, clickCount: Int) {
        // The confirmation box is modal.
        confirmNewGame?.let {
            it.mouseClicked(button)
            return
        }

        if (button != Input.MOUSE_LEFT_BUTTON)
            return

        // The mod list is modal.
        modsPanel?.let {
            it.mouseClicked(x, y)
            return
        }

        // The profile panel is modal.
        profilePanel?.let {
            it.mouseClicked(x, y)
            return
        }

        if (isOverModsButton(x, y)) {
            modsPanel = ModsPanel(getFont("JustinFont12Bold"), getFont("JustinFont10"), buttonFont(), main.profile) {
                modsPanel = null
            }
            return
        }

        for (b in buttons) {
            if (b.contains(x, y) && b.action != null) {
                b.action.invoke()
                return
            }
        }
    }

    private fun openProfilePanel() {
        fun font(name: String) = SILFontLoader(resourceContext, datafile, datafile["fonts/$name.font"])
        val families = ShipFamily.FamilyTable(
            com.pocketwormhole.android.SafeXML.builder().build(XftlResources.open("assets/data/xftl_ships.xml")!!)
        )
        profilePanel = ProfilePanel(
            font("JustinFont12Bold"), font("JustinFont10"), font("HL2").apply { scale = 2f },
            main.profile, main.gameContent.achievements, families
        ) { profilePanel = null }
    }

    private fun makeButton(name: String, y: Int, action: (() -> Unit)? = null): MenuButton {
        // The buttons are right-aligned, like vanilla.
        val image = getImg("img/main_menus/${name}_off.png")
        val x = buttonsRightX - image.width
        val hovered = getImg("img/main_menus/${name}_on.png")
        return MenuButton(image, hovered, x, y, action)
    }

    private fun getImg(path: String): Image {
        images[path]?.let { return it }

        val file = datafile.getOrNull(path)
            ?: error("Missing main menu image '$path'")
        val img = datafile.readImage(resourceContext, file)
        images[path] = img
        return img
    }

    private class MenuButton(
        val image: Image,
        val hovered: Image,
        val x: Int,
        val y: Int,
        val action: (() -> Unit)?
    ) {
        fun contains(px: Int, py: Int): Boolean {
            return px in x until x + image.width && py in y until y + image.height
        }

        fun draw(g: Graphics, mouseX: Int, mouseY: Int) {
            // Buttons without an action never light up.
            val img = if (action != null && contains(mouseX, mouseY)) hovered else image
            img.draw(x.f, y.f)
        }
    }

    companion object {
        private const val MODS_X = 24
        private const val MODS_Y = 660
        private const val MODS_W = 140
        private const val MODS_H = 36
        private val MODS_FILL = Colour(32, 46, 51, 230)
        private val MODS_HOVER_FILL = Colour(55, 78, 86, 240)

        private val CONFIRM_DIM = Colour(0, 0, 0, 150)
        private const val CONFIRM_TEXT_WIDTH = 330

        private const val LOGO_X = 805
        private const val LOGO_Y = 62
    }
}
