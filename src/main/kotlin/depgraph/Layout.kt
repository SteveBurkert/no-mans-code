package depgraph

import java.util.Random
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Places files in space. Small graphs get a plain force-directed layout. Big ones are laid out
 * per module first, so every module becomes its own star cluster and only the module centres
 * pay the quadratic cost.
 */
object Layout {

    const val DIRECT_LIMIT = 700
    private const val MAX_SPREAD = 9f
    private val GOLDEN_ANGLE = Math.PI * (3.0 - sqrt(5.0))

    fun compute(graph: Graph, keep: BooleanArray, look: Look): FloatArray {
        val ids = graph.nodes.indices.filter { keep[it] }.toIntArray()
        val full = FloatArray(graph.nodes.size * 3)
        if (ids.isEmpty()) return full

        val localOf = IntArray(graph.nodes.size) { -1 }
        ids.forEachIndexed { local, id -> localOf[id] = local }
        val edges = graph.edges.mapNotNull { edge ->
            val from = localOf[edge.from]
            val to = localOf[edge.to]
            if (from >= 0 && to >= 0) Edge(from, to) else null
        }

        val positions =
            if (ids.size <= DIRECT_LIMIT) forceDirected(ids.size, edges, look.branchSpacing)
            else clustered(graph, ids, edges, look)
        recentre(positions)

        ids.forEachIndexed { local, id ->
            full[id * 3] = positions[local * 3]
            full[id * 3 + 1] = positions[local * 3 + 1]
            full[id * 3 + 2] = positions[local * 3 + 2]
        }
        return full
    }

    private fun clustered(graph: Graph, ids: IntArray, edges: List<Edge>, look: Look): FloatArray {
        val moduleNames = ids.map { graph.nodes[it].module }.distinct().sorted()
        val indexOfModule = moduleNames.withIndex().associate { (index, name) -> name to index }
        val members = Array(moduleNames.size) { ArrayList<Int>() }
        ids.forEachIndexed { local, id ->
            members[indexOfModule.getValue(graph.nodes[id].module)].add(local)
        }

        val moduleOf = IntArray(ids.size) { indexOfModule.getValue(graph.nodes[ids[it]].module) }
        val centres = forceDirected(moduleNames.size, moduleEdges(edges, moduleOf), look.moduleSpacing)
        val radii = FloatArray(moduleNames.size) {
            look.clusterSpacing * Math.cbrt(members[it].size.toDouble()).toFloat()
        }
        spreadApart(centres, radii, look.clusterGap)

        val positions = FloatArray(ids.size * 3)
        for (module in moduleNames.indices) {
            placeModule(positions, graph, ids, members[module], centres, module, radii[module], look)
        }
        return positions
    }

    /** Repeated edges pull heavily coupled modules closer, capped so hub modules do not collapse. */
    private fun moduleEdges(edges: List<Edge>, moduleOf: IntArray): List<Edge> {
        val weights = HashMap<Long, Int>()
        for (edge in edges) {
            val from = moduleOf[edge.from]
            val to = moduleOf[edge.to]
            if (from != to) weights.merge((from.toLong() shl 32) or to.toLong(), 1, Int::plus)
        }
        val result = ArrayList<Edge>()
        for ((key, weight) in weights) {
            val from = (key ushr 32).toInt()
            val to = (key and 0xffffffffL).toInt()
            repeat(min(weight, 4)) { result.add(Edge(from, to)) }
        }
        return result
    }

    /**
     * A module is a blob of package clumps: every package gets its own spot inside the module
     * and its files gather tightly around that spot, so the packages read as knots inside the
     * cloud. Stretch and jitter keep the shapes organic, seeded per module so every run
     * produces the same layout.
     */
    private fun placeModule(
        positions: FloatArray,
        graph: Graph,
        ids: IntArray,
        members: List<Int>,
        centres: FloatArray,
        module: Int,
        radius: Float,
        look: Look
    ) {
        val centreX = centres[module * 3]
        val centreY = centres[module * 3 + 1]
        val centreZ = centres[module * 3 + 2]
        val random = Random(module * 7919L + 31L)
        val stretchX = 0.65f + random.nextFloat() * 0.8f
        val stretchY = 0.65f + random.nextFloat() * 0.8f
        val stretchZ = 0.65f + random.nextFloat() * 0.8f

        val packages = members.groupBy { graph.nodes[ids[it]].pkg }.entries.sortedBy { it.key }
        packages.forEachIndexed { slot, (_, files) ->
            var clumpX = centreX
            var clumpY = centreY
            var clumpZ = centreZ
            if (packages.size > 1) {
                val direction = sphereDirection(slot, packages.size)
                val reach = radius * (0.45f + random.nextFloat() * 0.4f)
                clumpX += direction[0] * reach * stretchX
                clumpY += direction[1] * reach * stretchY
                clumpZ += direction[2] * reach * stretchZ
            }

            val ordered = files.sortedBy { graph.nodes[ids[it]].name }
            val clumpRadius = look.packageSpacing * Math.cbrt(ordered.size.toDouble()).toFloat()
            ordered.forEachIndexed { index, local ->
                if (ordered.size == 1) {
                    positions[local * 3] = clumpX
                    positions[local * 3 + 1] = clumpY
                    positions[local * 3 + 2] = clumpZ
                    return@forEachIndexed
                }
                val direction = sphereDirection(index, ordered.size)
                val jitter = 0.75f + random.nextFloat() * 0.5f
                val distance = clumpRadius * Math.cbrt((index + 0.5) / ordered.size).toFloat() * jitter
                positions[local * 3] = clumpX + direction[0] * distance
                positions[local * 3 + 1] = clumpY + direction[1] * distance
                positions[local * 3 + 2] = clumpZ + direction[2] * distance
            }
        }
    }

    /** Direction [index] of [count] points spread evenly over a sphere. */
    private fun sphereDirection(index: Int, count: Int): FloatArray {
        if (count == 1) return floatArrayOf(0f, 1f, 0f)
        val height = 1.0 - 2.0 * index / (count - 1)
        val ring = sqrt((1.0 - height * height).coerceAtLeast(0.0))
        val angle = GOLDEN_ANGLE * index
        return floatArrayOf(
            (cos(angle) * ring).toFloat(),
            height.toFloat(),
            (sin(angle) * ring).toFloat()
        )
    }

    /** Pushes module centres apart to remove overlap, up to a fixed spread limit. */
    private fun spreadApart(centres: FloatArray, radii: FloatArray, clusterGap: Float) {
        var spread = 1f
        for (i in radii.indices) {
            for (j in i + 1 until radii.size) {
                val dx = centres[i * 3] - centres[j * 3]
                val dy = centres[i * 3 + 1] - centres[j * 3 + 1]
                val dz = centres[i * 3 + 2] - centres[j * 3 + 2]
                val distance = sqrt(dx * dx + dy * dy + dz * dz)
                if (distance < 1e-3f) continue
                val needed = (radii[i] + radii[j]) * clusterGap / distance
                if (needed > spread) spread = needed
            }
        }
        val factor = min(spread, MAX_SPREAD)
        if (factor > 1f) for (index in centres.indices) centres[index] *= factor
    }

    private fun forceDirected(count: Int, edges: List<Edge>, spacing: Float): FloatArray {
        val positions = FloatArray(count * 3)
        if (count < 2) return positions

        val random = Random(7919)
        val start = spacing * Math.cbrt(count.toDouble()).toFloat()
        for (index in 0 until count) {
            positions[index * 3] = (random.nextFloat() - 0.5f) * start
            positions[index * 3 + 1] = (random.nextFloat() - 0.5f) * start
            positions[index * 3 + 2] = (random.nextFloat() - 0.5f) * start
        }

        val push = FloatArray(count * 3)
        val iterations = if (count > 300) 150 else 320
        var temperature = start * 0.25f

        repeat(iterations) {
            java.util.Arrays.fill(push, 0f)

            // repulsion of k^2/d along the unit vector reduces to dx * k^2 / d^2
            val floor = spacing * spacing * 1e-4f
            for (i in 0 until count) {
                for (j in i + 1 until count) {
                    val dx = positions[i * 3] - positions[j * 3]
                    val dy = positions[i * 3 + 1] - positions[j * 3 + 1]
                    val dz = positions[i * 3 + 2] - positions[j * 3 + 2]
                    val squared = (dx * dx + dy * dy + dz * dz).coerceAtLeast(floor)
                    val scale = spacing * spacing / squared
                    push[i * 3] += dx * scale
                    push[i * 3 + 1] += dy * scale
                    push[i * 3 + 2] += dz * scale
                    push[j * 3] -= dx * scale
                    push[j * 3 + 1] -= dy * scale
                    push[j * 3 + 2] -= dz * scale
                }
            }

            // attraction of d^2/k along the unit vector reduces to dx * d / k
            for (edge in edges) {
                val dx = positions[edge.from * 3] - positions[edge.to * 3]
                val dy = positions[edge.from * 3 + 1] - positions[edge.to * 3 + 1]
                val dz = positions[edge.from * 3 + 2] - positions[edge.to * 3 + 2]
                val distance = sqrt(dx * dx + dy * dy + dz * dz)
                if (distance < 1e-4f) continue
                val scale = distance / spacing
                push[edge.from * 3] -= dx * scale
                push[edge.from * 3 + 1] -= dy * scale
                push[edge.from * 3 + 2] -= dz * scale
                push[edge.to * 3] += dx * scale
                push[edge.to * 3 + 1] += dy * scale
                push[edge.to * 3 + 2] += dz * scale
            }

            for (index in 0 until count) {
                val dx = push[index * 3]
                val dy = push[index * 3 + 1]
                val dz = push[index * 3 + 2]
                val length = sqrt(dx * dx + dy * dy + dz * dz)
                if (length < 1e-6f) continue
                val step = min(length, temperature) / length
                positions[index * 3] += dx * step
                positions[index * 3 + 1] += dy * step
                positions[index * 3 + 2] += dz * step
            }
            temperature *= 0.985f
        }
        return positions
    }

    private fun recentre(positions: FloatArray) {
        val count = positions.size / 3
        if (count == 0) return
        var x = 0f
        var y = 0f
        var z = 0f
        for (index in 0 until count) {
            x += positions[index * 3]
            y += positions[index * 3 + 1]
            z += positions[index * 3 + 2]
        }
        x /= count
        y /= count
        z /= count
        for (index in 0 until count) {
            positions[index * 3] -= x
            positions[index * 3 + 1] -= y
            positions[index * 3 + 2] -= z
        }
    }
}
