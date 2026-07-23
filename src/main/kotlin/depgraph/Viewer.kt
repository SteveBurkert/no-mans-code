package depgraph

import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33C.*
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class Viewer(
    private val graph: Graph,
    private val projectName: String,
    startRoot: Int,
    startDepth: Int,
    startUpstream: Boolean,
    private val look: Look,
    private val demo: Boolean = false,
    private val scope: String = "whole project",
    private val adb: Boolean = false
) {

    private val count = graph.nodes.size
    private val camera = Camera()

    private val nodeColour = FloatArray(count * 3)
    private val nodeSize = FloatArray(count)
    private val nodeGlow = FloatArray(count)
    private val fullPositions: FloatArray
    private var basePositions: FloatArray
    private var positions: FloatArray
    private var morphFrom: FloatArray
    private var morphTo: FloatArray
    private var now = 0f
    private var morph = 1f
    private var visible = BooleanArray(count) { true }
    private val neighbour = BooleanArray(count)

    private var root = startRoot
    private var depth = startDepth
    private var upstream = startUpstream
    private var selected = -1
    private var targeted = -1
    private var searchFocus = -1

    private var adbWatcher: AdbWatcher? = null
    private var lastAdbScreen: Screen? = null
    private var lastAdbNode = -1
    private var adbStartedAt = 0f
    private var adbEverFound = false

    private var highlightDeps = !adb
    private var orbitMode = adb
    private var orbiting = false
    private var orbitAngle = 0f
    private var orbitRadius = 0f
    private var orbitHeight = 0f
    private var orbitNode = -1
    private val orbitCentre = Vector3f()
    private val orbitScratch = Vector3f()

    private var searching = false
    private val query = StringBuilder()
    private var results: List<Node> = emptyList()
    private var moduleResults: List<Pair<String, Int>> = emptyList()
    private var highlighted = 0
    private var showHelp = false
    private var showLabels = true

    private var window = 0L
    private var windowWidth = 1600
    private var windowHeight = 1000
    private var framebufferWidth = 1600
    private var framebufferHeight = 1000
    private var captured = true
    private var firstMouse = true
    private var lastMouseX = 0.0
    private var lastMouseY = 0.0
    private var baseSpeed = 60f
    private var speedScale = 1f
    private var speedTouched = false
    private var autoSpeed = 1f
    private var nearestDistance = Float.MAX_VALUE
    private var farPlane = 8000f

    private lateinit var stars: StarField
    private lateinit var nodes: NodeLayer
    private lateinit var edges: EdgeLayer
    private lateinit var overlay: Overlay

    private val maxHazeGroups =
        graph.nodes.distinctBy { it.module to it.pkg }.size + graph.nodes.distinctBy { it.module }.size
    private val nodeData = FloatArray((count + maxHazeGroups) * 8)
    private val edgeData = FloatArray(graph.edges.size * 14)
    private var bulkRadius = 1000f

    private var hazeGroupCount = 0
    private val hazeStart = IntArray(maxHazeGroups + 1)
    private val hazeMembers = IntArray(count * 2)
    private val hazeTint = FloatArray(maxHazeGroups * 3)
    private val hazeIsModule = BooleanArray(maxHazeGroups)
    private val projection = Matrix4f()
    private val viewProjection = Matrix4f()
    private val clip = Vector4f()
    private val origin = Vector3f()
    private val labelIds = IntArray(MAX_LABELS)
    private val labelScores = FloatArray(MAX_LABELS)
    private var screenX = 0f
    private var screenY = 0f

    private val demoRandom = java.util.Random()
    private val demoTarget = Vector3f()
    private val demoDirection = Vector3f(0f, 0f, -1f)
    private val demoScratch = Vector3f()
    private var demoAge = Float.MAX_VALUE
    private var demoLifetime = 0f
    private var demoMouseTravel = 0f

    init {
        val modules = graph.nodes.map { it.module }.distinct().sorted()
        val hues = modules.withIndex().associate { (index, module) -> module to (index * 0.6180339f) % 1f }
        for (node in graph.nodes) {
            val rgb = hsv(hues.getValue(node.module), 0.6f, 1f)
            nodeColour[node.id * 3] = rgb[0]
            nodeColour[node.id * 3 + 1] = rgb[1]
            nodeColour[node.id * 3 + 2] = rgb[2]
            // size is the amount of code: a small file keeps a visible base size, above that
            // the star grows superlinearly so the giants become enormous.
            // glow is how many files depend on it.
            nodeSize[node.id] = (
                look.minStarSize +
                    look.starScale * Math.pow(node.lines / 200.0, look.starPower.toDouble()).toFloat()
                ).coerceAtMost(look.maxStarSize)
            nodeGlow[node.id] = min(1.5f, 0.75f + 0.14f * ln(1f + graph.incoming[node.id].size))
        }
        fullPositions = Layout.compute(graph, BooleanArray(count) { true }, look)
        basePositions = fullPositions.copyOf()
        positions = fullPositions.copyOf()
        morphFrom = fullPositions.copyOf()
        morphTo = fullPositions
        buildHaze()
    }

    /**
     * One faint cloud per package and a fainter one per module, wrapped around whichever of
     * their files are visible. Only the membership is fixed here; the clouds take their centre
     * and reach from the live positions every frame, so they ride along with morphs.
     */
    private fun buildHaze() {
        val byPackage = LinkedHashMap<String, MutableList<Int>>()
        val byModule = LinkedHashMap<String, MutableList<Int>>()
        for (node in graph.nodes) {
            if (!visible[node.id]) continue
            byPackage.getOrPut("${node.module}|${node.pkg}") { ArrayList() }.add(node.id)
            byModule.getOrPut(node.module) { ArrayList() }.add(node.id)
        }
        hazeGroupCount = 0
        var write = 0
        for ((groups, isModule) in listOf(byPackage.values to false, byModule.values to true)) {
            for (members in groups) {
                val group = hazeGroupCount++
                hazeStart[group] = write
                for (id in members) hazeMembers[write++] = id
                val first = members.first()
                hazeTint[group * 3] = nodeColour[first * 3]
                hazeTint[group * 3 + 1] = nodeColour[first * 3 + 1]
                hazeTint[group * 3 + 2] = nodeColour[first * 3 + 2]
                hazeIsModule[group] = isModule
            }
        }
        hazeStart[hazeGroupCount] = write
    }

    fun run() {
        createWindow()
        if (demo) keepDisplayAwake()
        stars = StarField(2600)
        nodes = NodeLayer()
        edges = EdgeLayer()
        overlay = Overlay(FontAtlas())

        if (root >= 0) {
            select(root)
            applyBranch(animate = false)
        }
        frame(instant = true)
        if (adb) {
            adbWatcher = AdbWatcher().apply { start() }
            adbStartedAt = glfwGetTime().toFloat()
        }

        var previous = glfwGetTime()
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents()
            val time = glfwGetTime()
            val elapsed = min((time - previous).toFloat(), 0.1f)
            previous = time
            now = time.toFloat()

            camera.update(elapsed)
            if (demo) demoFlight(elapsed) else advanceMovement(elapsed)
            followAdb()
            advanceMorph(elapsed)
            applyDrift()
            advanceOrbit(elapsed)
            updateTarget()
            render()
            glfwSwapBuffers(window)
        }
        adbWatcher?.stop()
        glfwDestroyWindow(window)
        glfwTerminate()
    }

    private fun createWindow() {
        GLFWErrorCallback.createPrint(System.err).set()
        check(glfwInit()) { "could not initialise GLFW" }

        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)
        glfwWindowHint(GLFW_SAMPLES, 4)

        val monitor = glfwGetPrimaryMonitor()
        if (demo && monitor != 0L) {
            val mode = checkNotNull(glfwGetVideoMode(monitor)) {
                "could not read the monitor's video mode"
            }
            windowWidth = mode.width()
            windowHeight = mode.height()
            glfwWindowHint(GLFW_REFRESH_RATE, mode.refreshRate())
            window = glfwCreateWindow(windowWidth, windowHeight, "No Man's Code - $projectName", monitor, 0L)
        } else {
            if (monitor != 0L) {
                val areaX = IntArray(1)
                val areaY = IntArray(1)
                val areaWidth = IntArray(1)
                val areaHeight = IntArray(1)
                glfwGetMonitorWorkarea(monitor, areaX, areaY, areaWidth, areaHeight)
                windowWidth = (areaWidth[0] * 0.86f).toInt()
                windowHeight = (areaHeight[0] * 0.86f).toInt()
            }
            window = glfwCreateWindow(windowWidth, windowHeight, "No Man's Code - $projectName", 0L, 0L)
        }
        check(window != 0L) { "could not create the window" }
        glfwMakeContextCurrent(window)
        glfwSwapInterval(1)
        GL.createCapabilities()

        readSizes()
        glfwSetFramebufferSizeCallback(window) { _, _, _ -> readSizes() }
        // a locked or switched-away session iconifies the fullscreen tour: end it cleanly
        glfwSetWindowIconifyCallback(window) { _, iconified -> if (demo && iconified) quit() }
        glfwSetCursorPosCallback(window) { _, x, y -> onMouseMove(x, y) }
        glfwSetKeyCallback(window) { _, key, _, action, _ -> onKey(key, action) }
        glfwSetCharCallback(window) { _, code -> onChar(code) }
        glfwSetMouseButtonCallback(window) { _, button, action, _ -> onMouseButton(button, action) }
        glfwSetScrollCallback(window) { _, _, delta -> onScroll(delta) }

        if (demo) glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_HIDDEN) else setCaptured(false)
        glDisable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glEnable(GL_PROGRAM_POINT_SIZE)
        glEnable(GL_MULTISAMPLE)
    }

    private fun readSizes() {
        val width = IntArray(1)
        val height = IntArray(1)
        glfwGetFramebufferSize(window, width, height)
        framebufferWidth = max(1, width[0])
        framebufferHeight = max(1, height[0])
        glfwGetWindowSize(window, width, height)
        windowWidth = max(1, width[0])
        windowHeight = max(1, height[0])
    }

    /**
     * The tour is not user activity, so the system's own idle lock would interrupt it. Both
     * helpers hold their assertion until this process dies, kill -9 included, so there is
     * nothing to clean up. Windows has no such built-in tool; there the iconify handler ends
     * the tour once the session locks.
     */
    private fun keepDisplayAwake() {
        val os = System.getProperty("os.name").lowercase()
        val pid = ProcessHandle.current().pid().toString()
        val command = when {
            os.contains("mac") -> listOf("caffeinate", "-d", "-i", "-w", pid)
            os.contains("windows") -> return
            else -> listOf(
                "systemd-inhibit", "--what=idle", "--who=depgraph", "--why=demo tour",
                "tail", "--pid=$pid", "-f", "/dev/null"
            )
        }
        runCatching { ProcessBuilder(command).start() }
            .onFailure { System.err.println("could not keep the display awake: ${it.message}") }
    }

    private fun setCaptured(value: Boolean) {
        captured = value
        firstMouse = true
        glfwSetInputMode(window, GLFW_CURSOR, if (value) GLFW_CURSOR_DISABLED else GLFW_CURSOR_NORMAL)
        if (value && glfwRawMouseMotionSupported()) {
            glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE)
        }
    }

    private fun onMouseMove(x: Double, y: Double) {
        if (firstMouse) {
            lastMouseX = x
            lastMouseY = y
            firstMouse = false
            return
        }
        val deltaX = (x - lastMouseX).toFloat()
        val deltaY = (y - lastMouseY).toFloat()
        lastMouseX = x
        lastMouseY = y
        if (demo) {
            demoMouseTravel += kotlin.math.abs(deltaX) + kotlin.math.abs(deltaY)
            if (now > DEMO_GRACE && demoMouseTravel > 40f) quit()
            return
        }
        if (!captured) return
        orbiting = false
        camera.turn(deltaX * LOOK_SPEED, -deltaY * LOOK_SPEED)
    }

    private fun quit() {
        glfwSetWindowShouldClose(window, true)
    }

    private fun onScroll(delta: Double) {
        if (demo) {
            if (now > DEMO_GRACE) quit()
            return
        }
        speedTouched = true
        speedScale = (speedScale * (if (delta > 0) 1.18f else 0.85f)).coerceIn(0.05f, 40f)
    }

    private fun onMouseButton(button: Int, action: Int) {
        if (demo) {
            if (action == GLFW_PRESS && now > DEMO_GRACE) quit()
            return
        }
        if (searching) return
        if (button != GLFW_MOUSE_BUTTON_LEFT || action != GLFW_PRESS) return
        if (!captured) setCaptured(true) else if (targeted >= 0) select(targeted) else deselect()
    }

    /**
     * Search and the branch-depth keys react to the typed character, not the key: GLFW names
     * keys after the US layout, so on a German keyboard '/' arrives as shift+7 and a key
     * binding would never fire. The '/' itself never lands in the query because it is ignored
     * while searching.
     */
    private fun onChar(code: Int) {
        if (demo) return
        val character = code.toChar()
        if (!searching) {
            when (character) {
                '/' -> openSearch()
                '[' -> changeDepth(-1)
                ']' -> changeDepth(1)
            }
            return
        }
        if (character != '/' && character.code in 32..126) {
            query.append(character)
            refreshResults()
        }
    }

    private fun onKey(key: Int, action: Int) {
        if (demo) {
            if (action == GLFW_PRESS && now > DEMO_GRACE) quit()
            return
        }
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return
        if (searching) {
            onSearchKey(key)
            return
        }
        when (key) {
            GLFW_KEY_ESCAPE -> setCaptured(!captured)
            GLFW_KEY_Q -> glfwSetWindowShouldClose(window, true)
            GLFW_KEY_H, GLFW_KEY_F1 -> showHelp = !showHelp
            GLFW_KEY_TAB -> showLabels = !showLabels
            GLFW_KEY_L -> highlightDeps = !highlightDeps
            GLFW_KEY_O -> {
                orbitMode = !orbitMode
                if (orbitMode && selected >= 0) startOrbit(selected) else orbiting = false
            }
            GLFW_KEY_B -> if (selected >= 0) branchFrom(selected, upstream = false)
            GLFW_KEY_U -> if (selected >= 0) branchFrom(selected, upstream = true)
            GLFW_KEY_R -> resetToWhole()
            GLFW_KEY_F -> {
                val focus = searchFocus
                if (focus >= 0) {
                    select(focus)
                    flyToNode(focus)
                } else if (selected >= 0) {
                    flyToNode(selected)
                }
            }
            GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> if (targeted >= 0) select(targeted) else deselect()
        }
    }

    private fun onSearchKey(key: Int) {
        when (key) {
            GLFW_KEY_ESCAPE -> closeSearch()
            GLFW_KEY_BACKSPACE -> {
                if (query.isNotEmpty()) {
                    query.setLength(query.length - 1)
                    refreshResults()
                }
            }
            GLFW_KEY_DOWN -> {
                val total = moduleResults.size + results.size
                if (total > 0) highlighted = (highlighted + 1) % total
            }
            GLFW_KEY_UP -> {
                val total = moduleResults.size + results.size
                if (total > 0) highlighted = (highlighted - 1 + total) % total
            }
            GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                if (highlighted < moduleResults.size) {
                    val module = moduleResults[highlighted].first
                    closeSearch()
                    flyToModule(module)
                } else {
                    // fly towards the hit without selecting it; F afterwards selects it
                    results.getOrNull(highlighted - moduleResults.size)?.let { node ->
                        closeSearch()
                        if (!visible[node.id]) resetToWhole()
                        flyToNode(node.id)
                        searchFocus = node.id
                    }
                }
            }
        }
    }

    private fun advanceMovement(elapsed: Float) {
        if (searching || !captured) return
        // until the user picks a speed, ease off near stars so close fly-bys stay readable
        val wanted = if (speedTouched) 1f else (nearestDistance / SLOW_RADIUS).coerceIn(MIN_AUTO_SPEED, 1f)
        autoSpeed += (wanted - autoSpeed) * min(1f, 4f * elapsed)
        val boost = if (down(GLFW_KEY_LEFT_SHIFT) || down(GLFW_KEY_RIGHT_SHIFT)) 5f else 1f
        val step = baseSpeed * speedScale * autoSpeed * boost * elapsed
        if (down(GLFW_KEY_W) || down(GLFW_KEY_S) || down(GLFW_KEY_A) || down(GLFW_KEY_D) ||
            down(GLFW_KEY_SPACE) || down(GLFW_KEY_C) || down(GLFW_KEY_LEFT_CONTROL)) orbiting = false
        if (down(GLFW_KEY_W)) camera.move(camera.forward(), step)
        if (down(GLFW_KEY_S)) camera.move(camera.forward(), -step)
        if (down(GLFW_KEY_D)) camera.move(camera.right(), step)
        if (down(GLFW_KEY_A)) camera.move(camera.right(), -step)
        if (down(GLFW_KEY_SPACE)) camera.move(UP, step)
        if (down(GLFW_KEY_C) || down(GLFW_KEY_LEFT_CONTROL)) camera.move(UP, -step)
    }

    private fun down(key: Int) = glfwGetKey(window, key) == GLFW_PRESS

    /** Banks smoothly towards a spot near a random star; on arrival or boredom picks the next. */
    private fun demoFlight(elapsed: Float) {
        demoAge += elapsed
        demoScratch.set(demoTarget).sub(camera.position)
        if (demoScratch.length() < DEMO_ARRIVAL || demoAge > demoLifetime) {
            pickDemoTarget()
            demoScratch.set(demoTarget).sub(camera.position)
        }
        val distance = demoScratch.length()
        if (distance > 1e-3f) {
            demoScratch.div(distance)
            demoDirection.lerp(demoScratch, min(1f, DEMO_TURN * elapsed)).normalize()
            if (demoDirection.y > 0.9f || demoDirection.y < -0.9f) {
                demoDirection.y = demoDirection.y.coerceIn(-0.9f, 0.9f)
                demoDirection.normalize()
            }
        }
        val speed = (min(distance, bulkRadius) * 0.3f).coerceAtLeast(25f)
        camera.position.fma(speed * elapsed, demoDirection)
        camera.yaw = kotlin.math.atan2(demoDirection.x, -demoDirection.z)
        camera.pitch = kotlin.math.asin(demoDirection.y.coerceIn(-1f, 1f))
    }

    /** Mostly hops between stars around the camera; now and then it crosses to a far cluster. */
    private fun pickDemoTarget() {
        var chosen = -1
        if (demoRandom.nextFloat() < 0.7f) {
            val far = bulkRadius * 0.45f
            val farSquared = far * far
            var candidates = 0
            for (id in 0 until count) {
                if (!visible[id]) continue
                val dx = basePositions[id * 3] - camera.position.x
                val dy = basePositions[id * 3 + 1] - camera.position.y
                val dz = basePositions[id * 3 + 2] - camera.position.z
                val squared = dx * dx + dy * dy + dz * dz
                if (squared > farSquared || squared < 100f * 100f) continue
                candidates++
                if (demoRandom.nextInt(candidates) == 0) chosen = id
            }
            if (candidates < 15) chosen = -1
        }
        if (chosen == -1) {
            var remaining = demoRandom.nextInt(visible.count { it })
            for (id in 0 until count) {
                if (!visible[id]) continue
                chosen = id
                if (remaining-- == 0) break
            }
        }
        val height = demoRandom.nextFloat() * 2f - 1f
        val ring = sqrt((1f - height * height).coerceAtLeast(0f))
        val angle = demoRandom.nextFloat() * 2f * Math.PI.toFloat()
        val reach = 30f + demoRandom.nextFloat() * 100f
        demoTarget.set(
            basePositions[chosen * 3] + kotlin.math.cos(angle) * ring * reach,
            basePositions[chosen * 3 + 1] + height * reach,
            basePositions[chosen * 3 + 2] + kotlin.math.sin(angle) * ring * reach
        )
        demoAge = 0f
        demoLifetime = 16f + demoRandom.nextFloat() * 10f
    }

    /**
     * Follows the device's foreground screen: when adb reports a new activity that maps to a file,
     * selects it and flies there. A screen that stays put is left alone, so the user keeps full
     * control between changes.
     */
    private fun followAdb() {
        val screen = adbWatcher?.screen ?: return
        if (screen === lastAdbScreen) return
        lastAdbScreen = screen
        val node = resolveScreen(screen) ?: return
        adbEverFound = true
        if (node.id == lastAdbNode) return
        lastAdbNode = node.id
        if (!visible[node.id]) resetToWhole()
        select(node.id)
        flyToNode(node.id)
    }

    /**
     * The file a foreground screen maps to, or null when it is not this project. The activity's
     * full name must match a file exactly, which both rejects other apps and ignores the flavor's
     * applicationId.
     */
    private fun resolveScreen(screen: Screen): Node? = graph.findByFqn(screen.activity)

    /** True once adb has run a while without ever matching a screen to a file. */
    private fun adbWaiting(): Boolean =
        adbWatcher != null && !adbEverFound && now - adbStartedAt > ADB_WAIT_SECONDS

    private fun select(id: Int) {
        searchFocus = -1
        selected = id
        java.util.Arrays.fill(neighbour, false)
        for (other in graph.outgoing[id]) neighbour[other] = true
        for (other in graph.incoming[id]) neighbour[other] = true
    }

    private fun deselect() {
        searchFocus = -1
        selected = -1
        java.util.Arrays.fill(neighbour, false)
    }

    private fun branchFrom(id: Int, upstream: Boolean) {
        root = id
        this.upstream = upstream
        applyBranch(animate = true)
    }

    private fun changeDepth(delta: Int) {
        if (root < 0) return
        val current = if (depth == Int.MAX_VALUE) MAX_SHOWN_DEPTH else depth
        depth = (current + delta).coerceIn(1, MAX_SHOWN_DEPTH)
        applyBranch(animate = true)
    }

    private fun resetToWhole() {
        if (root < 0) {
            // nothing to restore, but R still brings the camera back to the overview
            frame(instant = false)
            return
        }
        root = -1
        depth = Int.MAX_VALUE
        visible = BooleanArray(count) { true }
        buildHaze()
        startMorph(fullPositions, animate = true)
        frame(instant = false)
    }

    private fun applyBranch(animate: Boolean) {
        val depths = graph.depthsFrom(root, depth, upstream)
        visible = BooleanArray(count) { depths[it] >= 0 }
        buildHaze()
        select(root)
        startMorph(Layout.compute(graph, visible, look), animate)
        frame(instant = !animate)
    }

    private fun startMorph(target: FloatArray, animate: Boolean) {
        morphTo = target
        if (!animate) {
            basePositions = target.copyOf()
            morph = 1f
            return
        }
        morphFrom = basePositions.copyOf()
        morph = 0f
    }

    private fun advanceMorph(elapsed: Float) {
        if (morph >= 1f) return
        morph = min(1f, morph + elapsed / MORPH_SECONDS)
        val eased = morph * morph * (3f - 2f * morph)
        for (index in basePositions.indices) {
            basePositions[index] = morphFrom[index] + (morphTo[index] - morphFrom[index]) * eased
        }
    }

    /** A slow wobble around the laid-out spot keeps the sky from ever standing still. */
    private fun applyDrift() {
        for (id in 0 until count) {
            if (!visible[id]) continue
            val phase = id * 2.3999f
            val at = id * 3
            positions[at] = basePositions[at] + look.drift * sin(now * 0.29f + phase)
            positions[at + 1] = basePositions[at + 1] + look.drift * sin(now * 0.23f + phase * 1.7f)
            positions[at + 2] = basePositions[at + 2] + look.drift * sin(now * 0.19f + phase * 2.9f)
        }
    }

    private fun flyToNode(id: Int) {
        if (orbitMode) {
            startOrbit(id)
            return
        }
        orbiting = false
        camera.flyTo(
            origin.set(morphTo[id * 3], morphTo[id * 3 + 1], morphTo[id * 3 + 2]),
            nodeSize[id] * 9f + 30f,
            0.7f
        )
    }

    /**
     * Eases the camera to a package-scale distance around the selected file's package and circles
     * it slowly, always looking at the file. Manual flying drops back out of the orbit.
     */
    private fun startOrbit(id: Int) {
        orbitNode = id
        val node = graph.nodes[id]
        var centreX = 0f
        var centreY = 0f
        var centreZ = 0f
        var members = 0
        for (other in graph.nodes) {
            if (!visible[other.id] || other.module != node.module || other.pkg != node.pkg) continue
            centreX += morphTo[other.id * 3]
            centreY += morphTo[other.id * 3 + 1]
            centreZ += morphTo[other.id * 3 + 2]
            members++
        }
        if (members == 0) {
            centreX = morphTo[id * 3]
            centreY = morphTo[id * 3 + 1]
            centreZ = morphTo[id * 3 + 2]
            members = 1
        }
        centreX /= members
        centreY /= members
        centreZ /= members
        var reachSquared = 0f
        for (other in graph.nodes) {
            if (!visible[other.id] || other.module != node.module || other.pkg != node.pkg) continue
            val dx = morphTo[other.id * 3] - centreX
            val dy = morphTo[other.id * 3 + 1] - centreY
            val dz = morphTo[other.id * 3 + 2] - centreZ
            val squared = dx * dx + dy * dy + dz * dz
            if (squared > reachSquared) reachSquared = squared
        }
        orbitCentre.set(centreX, centreY, centreZ)
        orbitRadius = max(sqrt(reachSquared) * 1.9f + 70f, nodeSize[id] * 6f + 90f)
        orbitHeight = orbitRadius * 0.32f
        orbitAngle = kotlin.math.atan2(camera.position.z - centreZ, camera.position.x - centreX)
        orbiting = true
        camera.cancelFlight()
    }

    private fun advanceOrbit(elapsed: Float) {
        if (!orbiting) return
        orbitAngle += ORBIT_SPEED * elapsed
        orbitScratch.set(
            orbitCentre.x + kotlin.math.cos(orbitAngle) * orbitRadius,
            orbitCentre.y + orbitHeight,
            orbitCentre.z + kotlin.math.sin(orbitAngle) * orbitRadius
        )
        camera.position.lerp(orbitScratch, min(1f, ORBIT_EASE * elapsed))
        camera.pointAt(origin.set(positions[orbitNode * 3], positions[orbitNode * 3 + 1], positions[orbitNode * 3 + 2]))
    }

    /**
     * Frames the bulk of the graph, not its bounding sphere: one far-flung cluster would
     * otherwise push the camera so far back that everything else shrinks to a speck.
     */
    private fun frame(instant: Boolean) {
        orbiting = false
        var counted = 0
        for (id in 0 until count) if (visible[id]) counted++
        if (counted == 0) return
        val distances = FloatArray(counted)
        var write = 0
        for (id in 0 until count) {
            if (!visible[id]) continue
            val x = morphTo[id * 3]
            val y = morphTo[id * 3 + 1]
            val z = morphTo[id * 3 + 2]
            distances[write++] = sqrt(x * x + y * y + z * z)
        }
        distances.sort()
        val bulk = max(distances[((counted - 1) * 0.88f).toInt()], 30f)
        val whole = distances[counted - 1]

        bulkRadius = bulk
        farPlane = max(6000f, whole * 10f)
        baseSpeed = max(bulk / 5f, 15f)
        val distance = bulk * 1.6f + 50f
        if (instant) {
            camera.position.set(0f, 0f, distance)
            camera.yaw = 0f
            camera.pitch = 0f
        } else {
            camera.flyTo(origin.set(0f, 0f, 0f), distance, 0.9f)
        }
    }

    private fun updateTarget() {
        val forward = camera.forward()
        val forwardX = forward.x
        val forwardY = forward.y
        val forwardZ = forward.z
        var best = -1
        var bestAlignment = AIM_THRESHOLD
        var nearest = Float.MAX_VALUE
        for (id in 0 until count) {
            if (!visible[id]) continue
            val dx = positions[id * 3] - camera.position.x
            val dy = positions[id * 3 + 1] - camera.position.y
            val dz = positions[id * 3 + 2] - camera.position.z
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            if (length < 1e-3f) continue
            if (length < nearest) nearest = length
            val alignment = (dx * forwardX + dy * forwardY + dz * forwardZ) / length
            if (alignment > bestAlignment) {
                bestAlignment = alignment
                best = id
            }
        }
        targeted = best
        nearestDistance = nearest
    }

    private fun openSearch() {
        searching = true
        query.setLength(0)
        results = emptyList()
        moduleResults = emptyList()
        highlighted = 0
        setCaptured(false)
    }

    private fun closeSearch() {
        searching = false
        setCaptured(true)
    }

    /** Modules rank above files: a handful of clusters beats fifty look-alike file names. */
    private fun refreshResults() {
        val needle = query.toString().trim().lowercase()
        moduleResults = if (needle.isEmpty()) emptyList() else {
            val sizes = HashMap<String, Int>()
            for (node in graph.nodes) if (visible[node.id]) sizes.merge(node.module, 1, Int::plus)
            sizes.entries
                .mapNotNull { (module, files) ->
                    val name = module.lowercase()
                    val rank = when {
                        name == needle -> 0
                        name.startsWith(needle) -> 1
                        name.contains(needle) -> 2
                        else -> return@mapNotNull null
                    }
                    Triple(module, files, rank)
                }
                .sortedWith(compareBy({ it.third }, { it.first.length }, { it.first }))
                .take(3)
                .map { it.first to it.second }
        }
        results = graph.search(query.toString(), 10 - moduleResults.size)
        highlighted = 0
    }

    private fun flyToModule(module: String) {
        var centreX = 0f
        var centreY = 0f
        var centreZ = 0f
        var members = 0
        for (node in graph.nodes) {
            if (!visible[node.id] || node.module != module) continue
            centreX += morphTo[node.id * 3]
            centreY += morphTo[node.id * 3 + 1]
            centreZ += morphTo[node.id * 3 + 2]
            members++
        }
        if (members == 0) return
        centreX /= members
        centreY /= members
        centreZ /= members
        var radiusSquared = 0f
        for (node in graph.nodes) {
            if (!visible[node.id] || node.module != module) continue
            val dx = morphTo[node.id * 3] - centreX
            val dy = morphTo[node.id * 3 + 1] - centreY
            val dz = morphTo[node.id * 3 + 2] - centreZ
            val squared = dx * dx + dy * dy + dz * dz
            if (squared > radiusSquared) radiusSquared = squared
        }
        camera.flyTo(origin.set(centreX, centreY, centreZ), sqrt(radiusSquared) * 1.7f + 60f, 0.9f)
    }

    private fun render() {
        glViewport(0, 0, framebufferWidth, framebufferHeight)
        glClearColor(BACKGROUND.red, BACKGROUND.green, BACKGROUND.blue, BACKGROUND.alpha)
        glClear(GL_COLOR_BUFFER_BIT)

        projection.setPerspective(
            FIELD_OF_VIEW,
            framebufferWidth.toFloat() / framebufferHeight,
            0.5f,
            farPlane
        )
        val view = camera.view()
        viewProjection.set(projection).mul(view)

        glBlendFunc(GL_ONE, GL_ONE)
        stars.draw(view, projection)

        val lines = buildEdges()
        edges.upload(edgeData, lines)
        edges.draw(view, projection, camera.position, bulkRadius * look.edgeFade)

        val points = buildNodes()
        nodes.upload(nodeData, points)
        nodes.draw(view, projection, framebufferHeight)

        overlay.begin()
        if (showLabels) drawLabels()
        if (!demo) drawHud()
        overlay.draw(windowWidth, windowHeight)
    }

    private fun buildNodes(): Int {
        var written = 0
        for (id in 0 until count) {
            if (!visible[id]) continue
            var size = nodeSize[id]
            var red = nodeColour[id * 3]
            var green = nodeColour[id * 3 + 1]
            var blue = nodeColour[id * 3 + 2]
            var intensity = nodeGlow[id] * (0.9f + 0.1f * sin(now * 1.1f + id * 1.7f))
            if (selected >= 0) {
                when {
                    id == selected -> {
                        red = 1f
                        green = 1f
                        blue = 1f
                        size *= 1.7f
                        intensity = 1.5f
                    }
                    !highlightDeps -> {}
                    neighbour[id] -> intensity = 1.4f
                    else -> intensity = 0.3f
                }
            }
            if (id == targeted && id != selected) {
                size *= 1.7f
                intensity = max(intensity, 1.5f)
            }
            val at = written * 8
            nodeData[at] = positions[id * 3]
            nodeData[at + 1] = positions[id * 3 + 1]
            nodeData[at + 2] = positions[id * 3 + 2]
            nodeData[at + 3] = size
            nodeData[at + 4] = red
            nodeData[at + 5] = green
            nodeData[at + 6] = blue
            nodeData[at + 7] = intensity
            written++
        }
        return appendHaze(written)
    }

    /** Branch views use a plain force layout where packages have no shape, so no clouds there. */
    private fun appendHaze(written: Int): Int {
        var total = written
        if (total <= Layout.DIRECT_LIMIT) return total
        val dimmed = if (selected >= 0 && highlightDeps) 0.35f else 1f
        for (group in 0 until hazeGroupCount) {
            val from = hazeStart[group]
            val end = hazeStart[group + 1]
            var centreX = 0f
            var centreY = 0f
            var centreZ = 0f
            for (index in from until end) {
                val id = hazeMembers[index]
                centreX += positions[id * 3]
                centreY += positions[id * 3 + 1]
                centreZ += positions[id * 3 + 2]
            }
            val members = end - from
            centreX /= members
            centreY /= members
            centreZ /= members
            var reachSquared = 0f
            for (index in from until end) {
                val id = hazeMembers[index]
                val dx = positions[id * 3] - centreX
                val dy = positions[id * 3 + 1] - centreY
                val dz = positions[id * 3 + 2] - centreZ
                val squared = dx * dx + dy * dy + dz * dz
                if (squared > reachSquared) reachSquared = squared
            }
            val reach = sqrt(reachSquared)
            val at = total * 8
            nodeData[at] = centreX
            nodeData[at + 1] = centreY
            nodeData[at + 2] = centreZ
            nodeData[at + 3] = if (hazeIsModule[group]) reach * 1.05f + 26f else reach * 1.4f + 14f
            nodeData[at + 4] = hazeTint[group * 3]
            nodeData[at + 5] = hazeTint[group * 3 + 1]
            nodeData[at + 6] = hazeTint[group * 3 + 2]
            nodeData[at + 7] = (if (hazeIsModule[group]) look.hazeModule else look.hazePackage) * dimmed
            total++
        }
        return total
    }

    private fun buildEdges(): Int {
        var written = 0
        for (edge in graph.edges) {
            if (!visible[edge.from] || !visible[edge.to]) continue
            val touching = selected >= 0 && highlightDeps && (edge.from == selected || edge.to == selected)
            val brightness = when {
                touching -> 1.7f
                selected >= 0 && highlightDeps -> 0.07f
                else -> look.edgeBrightness
            }
            // highlighted lines must stay visible across space, so they skip the distance fade
            val fadeStrength = if (touching) 0f else 1f
            var red = nodeColour[edge.from * 3]
            var green = nodeColour[edge.from * 3 + 1]
            var blue = nodeColour[edge.from * 3 + 2]
            if (touching) {
                red = red * 0.4f + 0.6f
                green = green * 0.4f + 0.6f
                blue = blue * 0.4f + 0.6f
            }
            val at = written * 14
            edgeData[at] = positions[edge.from * 3]
            edgeData[at + 1] = positions[edge.from * 3 + 1]
            edgeData[at + 2] = positions[edge.from * 3 + 2]
            edgeData[at + 3] = red * brightness
            edgeData[at + 4] = green * brightness
            edgeData[at + 5] = blue * brightness
            edgeData[at + 6] = fadeStrength
            edgeData[at + 7] = positions[edge.to * 3]
            edgeData[at + 8] = positions[edge.to * 3 + 1]
            edgeData[at + 9] = positions[edge.to * 3 + 2]
            edgeData[at + 10] = red * brightness * 0.22f
            edgeData[at + 11] = green * brightness * 0.22f
            edgeData[at + 12] = blue * brightness * 0.22f
            edgeData[at + 13] = fadeStrength
            written++
        }
        return written
    }

    private fun drawLabels() {
        var found = 0
        for (id in 0 until count) {
            if (!visible[id]) continue
            val dx = positions[id * 3] - camera.position.x
            val dy = positions[id * 3 + 1] - camera.position.y
            val dz = positions[id * 3 + 2] - camera.position.z
            val distance = sqrt(dx * dx + dy * dy + dz * dz)
            var score = nodeSize[id] / max(distance, 1f)
            if (id == selected || id == targeted) score = Float.MAX_VALUE
            if (score < LABEL_CUTOFF) continue
            if (found < MAX_LABELS) {
                labelIds[found] = id
                labelScores[found] = score
                found++
            } else {
                var weakest = 0
                for (slot in 1 until MAX_LABELS) {
                    if (labelScores[slot] < labelScores[weakest]) weakest = slot
                }
                if (score > labelScores[weakest]) {
                    labelIds[weakest] = id
                    labelScores[weakest] = score
                }
            }
        }

        // strongest first; a label that would sit on an already placed one is dropped
        val order = (0 until found).sortedByDescending { labelScores[it] }
        val placed = FloatArray((found + 3) * 4)
        var placedCount = 0
        fun reserve(left: Float, top: Float, right: Float, bottom: Float) {
            placed[placedCount * 4] = left
            placed[placedCount * 4 + 1] = top
            placed[placedCount * 4 + 2] = right
            placed[placedCount * 4 + 3] = bottom
            placedCount++
        }
        val info = infoLines()
        reserve(MARGIN, MARGIN, MARGIN + panelWidth(info), MARGIN + panelHeight(info))
        if (showHelp) {
            val width = panelWidth(HELP)
            val left = windowWidth - MARGIN - width
            reserve(left, MARGIN, left + width, MARGIN + panelHeight(HELP))
        } else {
            val hintLeft = windowWidth - MARGIN - overlay.textWidth(HELP_HINT, BODY)
            reserve(hintLeft, MARGIN, windowWidth.toFloat(), MARGIN + BODY)
        }
        if (selected >= 0) {
            val selection = selectionLines()
            val top = windowHeight - MARGIN - panelHeight(selection)
            reserve(MARGIN, top, MARGIN + panelWidth(selection), top + panelHeight(selection))
        }
        for (slot in order) {
            val id = labelIds[slot]
            if (!project(positions[id * 3], positions[id * 3 + 1], positions[id * 3 + 2])) continue
            val node = graph.nodes[id]
            val width = overlay.textWidth(node.name, LABEL_SIZE)
            val left = screenX - width / 2f - 4f
            val top = screenY + 7f
            val right = left + width + 8f
            val bottom = top + LABEL_SIZE + 6f
            var free = true
            for (other in 0 until placedCount) {
                if (left < placed[other * 4 + 2] && right > placed[other * 4] &&
                    top < placed[other * 4 + 3] && bottom > placed[other * 4 + 1]
                ) {
                    free = false
                    break
                }
            }
            if (!free) continue
            reserve(left, top, right, bottom)

            val colour = when (id) {
                selected -> WHITE
                targeted -> AIM
                else -> Colour(
                    nodeColour[id * 3],
                    nodeColour[id * 3 + 1],
                    nodeColour[id * 3 + 2],
                    if (selected >= 0 && highlightDeps && !neighbour[id]) 0.35f else 0.85f
                )
            }
            overlay.text(screenX - width / 2f, screenY + 9f, LABEL_SIZE, colour, node.name)
        }
    }

    private fun project(x: Float, y: Float, z: Float): Boolean {
        clip.set(x, y, z, 1f)
        viewProjection.transform(clip)
        if (clip.w <= 0.0001f) return false
        screenX = (clip.x / clip.w * 0.5f + 0.5f) * windowWidth
        screenY = (1f - (clip.y / clip.w * 0.5f + 0.5f)) * windowHeight
        return screenX > -160f && screenX < windowWidth + 160f &&
            screenY > -40f && screenY < windowHeight + 40f
    }

    private fun drawHud() {
        drawInfo()

        if (selected >= 0) {
            val lines = selectionLines()
            panel(MARGIN, windowHeight - MARGIN - panelHeight(lines), lines)
        }

        val centreX = windowWidth / 2f
        val centreY = windowHeight / 2f
        if (!captured && !searching) {
            val invitation = "click to fly"
            overlay.text(
                centreX - overlay.textWidth(invitation, BODY) / 2f,
                centreY - 40f, BODY, AIM, invitation
            )
        }
        val aim = if (targeted >= 0) AIM else CROSSHAIR
        overlay.rect(centreX - 7f, centreY - 0.5f, 14f, 1f, aim)
        overlay.rect(centreX - 0.5f, centreY - 7f, 1f, 14f, aim)
        if (targeted >= 0 && targeted != selected) {
            val name = graph.nodes[targeted].name
            overlay.text(centreX - overlay.textWidth(name, BODY) / 2f, centreY + 16f, BODY, AIM, name)
        }

        if (!showHelp) {
            overlay.text(windowWidth - MARGIN - overlay.textWidth(HELP_HINT, BODY), MARGIN, BODY, DIM, HELP_HINT)
        } else {
            panel(windowWidth - MARGIN - panelWidth(HELP), MARGIN, HELP)
        }

        if (searching) drawSearch()
    }

    private fun infoLines(): List<String> {
        val mode = when {
            root < 0 && adbWaiting() -> "no matching screen on device"
            root < 0 && adbWatcher != null -> "following device"
            root < 0 -> scope
            upstream -> "used by ${graph.nodes[root].name}${depthLabel()}"
            else -> "used from ${graph.nodes[root].name}${depthLabel()}"
        }
        return listOf(
            projectName,
            mode,
            "${visible.count { it }} files   ${countVisibleEdges()} dependencies",
            if (speedTouched) "speed %.1fx".format(java.util.Locale.ROOT, speedScale) else "speed auto"
        )
    }

    private fun selectionLines(): List<String> {
        val node = graph.nodes[selected]
        return listOf(
            node.name,
            node.pkg,
            "module ${node.module}",
            "${node.lines} lines   uses ${graph.outgoing[selected].size}" +
                "   used by ${graph.incoming[selected].size}",
            "B branch down    U branch up    F fly to"
        )
    }

    private fun drawSearch() {
        val boxWidth = min(700f, windowWidth * 0.7f)
        val left = (windowWidth - boxWidth) / 2f
        var top = windowHeight * 0.14f

        overlay.rect(left, top, boxWidth, 38f, PANEL_STRONG)
        overlay.text(left + PADDING, top + 11f, BODY, WHITE, "/ ${query}_")
        top += 42f

        if (moduleResults.isEmpty() && results.isEmpty() && query.isNotEmpty()) {
            overlay.rect(left, top, boxWidth, ROW_HEIGHT, PANEL)
            overlay.text(left + PADDING, top + 7f, BODY, DIM, "nothing matches")
            return
        }
        moduleResults.forEachIndexed { index, (module, files) ->
            val rowTop = top + index * ROW_HEIGHT
            overlay.rect(left, rowTop, boxWidth, ROW_HEIGHT, if (index == highlighted) SELECTION else PANEL)
            overlay.text(left + PADDING, rowTop + 7f, BODY, if (index == highlighted) WHITE else AIM, module)
            val detail = "module   $files files"
            overlay.text(
                left + boxWidth - PADDING - overlay.textWidth(detail, SMALL),
                rowTop + 8f, SMALL, DIM, detail
            )
        }
        results.forEachIndexed { index, node ->
            val row = moduleResults.size + index
            val rowTop = top + row * ROW_HEIGHT
            overlay.rect(left, rowTop, boxWidth, ROW_HEIGHT, if (row == highlighted) SELECTION else PANEL)
            overlay.text(left + PADDING, rowTop + 7f, BODY, if (row == highlighted) WHITE else TEXT, node.name)
            val detail = if (visible[node.id]) node.module else "${node.module}  (outside branch)"
            overlay.text(
                left + boxWidth - PADDING - overlay.textWidth(detail, SMALL),
                rowTop + 8f, SMALL, DIM, detail
            )
        }
    }

    private fun panel(x: Float, y: Float, lines: List<String>) {
        overlay.rect(x, y, panelWidth(lines), panelHeight(lines), PANEL)
        lines.forEachIndexed { index, line ->
            val colour = if (index == 0) WHITE else TEXT
            overlay.text(x + PADDING, y + PADDING + index * LINE_STEP, BODY, colour, line)
        }
    }

    /** Like [panel] for the top-left readout, but paints the adb-waiting line red and bold. */
    private fun drawInfo() {
        val lines = infoLines()
        overlay.rect(MARGIN, MARGIN, panelWidth(lines), panelHeight(lines), PANEL)
        lines.forEachIndexed { index, line ->
            val x = MARGIN + PADDING
            val y = MARGIN + PADDING + index * LINE_STEP
            if (index == MODE_LINE && root < 0 && adbWaiting()) {
                overlay.text(x, y, BODY, ERROR, line)
                overlay.text(x + 0.7f, y, BODY, ERROR, line)
            } else {
                overlay.text(x, y, BODY, if (index == 0) WHITE else TEXT, line)
            }
        }
    }

    private fun panelWidth(lines: List<String>): Float =
        lines.maxOf { overlay.textWidth(it, BODY) } + PADDING * 2

    private fun panelHeight(lines: List<String>): Float = lines.size * LINE_STEP + PADDING * 2

    private fun countVisibleEdges(): Int =
        if (root < 0) graph.edges.size else graph.edges.count { visible[it.from] && visible[it.to] }

    private fun depthLabel(): String = if (depth == Int.MAX_VALUE) "" else "  depth $depth"

    private fun hsv(hue: Float, saturation: Float, value: Float): FloatArray {
        val sector = (hue * 6f) % 6f
        val offset = sector - sector.toInt()
        val p = value * (1f - saturation)
        val q = value * (1f - saturation * offset)
        val t = value * (1f - saturation * (1f - offset))
        return when (sector.toInt()) {
            0 -> floatArrayOf(value, t, p)
            1 -> floatArrayOf(q, value, p)
            2 -> floatArrayOf(p, value, t)
            3 -> floatArrayOf(p, q, value)
            4 -> floatArrayOf(t, p, value)
            else -> floatArrayOf(value, p, q)
        }
    }

    private companion object {
        const val FIELD_OF_VIEW = 1.15f
        const val LOOK_SPEED = 0.0022f
        const val MORPH_SECONDS = 0.75f
        const val SLOW_RADIUS = 280f
        const val MIN_AUTO_SPEED = 0.05f
        const val DEMO_GRACE = 2.5f
        const val DEMO_TURN = 0.55f
        const val DEMO_ARRIVAL = 70f
        const val MAX_LABELS = 130
        const val LABEL_CUTOFF = 0.0009f
        const val MAX_SHOWN_DEPTH = 12
        const val AIM_THRESHOLD = 0.9988f
        const val ORBIT_SPEED = 0.12f
        const val ORBIT_EASE = 2.2f

        const val MARGIN = 16f
        const val PADDING = 10f
        const val BODY = 19f
        const val SMALL = 16f
        const val LABEL_SIZE = 17f
        const val LINE_STEP = 21f
        const val ROW_HEIGHT = 26f
        const val MODE_LINE = 1
        const val ADB_WAIT_SECONDS = 20f
        const val HELP_HINT = "H  help"

        val HELP = listOf(
            "mouse         look around",
            "W A S D       fly",
            "space / C     rise / descend",
            "shift         boost",
            "scroll        flight speed",
            "click|enter   select node at crosshair, empty space unselects",
            "B             branch down from selection",
            "U             branch up from selection",
            "[  ]          branch depth",
            "R             back to whole project and overview camera",
            "F             fly to selection, after a search select its hit",
            "L             light select, keep the rest of the graph lit",
            "O             orbit the selection's package instead of flying close",
            "/             search",
            "tab           labels on/off",
            "esc           release mouse, click to fly again",
            "H             close help",
            "Q             quit"
        )

        val UP = Vector3f(0f, 1f, 0f)
        val BACKGROUND = Colour(0.012f, 0.016f, 0.038f)
        val WHITE = Colour(1f, 1f, 1f, 0.98f)
        val TEXT = Colour(0.74f, 0.80f, 0.92f, 0.95f)
        val DIM = Colour(0.55f, 0.62f, 0.78f, 0.8f)
        val AIM = Colour(0.45f, 0.95f, 1f, 0.98f)
        val ERROR = Colour(1f, 0.34f, 0.34f, 0.98f)
        val CROSSHAIR = Colour(0.6f, 0.7f, 0.9f, 0.45f)
        val PANEL = Colour(0.03f, 0.05f, 0.11f, 0.72f)
        val PANEL_STRONG = Colour(0.05f, 0.08f, 0.16f, 0.92f)
        val SELECTION = Colour(0.12f, 0.30f, 0.55f, 0.95f)
    }
}
