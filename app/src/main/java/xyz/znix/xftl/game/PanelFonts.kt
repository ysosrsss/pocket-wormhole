package xyz.znix.xftl.game

import xyz.znix.xftl.SILFontLoader

/** Readable, solid FTL fonts shared by the MODS and OPTIONS panels. */
class PanelFonts(load: (String) -> SILFontLoader) {
    val title = load("JustinFont12Bold").apply { scale = 2f }
    val text = load("JustinFont12Bold").apply { scale = 1.5f }
    val button = load("c&c").apply { scale = 3f }
}
