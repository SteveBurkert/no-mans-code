package depgraph

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Watches a connected device over adb for the foreground activity. Runs its own daemon thread,
 * polling `dumpsys activity activities` about twice a second, and publishes the latest [Screen]
 * for the render loop to read. It reports whichever app is in front; deciding which screens
 * belong to the project is left to the reader, which has the graph to match against.
 */
class AdbWatcher {

    private val current = AtomicReference<Screen?>(null)

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var reportedMissing = false
    private var reportedNoDevice = false

    val screen: Screen? get() = current.get()

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "adb-watcher").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }

    private fun loop() {
        var previous: Screen? = null
        while (running) {
            val next = poll()
            if (next != null && next.activity != previous?.activity) {
                previous = next
                current.set(next)
            }
            try {
                Thread.sleep(POLL_MILLIS)
            } catch (interrupted: InterruptedException) {
                break
            }
        }
    }

    private fun poll(): Screen? {
        val activities = run("shell", "dumpsys", "activity", "activities") ?: return null
        val (appId, activity) = resumedComponent(activities) ?: return null
        return Screen(absoluteFqn(appId, activity))
    }

    /** The resumed activity as (applicationId, class), whichever app is in the foreground. */
    private fun resumedComponent(dump: String): Pair<String, String>? {
        for (line in dump.lineSequence()) {
            if (!line.contains("ResumedActivity")) continue
            val match = COMPONENT.find(line) ?: continue
            return match.groupValues[1] to match.groupValues[2]
        }
        return null
    }

    /**
     * The class's full name. dumpsys writes it relative (a leading dot) only when it starts with
     * the applicationId, so prepending the applicationId there restores the true source name.
     */
    private fun absoluteFqn(appId: String, activity: String): String =
        if (activity.startsWith(".")) appId + activity else activity

    private fun run(vararg arguments: String): String? = try {
        val process = ProcessBuilder(listOf("adb") + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        if (output.contains("no devices/emulators found") || output.contains("device offline")) {
            reportNoDevice()
            null
        } else {
            output
        }
    } catch (missing: IOException) {
        reportMissing()
        null
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }

    private fun reportMissing() {
        if (reportedMissing) return
        reportedMissing = true
        System.err.println("adb not found on PATH: --adb needs the Android platform-tools adb")
    }

    private fun reportNoDevice() {
        if (reportedNoDevice) return
        reportedNoDevice = true
        System.err.println("adb: no device connected, waiting for one")
    }

    private companion object {
        const val POLL_MILLIS = 500L
        val COMPONENT = Regex("""ActivityRecord\{\S+ \S+ ([\w.]+)/([\w.]+)""")
    }
}

/** One observed foreground screen: the resumed activity's full class name. */
class Screen(val activity: String)
