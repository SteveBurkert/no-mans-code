package depgraph

import java.io.File

object ProjectScanner {

    fun scan(root: File, includeTests: Boolean, config: Config): Graph {
        val parsed = sourceFiles(root, includeTests, config)
            .map { parse(it, root, config) }
            .sortedBy { it.path }

        val declaringNode = HashMap<String, Int>(parsed.size * 2)
        val nodesInPackage = HashMap<String, MutableList<Int>>()
        val declarationsInPackage = HashMap<String, MutableList<Declaration>>()

        parsed.forEachIndexed { id, file ->
            nodesInPackage.getOrPut(file.pkg) { ArrayList() }.add(id)
            file.selfName?.let { declaringNode.putIfAbsent(it, id) }
            val prefix = file.selfName ?: file.pkg
            for (name in file.declared) {
                declaringNode.putIfAbsent("$prefix.$name", id)
                if (config.samePackage) {
                    declarationsInPackage.getOrPut(file.pkg) { ArrayList() }.add(Declaration(name, id))
                }
            }
        }

        val edges = ArrayList<Edge>()
        val seen = HashSet<Long>()
        fun link(from: Int, to: Int) {
            if (from != to && seen.add((from.toLong() shl 32) or to.toLong())) edges.add(Edge(from, to))
        }

        parsed.forEachIndexed { id, file ->
            for (target in file.targets) {
                resolve(target, declaringNode)?.let { link(id, it) }
            }
            for (wildcard in file.wildcards) {
                nodesInPackage[wildcard]?.forEach { link(id, it) }
                resolve(wildcard, declaringNode)?.let { link(id, it) }
            }
            val siblings = declarationsInPackage[file.pkg]
            if (siblings != null && siblings.size > 1) {
                val used = identifiers(file.text)
                for (sibling in siblings) {
                    if (sibling.node != id && sibling.name in used) link(id, sibling.node)
                }
            }
        }

        val nodes = parsed.mapIndexed { id, file ->
            Node(id, file.name, file.path, file.pkg, file.module, file.declared, file.lines)
        }
        return Graph(nodes, edges)
    }

    /** Walks up the dotted name so nested types and member imports land on their outer file. */
    private fun resolve(target: String, declaringNode: Map<String, Int>): Int? {
        var candidate = target
        while (true) {
            declaringNode[candidate]?.let { return it }
            if (!candidate.contains('.')) return null
            candidate = candidate.substringBeforeLast('.')
        }
    }

    private fun sourceFiles(root: File, includeTests: Boolean, config: Config): List<File> {
        val files = ArrayList<File>()
        root.walkTopDown()
            .onEnter { it.name !in config.skipDirs && !(it.name.startsWith(".") && it != root) }
            .forEach { file ->
                if (!file.isFile || file.extension !in config.extensions) return@forEach
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (config.includePath != null && !config.includePath.containsMatchIn(relative)) return@forEach
                if (!includeTests && config.testPath?.containsMatchIn(relative) == true) return@forEach
                files.add(file)
            }
        return files
    }

    private fun parse(file: File, root: File, config: Config): ParsedFile {
        val text = file.readText()
        val relative = file.relativeTo(root).invariantSeparatorsPath

        val pathName = pathName(relative, config)
        val pkg = when (config.packageFrom) {
            Config.PackageFrom.DECLARATION ->
                config.packageRegex?.find(text)?.groupValues?.get(1) ?: ""
            Config.PackageFrom.PATH -> pathName.substringBeforeLast('.', "")
        }

        val declared = LinkedHashSet<String>()
        config.declarationRegex.findAll(text).mapTo(declared) { it.groupValues[1] }
        config.memberRegex?.findAll(text)
            ?.map { it.groupValues[1].substringAfterLast('.') }
            ?.filter { it.isNotEmpty() && it.all { char -> char.isLetterOrDigit() || char == '_' } }
            ?.forEach { declared.add(it) }

        val targets = ArrayList<String>()
        val wildcards = ArrayList<String>()
        for (match in config.importRegex.findAll(text)) {
            val base = absolute(match.groupValues[1].removeSuffix(";"), pkg)
            val names = match.groupValues.getOrNull(2).orEmpty()
            if (names.isBlank()) {
                if (base.endsWith(".*")) wildcards.add(base.dropLast(2)) else targets.add(base)
            } else {
                for (raw in names.split(',')) {
                    val name = raw.trim()
                    if (name == "*") wildcards.add(base)
                    else if (name.isNotEmpty()) targets.add("$base.$name")
                }
            }
        }

        return ParsedFile(
            path = relative,
            module = module(relative, root, pkg, pathName, config),
            pkg = pkg,
            name = displayName(file, relative),
            selfName = if (config.packageFrom == Config.PackageFrom.PATH) pathName else null,
            declared = declared.toList(),
            targets = targets,
            wildcards = wildcards,
            lines = text.count { it == '\n' } + 1,
            text = text
        )
    }

    /** Relative imports carry leading dots: one dot is the own package, each further dot one up. */
    private fun absolute(target: String, pkg: String): String {
        if (!target.startsWith(".")) return target
        val dots = target.takeWhile { it == '.' }.length
        val rest = target.drop(dots)
        val segments = pkg.split('.').filter { it.isNotEmpty() }.dropLast(dots - 1)
        return (segments + rest.split('.').filter { it.isNotEmpty() }).joinToString(".")
    }

    /** The dotted name a file has by location: alpha/core.py is alpha.core. */
    private fun pathName(relative: String, config: Config): String {
        var path = relative.substringBeforeLast('.')
        for (prefix in config.stripRoots) {
            if (path.startsWith("$prefix/")) {
                path = path.removePrefix("$prefix/")
                break
            }
        }
        if (path.substringAfterLast('/') == "__init__") {
            path = path.substringBeforeLast('/', "")
        }
        return path.replace('/', '.')
    }

    private fun module(relative: String, root: File, pkg: String, pathName: String, config: Config): String =
        when (config.moduleRule) {
            Config.ModuleRule.SRC_PARENT ->
                if (relative.startsWith("src/")) root.name
                else relative.substringBefore("/src/", relative.substringBefore('/'))
            Config.ModuleRule.TOP_PACKAGE -> {
                val base = if (config.packageFrom == Config.PackageFrom.PATH) pathName else pkg
                base.substringBefore('.').ifEmpty { root.name }
            }
        }

    private fun displayName(file: File, relative: String): String =
        if (file.nameWithoutExtension == "__init__") {
            relative.substringBeforeLast('/').substringAfterLast('/').ifEmpty { file.nameWithoutExtension }
        } else {
            file.nameWithoutExtension
        }

    private fun identifiers(text: String): HashSet<String> {
        val found = HashSet<String>(256)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char.isLetter() || char == '_') {
                val start = index
                while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_')) index++
                found.add(text.substring(start, index))
            } else {
                index++
            }
        }
        return found
    }

    private class Declaration(val name: String, val node: Int)

    private class ParsedFile(
        val path: String,
        val module: String,
        val pkg: String,
        val name: String,
        val selfName: String?,
        val declared: List<String>,
        val targets: List<String>,
        val wildcards: List<String>,
        val lines: Int,
        val text: String
    )
}
