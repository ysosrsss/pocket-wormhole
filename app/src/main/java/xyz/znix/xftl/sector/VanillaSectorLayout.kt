package xyz.znix.xftl.sector

import xyz.znix.xftl.vanillasave.VanillaRng
import kotlin.math.sqrt

/**
 * A sector's beacon map the way vanilla FTL 1.6.14 generates it
 * (StarMap::GenerateMap, GenerateEvents and GenerateNebulas): from the same
 * layout seed it gives the same beacons, start and exit beacons, nebulas and
 * the same event at every beacon. Vanilla saves store the seed, so a loaded
 * vanilla run gets its own map back.
 *
 * Not the last stand, which vanilla lays out differently.
 */
class VanillaSectorLayout(
    private val walker: VanillaEventWalker,
    seed: Int,

    /** The sector type's name in sector_data.xml (the first sector is STANDARD_SPACE). */
    sectorName: String,

    /** The zero-based sector number, for the crew found in events. */
    level: Int,
) {
    class Beacon(val cell: Int, val x: Int, val y: Int) {
        val links = ArrayList<Beacon>()
        var event: VanillaEventWalker.Made? = null
        var isExit = false
        var nebula = false
    }

    /** The beacons, in vanilla's order (the order vanilla saves list them in). */
    val beacons = ArrayList<Beacon>()
    val start: Beacon
    val exit: Beacon

    /** The rebel fleet's starting height, vanilla's fleetFudge. */
    val fleetFudge: Int

    /** Where vanilla draws its nebula images: x, y, width, height. */
    val nebulaRects = ArrayList<IntArray>()

    private val rng = VanillaRng(seed)

    init {
        walker.setSector(sectorName)
        fleetFudge = rng.next() % 250 + 50
        var foundStart: Beacon
        var foundExit: Beacon
        while (true) {
            // StarMap::PopulateGrid, cell by cell: maybe skipped, else a beacon
            // somewhere in the cell, linked to the beacons of the cells around it
            beacons.clear()
            val cells = HashMap<Int, MutableList<Beacon>>()
            var tried = 0
            var skipped = 0
            for (cx in 0 until 6) for (cy in 0 until 4) {
                val key = cx * 4 + cy
                if (cells.containsKey(key)) continue
                if (rng.next() % 5 == 0) {
                    skipped++
                    if (tried / skipped > 4) {
                        cells[key] = ArrayList()
                        tried++
                        continue
                    }
                }
                rng.next() // the beacon's animation phase (Location's constructor)
                val bx = cx * 110 + 10 + rng.next() % 90
                var by = (cy * 110 + 10 + rng.next() % 90).coerceAtMost(415)
                if (cx > 3 && cy == 0 && by < 30) by = 30
                val b = Beacon(key, bx, by)
                beacons += b
                cells[key] = arrayListOf(b)
                for (dx in -1..1) for (dy in -1..1) {
                    if (cx + dx !in 0 until 6 || cy + dy !in 0 until 4) continue
                    for (o in cells[(cx + dx) * 4 + cy + dy] ?: continue) {
                        if (o === b) continue
                        val d = sqrt(((o.x - b.x) * (o.x - b.x) + (o.y - b.y) * (o.y - b.y)).toDouble())
                        if (d > 165.0) continue
                        b.links += o
                        o.links += b
                    }
                }
                tried++
            }

            // Every beacon reachable from the first one, or start again
            val seen = HashSet<Beacon>()
            fun visit(b: Beacon) {
                if (seen.add(b)) b.links.forEach { visit(it) }
            }
            visit(beacons[0])
            if (seen.size != beacons.size) continue

            val s = cells[rng.next() % 4]?.firstOrNull() ?: beacons[0]
            s.event = walker.get(walker.sectorStart[sectorName] ?: "START_BEACON", level, rng)

            // The exit: a random beacon in the two right columns, at least
            // five beacons along the path from the start; 16 tries, or start again
            var e: Beacon? = null
            for (tries in 0 until 16) {
                var cand: Beacon?
                do {
                    val ey = rng.next() % 4
                    val ex = rng.next() % 2 + 4
                    cand = cells.getOrPut(ex * 4 + ey) { ArrayList() }.firstOrNull()
                } while (cand == null)
                if (hops(s, cand!!) + 1 > 4) {
                    e = cand
                    break
                }
            }
            if (e == null) {
                s.event = null
                continue
            }
            foundStart = s
            foundExit = e
            break
        }
        start = foundStart
        exit = foundExit
        exit.isExit = true
        exit.event = walker.get("FINISH_BEACON", level, rng)
        generateEvents(walker.sectorEvents[sectorName].orEmpty(), level)
    }

    private fun hops(from: Beacon, to: Beacon): Int {
        val dist = HashMap<Beacon, Int>()
        val queue = ArrayDeque<Beacon>()
        dist[from] = 0
        queue += from
        while (queue.isNotEmpty()) {
            val b = queue.removeFirst()
            for (n in b.links) if (n !in dist) {
                dist[n] = dist[b]!! + 1
                queue += n
            }
        }
        return dist[to] ?: -1
    }

    /** RandomAmount: min..max (no chance on sector events). */
    private fun amount(min: Int, max: Int) = if (max == 0) min else min + rng.next() % (max + 1 - min)

    /**
     * StarMap::GenerateEvents: nebulas first (all the sector's NEBULA* events,
     * placed with nebula images), then the other events in sector_data order on
     * random beacons still without one, then NEUTRAL on the rest.
     */
    private fun generateEvents(list: List<Triple<String, Int, Int>>, level: Int) {
        val pool = beacons.toMutableList()
        val nebNames = ArrayList<String>()
        val rest = ArrayList<Triple<String, Int, Int>>()
        for (e in list) {
            if ("NEBULA" in e.first) repeat(amount(e.second, e.third)) { nebNames += e.first }
            else rest += e
        }
        if (nebNames.isNotEmpty()) generateNebulas(nebNames, level)

        for (e in rest) {
            val n = amount(e.second, e.third)
            if (n > 0) {
                if (pool.isEmpty()) return
                var made = 0
                while (true) {
                    val b = pool.removeAt(rng.next() % pool.size)
                    if (b.event == null) {
                        made++
                        b.event = walker.get(e.first, level, rng)
                    }
                    if (made >= n) break
                    if (pool.isEmpty()) return
                }
            }
            if (pool.isEmpty()) return
        }
        for (b in pool) if (b.event == null) b.event = walker.get("NEUTRAL", level, rng)
    }

    /**
     * StarMap::GenerateNebulas: nebula images placed around a random beacon,
     * then next to the ones already placed; the beacons inside get the nebula
     * events (the exit becomes FINISH_BEACON_NEBULA).
     */
    private fun generateNebulas(nebNames: MutableList<String>, level: Int) {
        val imgs = if (nebNames.size < 6) NEBULA_SMALL else NEBULA_LARGE
        fun img() = imgs[Integer.remainderUnsigned(rng.next(), imgs.size)]
        var im = img()
        val cand = beacons.filter { !it.nebula }.toMutableList()
        while (cand.size < nebNames.size + 4 && nebNames.isNotEmpty())
            nebNames.removeAt(Integer.remainderUnsigned(rng.next(), nebNames.size))
        if (cand.isEmpty() || nebNames.isEmpty()) return
        var c = cand[rng.next() % cand.size]
        var x = c.x - im.first / 2
        var y = c.y - im.second / 2
        var fails = 0
        do {
            var any = false
            val it = cand.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (!(x + 5 < b.x && b.x < x + im.first - 5 && y + 5 < b.y && b.y < y + im.second - 5)) continue
                b.nebula = true
                if (b.event == null) {
                    val name = if (nebNames.isEmpty()) "NEBULA"
                    else nebNames.removeAt(Integer.remainderUnsigned(rng.next(), nebNames.size))
                    b.event = walker.get(name, level, rng)
                } else if (b.isExit) {
                    b.event = walker.get("FINISH_BEACON_NEBULA", level, rng)
                }
                it.remove()
                any = true
            }
            if (any) nebulaRects += intArrayOf(x, y, im.first, im.second) else fails++
            // Vanilla would divide by zero here; we just stop
            if (cand.isEmpty()) break
            if (fails < 21 && nebulaRects.isNotEmpty()) {
                val p = nebulaRects[Integer.remainderUnsigned(rng.next(), nebulaRects.size)]
                im = img()
                x = p[0] + rng.next() % (p[2] + im.first) - im.first
                y = rng.next() % (p[3] + im.second) + p[1] - im.second
            } else {
                fails = 0
                c = cand[rng.next() % cand.size]
                x = c.x - im.first / 2
                y = c.y - im.second / 2
            }
        } while (nebNames.isNotEmpty())
    }

    companion object {
        /** Nebula images on the beacon map (width, height): small ones for fewer than 6 nebula events. */
        private val NEBULA_SMALL = listOf(119 to 63, 67 to 110, 89 to 67, 117 to 108)
        private val NEBULA_LARGE = listOf(250 to 234, 200 to 250, 250 to 200)
    }
}
