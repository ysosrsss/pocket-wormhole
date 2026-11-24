package xyz.znix.xftl.game

import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.sys.PlatformSpecific

/** In-flight host for the same settings panel used by the title screen. */
class OptionsWindow(game: InGameState, close: () -> Unit) : Window() {
    private val fonts = PanelFonts { game.getFont(it) }
    private val panel = SettingsPanel(
        fonts.title, fonts.text, fonts.button,
        game.mainGame.profile,
        if (PlatformSpecific.INSTANCE.isTouchUi) null else ({ game.shipUI.showKeybindWindow() }),
        close
    )
    private var mouseX = 0
    private var mouseY = 0

    override val size: IPoint = ConstPoint(1280, 720)
    override val appliesSelfTint = true

    override fun draw(g: Graphics) = panel.draw(g, mouseX, mouseY)

    override fun updateUI(x: Int, y: Int) {
        mouseX = x
        mouseY = y
    }

    override fun mouseClick(button: Int, x: Int, y: Int) {
        if (button == Input.MOUSE_LEFT_BUTTON) panel.mouseClicked(x, y)
    }

    override fun escapePressed() = panel.escapePressed()
}
