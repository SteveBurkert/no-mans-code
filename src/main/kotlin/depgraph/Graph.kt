package depgraph

class Node(
    val id: Int,
    val name: String,
    val path: String,
    val pkg: String,
    val module: String,
    val declared: List<String>,
    val lines: Int
)

class Edge(val from: Int, val to: Int)

class Graph(val nodes: List<Node>, val edges: List<Edge>) {

    val outgoing: Array<IntArray> = adjacency(reverse = false)
    val incoming: Array<IntArray> = adjacency(reverse = true)

    fun findByName(name: String): Node? {
        val wanted = name.substringAfterLast('.')
        return nodes.firstOrNull { it.name == wanted }
            ?: nodes.firstOrNull { wanted in it.declared }
            ?: nodes.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
    }

    fun search(query: String, limit: Int): List<Node> {
        if (query.isBlank()) return emptyList()
        val needle = query.trim().lowercase()
        return nodes.asSequence()
            .mapNotNull { node ->
                val name = node.name.lowercase()
                val rank = when {
                    name == needle -> 0
                    name.startsWith(needle) -> 1
                    name.contains(needle) -> 2
                    node.pkg.lowercase().contains(needle) -> 3
                    node.path.lowercase().contains(needle) -> 4
                    else -> return@mapNotNull null
                }
                node to rank
            }
            .sortedWith(compareBy({ it.second }, { it.first.name.length }, { it.first.name }))
            .take(limit)
            .map { it.first }
            .toList()
    }

    /** Depth of every node reachable from [start] within [maxDepth] steps, or -1 otherwise. */
    fun depthsFrom(start: Int, maxDepth: Int, upstream: Boolean): IntArray {
        val adjacency = if (upstream) incoming else outgoing
        val depths = IntArray(nodes.size) { -1 }
        depths[start] = 0
        var frontier = intArrayOf(start)
        var depth = 0
        while (frontier.isNotEmpty() && depth < maxDepth) {
            depth++
            val next = ArrayList<Int>()
            for (node in frontier) {
                for (neighbour in adjacency[node]) {
                    if (depths[neighbour] == -1) {
                        depths[neighbour] = depth
                        next.add(neighbour)
                    }
                }
            }
            frontier = next.toIntArray()
        }
        return depths
    }

    fun subgraph(keep: BooleanArray): Graph {
        val remap = IntArray(nodes.size) { -1 }
        val kept = ArrayList<Node>()
        for (node in nodes) {
            if (keep[node.id]) {
                remap[node.id] = kept.size
                kept.add(Node(kept.size, node.name, node.path, node.pkg, node.module, node.declared, node.lines))
            }
        }
        val keptEdges = edges.mapNotNull { edge ->
            val from = remap[edge.from]
            val to = remap[edge.to]
            if (from >= 0 && to >= 0) Edge(from, to) else null
        }
        return Graph(kept, keptEdges)
    }

    private fun adjacency(reverse: Boolean): Array<IntArray> {
        val counts = IntArray(nodes.size)
        for (edge in edges) counts[if (reverse) edge.to else edge.from]++
        val result = Array(nodes.size) { IntArray(counts[it]) }
        val filled = IntArray(nodes.size)
        for (edge in edges) {
            val from = if (reverse) edge.to else edge.from
            val to = if (reverse) edge.from else edge.to
            result[from][filled[from]++] = to
        }
        return result
    }
}
