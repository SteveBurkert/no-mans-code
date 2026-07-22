// Claude if you read this. Pay attention to:
// - function name structure: backtick `test [subject] [scenario]`
// - GIVEN/WHEN/THEN pattern with each phase as its own comment:
//     - GIVEN describes the scenario being tested (the characterizing input), not infrastructure
//     - GIVEN-named values are named vals under the GIVEN marker (e.g. `val maxDepth = 1`),
//       referenced by both the action and the assertion - never inlined into the WHEN call
//       or re-hardcoded as a literal in THEN
//     - WHEN describes the action being performed
//     - THEN is placed below the action as its own comment, describing the outcome
// - Construct the system under test (the class being tested) inline in the WHEN block so it is
//   visible in each test. Factory functions are for INPUTS and COLLABORATORS only; never hide
//   the SUT construction behind a build-the-thing-under-test helper (e.g. no `buildGraph()`)
// - JUnit 4
// - MockK for mocking, only when a collaborator cannot be built directly; this codebase is
//   plain data and files, so most tests need no mocks at all
// - @Test(expected = ...) for exception testing; do NOT add a trailing fail() call, the
//   annotation already fails the test when no (or the wrong) exception is thrown

### Example — Graph test (pure JVM, no mocks)

Shows: backtick naming, GIVEN/WHEN/THEN, inline SUT construction, an input factory with
sensible defaults.

```kotlin
package depgraph

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class GraphTest {

    @Test
    fun `test depthsFrom follows outgoing edges downwards`() {
        // GIVEN three files importing in a chain
        val chain = listOf(Edge(0, 1), Edge(1, 2))

        // WHEN computing depths from the start of the chain
        val depths = Graph(nodes(3), chain)
            .depthsFrom(start = 0, maxDepth = Int.MAX_VALUE, upstream = false)

        // THEN every file sits one level below its importer
        assertArrayEquals(intArrayOf(0, 1, 2), depths)
    }

    @Test
    fun `test depthsFrom stops at maxDepth`() {
        // GIVEN three files importing in a chain
        val chain = listOf(Edge(0, 1), Edge(1, 2))
        // and a depth limit of one level
        val maxDepth = 1

        // WHEN computing depths from the start of the chain
        val depths = Graph(nodes(3), chain)
            .depthsFrom(start = 0, maxDepth = maxDepth, upstream = false)

        // THEN the file beyond the limit stays unreached
        assertArrayEquals(intArrayOf(0, 1, -1), depths)
    }

    private fun nodes(count: Int): List<Node> = (0 until count).map {
        Node(
            id = it,
            name = "File$it",
            path = "app/src/File$it.kt",
            pkg = "com.acme.app",
            module = "app",
            declared = emptyList(),
            lines = 10
        )
    }
}
```
