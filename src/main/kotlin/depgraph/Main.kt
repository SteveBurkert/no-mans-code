package depgraph

import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """
depgraph - No Man's Code: fly through a codebase's dependency graph

usage:
  depgraph [options] [RepositoryUrl] [ClassName]

  ClassName            start the graph at this class and show only what it uses,
                       following dependencies downwards. Omit it for the whole project.
  RepositoryUrl        a git url (https://... or git@...) to clone and explore instead
                       of a local project. Needs git; the checkout lives in the temp dir.

options:
  -p, --project <x>    project root to parse, or a git url (default: current directory)
  -c, --config <x>     language/framework config: built-in name (android, jvm, python)
                       or a config file that also may tune the look (see README).
                       Without it the project type is detected from marker files.
  -m, --module <name>  only files of that module, in isolation
      --package <pre>  only files in that package and below, in isolation
  -d, --depth <n>      how many levels to follow from the class (default: all)
  -u, --up             follow dependents upwards instead of dependencies downwards
  -t, --tests          include test sources
  -s, --stats          print graph statistics and exit, no window
      --demo           fullscreen self-flying tour, any input quits (screensaver)
      --lock           lock the screen when the window closes; with --demo whoever
                       stops the tour has to unlock
      --adb            follow the foreground Android screen on a connected device and
                       fly to it, keeping up as you move through the app. Needs adb and
                       a device. Not with -m, --package, a class name or --demo.
  -h, --help           show this help
"""

private const val BANNER = """
   .            *          .             +             .           *        .
      _   ______     __  ______    _   ___ _____    __________  ____  ______
     / | / / __ \   /  |/  /   |  / | / ( ) ___/   / ____/ __ \/ __ \/ ____/
    /  |/ / / / /  / /|_/ / /| | /  |/ /|/\__ \   / /   / / / / / / / __/
   / /|  / /_/ /  / /  / / ___ |/ /|  /  ___/ /  / /___/ /_/ / /_/ / /___
  /_/ |_/\____/  /_/  /_/_/  |_/_/ |_/  /____/   \____/\____/_____/_____/
      +        .            *               --=*          .            *     .
"""

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val options = Options.parse(args) ?: exitProcess(0)

    if (!options.statsOnly) {
        relaunchOnFirstThreadIfNeeded(args)
        println(BANNER.trimEnd())
    }
    val project = options.projectUrl?.let { fetchRepository(it) } ?: resolveProject(options)

    if (!project.isDirectory) {
        System.err.println("not a directory: $project")
        exitProcess(1)
    }

    if (options.adb && !options.projectGiven && options.projectUrl == null && !Config.isAndroid(project)) {
        System.err.println("--adb needs an Android project, but this is not one:")
        System.err.println("  $project")
        System.err.println("point at an Android app with -p")
        exitProcess(1)
    }

    val config = options.config?.let { Config.load(it) } ?: Config.detect(project)
    println("config: ${config.name}")

    val started = System.currentTimeMillis()
    val graph = ProjectScanner.scan(project, options.includeTests, config)
    if (graph.nodes.isEmpty()) {
        System.err.println("no ${config.name} sources (${config.extensions.joinToString(", ")}) found under $project")
        System.err.println("try another --config: built in are ${Config.BUILT_IN}, or bring your own file")
        exitProcess(1)
    }
    val elapsed = System.currentTimeMillis() - started
    println("parsed ${graph.nodes.size} files, ${graph.edges.size} dependencies in ${elapsed}ms")

    val (scoped, scope) = applyScope(graph, options)
    val root = options.rootClass?.let { findRoot(scoped, it, project) } ?: -1

    if (options.statsOnly) {
        printStats(branchOf(scoped, root, options))
        return
    }
    if (options.adb) println("adb: watching a connected device for the app's screens")
    Viewer(scoped, project.name, root, options.depth, options.upstream, config.look, options.demo, scope, options.adb)
        .run()
    if (options.lock) lockScreen()
}

private fun applyScope(full: Graph, options: Options): Pair<Graph, String> {
    var graph = full
    val parts = ArrayList<String>()

    options.module?.let { name ->
        val keep = BooleanArray(graph.nodes.size) { graph.nodes[it].module == name }
        if (!keep.any { it }) {
            System.err.println("no module named '$name'")
            suggest(graph.nodes.map { it.module }, name)
            exitProcess(1)
        }
        graph = graph.subgraph(keep)
        parts.add("module $name")
    }

    options.pkg?.let { prefix ->
        val keep = BooleanArray(graph.nodes.size) {
            graph.nodes[it].pkg == prefix || graph.nodes[it].pkg.startsWith("$prefix.")
        }
        if (!keep.any { it }) {
            System.err.println("no files in package '$prefix'")
            suggest(graph.nodes.map { it.pkg }, prefix)
            exitProcess(1)
        }
        graph = graph.subgraph(keep)
        parts.add("package $prefix")
    }

    if (parts.isEmpty()) return full to "whole project"
    val scope = parts.joinToString(", ")
    println("$scope: ${graph.nodes.size} files, ${graph.edges.size} dependencies")
    return graph to scope
}

private fun suggest(candidates: List<String>, needle: String) {
    val wanted = needle.lowercase()
    val close = candidates.distinct()
        .map { it to sharedPrefix(it.lowercase(), wanted) }
        .filter { it.second >= 3 }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        .take(10)
    if (close.isNotEmpty()) {
        System.err.println("did you mean:")
        close.forEach { System.err.println("  ${it.first}") }
    }
}

private fun sharedPrefix(a: String, b: String): Int {
    var index = 0
    while (index < a.length && index < b.length && a[index] == b[index]) index++
    return index
}

private fun lockScreen() {
    val os = System.getProperty("os.name").lowercase()
    val command = when {
        os.contains("mac") -> listOf("pmset", "displaysleepnow")
        os.contains("windows") -> listOf("rundll32.exe", "user32.dll,LockWorkStation")
        else -> listOf("loginctl", "lock-session")
    }
    runCatching { ProcessBuilder(command).inheritIO().start().waitFor() }
        .onFailure { System.err.println("could not lock the screen: ${it.message}") }
}

/**
 * Clones a repository into the system temp dir, or freshens an earlier checkout of it there.
 * The temp dir keeps the tool from bloating the machine: the operating system throws it away
 * on its own schedule, and a fast re-clone is the acceptable price.
 */
private fun fetchRepository(url: String): File {
    val repoName = url.trimEnd('/').removeSuffix(".git")
        .substringAfterLast('/').substringAfterLast(':')
        .ifEmpty { "repository" }
    val slug = url.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    val checkout = File(System.getProperty("java.io.tmpdir"), "depgraph-checkouts/$slug/$repoName")

    if (File(checkout, ".git").isDirectory) {
        println("updating checkout $checkout")
        if (!git("-C", checkout.absolutePath, "pull", "--ff-only")) {
            System.err.println("could not update, using the existing checkout")
        }
    } else {
        checkout.deleteRecursively()
        checkout.parentFile.mkdirs()
        println("cloning $url")
        if (!git("clone", "--depth", "1", url, checkout.absolutePath)) {
            System.err.println("clone failed: $url")
            exitProcess(1)
        }
    }
    return checkout
}

private fun git(vararg arguments: String): Boolean = try {
    ProcessBuilder(listOf("git") + arguments).inheritIO().start().waitFor() == 0
} catch (missing: java.io.IOException) {
    System.err.println("opening a repository url needs git, but git was not found on this machine")
    System.err.println("install git, or clone the repository yourself and pass the directory with -p")
    exitProcess(1)
}

/**
 * Started without -p outside anything that looks like a project (a double-clicked jar lands in
 * the home directory), a native folder chooser asks what to explore. Explicit paths and --stats
 * runs never get a dialog.
 */
private fun resolveProject(options: Options): File {
    if (options.projectGiven || options.statsOnly || looksLikeProject(options.project)) {
        return options.project
    }
    val choice = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_selectFolderDialog(
        "Choose a project to explore",
        System.getProperty("user.home") + File.separator
    )
    if (choice == null) {
        System.err.println("no project chosen")
        exitProcess(0)
    }
    return File(choice).canonicalFile
}

private fun looksLikeProject(dir: File): Boolean =
    listOf("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle", "pom.xml", "src")
        .any { File(dir, it).exists() }

/**
 * GLFW can only open windows from the first thread on macOS, which needs the -XstartOnFirstThread
 * JVM flag. A plain `java -jar depgraph.jar` does not have it, so the process starts itself once
 * more with the flag and waits for that child instead. Everywhere else this is a no-op.
 */
private fun relaunchOnFirstThreadIfNeeded(args: Array<String>) {
    if (!System.getProperty("os.name").lowercase().contains("mac")) return
    val pid = ProcessHandle.current().pid()
    if (System.getenv("JAVA_STARTED_ON_FIRST_THREAD_$pid") == "1") return
    if (System.getProperty("depgraph.relaunched") != null) return

    val jar = runCatching {
        File(object {}.javaClass.protectionDomain.codeSource.location.toURI())
    }.getOrNull()
    if (jar == null || !jar.isFile) return

    val java = File(System.getProperty("java.home"), "bin/java").absolutePath
    val command = mutableListOf(java, "-XstartOnFirstThread", "-Ddepgraph.relaunched=1", "-jar", jar.absolutePath)
    command.addAll(args)
    exitProcess(ProcessBuilder(command).inheritIO().start().waitFor())
}

private fun findRoot(graph: Graph, rootClass: String, project: File): Int {
    graph.findByName(rootClass)?.let { return it.id }

    System.err.println("no class or file named '$rootClass' in $project")
    val close = graph.search(rootClass, 8)
    if (close.isNotEmpty()) {
        System.err.println("did you mean:")
        close.forEach { System.err.println("  ${it.name}  (${it.path})") }
    }
    exitProcess(1)
}

private fun branchOf(graph: Graph, root: Int, options: Options): Graph {
    if (root < 0) return graph
    val depths = graph.depthsFrom(root, options.depth, options.upstream)
    val branch = graph.subgraph(BooleanArray(graph.nodes.size) { depths[it] >= 0 })
    println("showing ${branch.nodes.size} files, ${branch.edges.size} dependencies")
    return branch
}

private fun printStats(graph: Graph) {
    val byModule = graph.nodes.groupingBy { it.module }.eachCount()
    println("modules: ${byModule.size}")
    byModule.entries.sortedByDescending { it.value }.take(10).forEach { (module, count) ->
        println("  %5d  %s".format(count, module))
    }
    println("most depended upon:")
    graph.nodes.sortedByDescending { graph.incoming[it.id].size }.take(15).forEach { node ->
        println("  %5d  %-45s %s".format(graph.incoming[node.id].size, node.name, node.module))
    }
    val orphans = graph.nodes.count { graph.incoming[it.id].isEmpty() && graph.outgoing[it.id].isEmpty() }
    println("unconnected files: $orphans")
}

class Options(
    val project: File,
    val projectGiven: Boolean,
    val projectUrl: String?,
    val config: String?,
    val module: String?,
    val pkg: String?,
    val rootClass: String?,
    val depth: Int,
    val upstream: Boolean,
    val includeTests: Boolean,
    val statsOnly: Boolean,
    val demo: Boolean,
    val lock: Boolean,
    val adb: Boolean
) {
    companion object {
        fun parse(args: Array<String>): Options? {
            var project = File(".").canonicalFile
            var projectGiven = false
            var projectUrl: String? = null
            var config: String? = null
            var module: String? = null
            var pkg: String? = null
            var rootClass: String? = null
            var depth = Int.MAX_VALUE
            var upstream = false
            var includeTests = false
            var statsOnly = false
            var demo = false
            var lock = false
            var adb = false

            var index = 0
            while (index < args.size) {
                when (val argument = args[index]) {
                    "-h", "--help" -> {
                        println(USAGE.trim())
                        return null
                    }
                    "-p", "--project" -> {
                        val value = next(args, ++index, argument)
                        if (isRepositoryUrl(value)) projectUrl = value
                        else project = File(value).canonicalFile
                        projectGiven = true
                    }
                    "-c", "--config" -> config = next(args, ++index, argument)
                    "-m", "--module" -> module = next(args, ++index, argument)
                    "--package" -> pkg = next(args, ++index, argument)
                    "-d", "--depth" -> {
                        val value = next(args, ++index, argument)
                        depth = value.toIntOrNull() ?: fail("--depth expects a number, got '$value'")
                    }
                    "-u", "--up" -> upstream = true
                    "-t", "--tests" -> includeTests = true
                    "-s", "--stats" -> statsOnly = true
                    "--demo" -> demo = true
                    "--lock" -> lock = true
                    "--adb" -> adb = true
                    else -> {
                        if (argument.startsWith("-")) fail("unknown option '$argument', try --help")
                        if (isRepositoryUrl(argument)) projectUrl = argument else rootClass = argument
                    }
                }
                index++
            }
            if (adb && (module != null || pkg != null || rootClass != null || demo)) {
                fail("--adb cannot be combined with -m, --package, a class name or --demo")
            }
            return Options(
                project, projectGiven, projectUrl, config, module, pkg, rootClass, depth, upstream,
                includeTests, statsOnly, demo, lock, adb
            )
        }

        private fun isRepositoryUrl(value: String): Boolean =
            value.startsWith("http://") || value.startsWith("https://") ||
                value.startsWith("git@") || value.startsWith("ssh://")

        private fun next(args: Array<String>, index: Int, option: String): String =
            args.getOrNull(index) ?: fail("$option expects a value")

        private fun fail(message: String): Nothing {
            System.err.println(message)
            exitProcess(1)
        }
    }
}
