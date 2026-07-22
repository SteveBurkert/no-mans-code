package depgraph

import java.io.File

/**
 * Everything language- or taste-specific in one place. A config is a plain text file of
 * `key: value` lines; values are taken verbatim, so regexes need no escaping. Built-in
 * configs live in the jar under /configs and users can bring their own file via --config.
 */
class Config(
    val name: String,
    val extensions: Set<String>,
    val detect: List<String>,
    val skipDirs: Set<String>,
    val includePath: Regex?,
    val testPath: Regex?,
    val moduleRule: ModuleRule,
    val packageFrom: PackageFrom,
    val packageRegex: Regex?,
    val importRegex: Regex,
    val declarationRegex: Regex,
    val memberRegex: Regex?,
    val stripRoots: List<String>,
    val samePackage: Boolean,
    val look: Look
) {
    enum class ModuleRule { SRC_PARENT, TOP_PACKAGE }
    enum class PackageFrom { DECLARATION, PATH }

    companion object {
        /** Detection order matters: the more specific config is listed first. */
        val BUILT_IN = listOf("android", "jvm", "python")

        fun load(nameOrFile: String): Config {
            val file = File(nameOrFile)
            if (!file.isFile && nameOrFile !in BUILT_IN) {
                System.err.println("no config '$nameOrFile': expected a file or one of $BUILT_IN")
                kotlin.system.exitProcess(1)
            }
            return try {
                if (file.isFile) parse(file.readText(), file.name) else builtIn(nameOrFile)
            } catch (broken: IllegalArgumentException) {
                System.err.println(broken.message)
                kotlin.system.exitProcess(1)
            }
        }

        fun detect(projectRoot: File): Config {
            for (name in BUILT_IN) {
                val config = builtIn(name)
                if (config.detect.any { marker -> markerExists(projectRoot, marker) }) return config
            }
            return builtIn("jvm")
        }

        fun builtIn(name: String): Config {
            val resource = Config::class.java.getResourceAsStream("/configs/$name.conf")
                ?: error("built-in config '$name' missing from the jar")
            return parse(resource.bufferedReader().readText(), name)
        }

        /** A marker starting with * / is looked for in every direct child directory too. */
        private fun markerExists(root: File, marker: String): Boolean {
            if (!marker.startsWith("*/")) return File(root, marker).exists()
            val rest = marker.removePrefix("*/")
            return root.listFiles()?.any { it.isDirectory && File(it, rest).exists() } == true
        }

        private fun parse(text: String, source: String): Config {
            val values = HashMap<String, String>()
            for (rawLine in text.lines()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val colon = line.indexOf(':')
                require(colon > 0) { "config $source: expected 'key: value', got '$line'" }
                values[line.take(colon).trim()] = line.substring(colon + 1).trim()
            }

            fun list(key: String): List<String> =
                values[key]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

            fun compile(key: String, raw: String): Regex = try {
                Regex(raw, RegexOption.MULTILINE)
            } catch (broken: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "config $source: '$key' is not a valid pattern: ${broken.message}"
                )
            }

            fun pathRegex(key: String): Regex? = values[key]?.let { compile(key, it) }

            fun regex(key: String): Regex? = values[key]?.let { raw ->
                val compiled = compile(key, raw)
                require(compiled.toPattern().matcher("").groupCount() >= 1) {
                    "config $source: '$key' has no capturing group: '$raw'"
                }
                compiled
            }

            fun requiredRegex(key: String): Regex = regex(key)
                ?: throw IllegalArgumentException("config $source: missing '$key'")

            fun required(key: String): String = values[key]
                ?: throw IllegalArgumentException("config $source: missing '$key'")

            fun number(key: String, fallback: Float): Float {
                val raw = values[key] ?: return fallback
                return raw.toFloatOrNull()?.takeIf { it.isFinite() }
                    ?: throw IllegalArgumentException(
                        "config $source: '$key' is not a finite number: '$raw'"
                    )
            }

            val packageFrom = when (val raw = values["packageFrom"] ?: "declaration") {
                "declaration" -> PackageFrom.DECLARATION
                "path" -> PackageFrom.PATH
                else -> throw IllegalArgumentException(
                    "config $source: packageFrom must be 'declaration' or 'path', got '$raw'"
                )
            }
            if (packageFrom == PackageFrom.DECLARATION) required("packageRegex")

            val look = Look(
                minStarSize = number("look.minStarSize", 1.8f),
                starScale = number("look.starScale", 1.9f),
                starPower = number("look.starPower", 1.55f),
                maxStarSize = number("look.maxStarSize", 60f),
                edgeBrightness = number("look.edgeBrightness", 0.21f),
                edgeFade = number("look.edgeFade", 0.85f),
                drift = number("look.drift", 1.4f),
                hazePackage = number("look.hazePackage", 0.022f),
                hazeModule = number("look.hazeModule", 0.012f),
                moduleSpacing = number("look.moduleSpacing", 880f),
                clusterSpacing = number("look.clusterSpacing", 24f),
                packageSpacing = number("look.packageSpacing", 7f),
                clusterGap = number("look.clusterGap", 2.6f),
                branchSpacing = number("look.branchSpacing", 55f)
            )
            require(look.moduleSpacing > 0) {
                "config $source: 'look.moduleSpacing' must be greater than 0, got '${values["look.moduleSpacing"]}'"
            }
            require(look.branchSpacing > 0) {
                "config $source: 'look.branchSpacing' must be greater than 0, got '${values["look.branchSpacing"]}'"
            }

            return Config(
                name = values["name"] ?: source,
                extensions = list("extensions").toSet().also {
                    require(it.isNotEmpty()) { "config $source: missing 'extensions'" }
                },
                detect = list("detect"),
                skipDirs = list("skipDirs").toSet(),
                includePath = pathRegex("includePath"),
                testPath = pathRegex("testPath"),
                moduleRule = when (val raw = values["module"] ?: "srcParent") {
                    "srcParent" -> ModuleRule.SRC_PARENT
                    "topPackage" -> ModuleRule.TOP_PACKAGE
                    else -> throw IllegalArgumentException(
                        "config $source: module must be 'srcParent' or 'topPackage', got '$raw'"
                    )
                },
                packageFrom = packageFrom,
                packageRegex = regex("packageRegex"),
                importRegex = requiredRegex("importRegex"),
                declarationRegex = requiredRegex("declarationRegex"),
                memberRegex = regex("memberRegex"),
                stripRoots = list("stripRoots"),
                samePackage = when (val raw = values["samePackage"] ?: "on") {
                    "on" -> true
                    "off" -> false
                    else -> throw IllegalArgumentException(
                        "config $source: samePackage must be 'on' or 'off', got '$raw'"
                    )
                },
                look = look
            )
        }
    }
}

/** The universe's look. Every value has the tuned default; configs override single keys. */
class Look(
    val minStarSize: Float = 1.8f,
    val starScale: Float = 1.9f,
    val starPower: Float = 1.55f,
    val maxStarSize: Float = 60f,
    val edgeBrightness: Float = 0.21f,
    val edgeFade: Float = 0.85f,
    val drift: Float = 1.4f,
    val hazePackage: Float = 0.022f,
    val hazeModule: Float = 0.012f,
    val moduleSpacing: Float = 880f,
    val clusterSpacing: Float = 24f,
    val packageSpacing: Float = 7f,
    val clusterGap: Float = 2.6f,
    val branchSpacing: Float = 55f
)
