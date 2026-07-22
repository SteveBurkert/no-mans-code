# No Man's Code (executable: depgraph)

3D fly-through viewer for dependency graphs. LWJGL 3 + OpenGL 3.3 core, Kotlin, single Gradle
module. The README covers usage and controls. The project name is "No Man's Code";
the binary, gradle project and jar keep the name depgraph.

## Build and verify

    ./gradlew installDist       # build the CLI the ./depgraph launcher uses
    ./gradlew fatJar            # shareable build/libs/depgraph-all.jar (all-platform natives)
    ./depgraph -p <dir> --stats # headless parse check, no window opens

Rendering changes can only be judged visually - run the viewer and look. Everything else
(parser, branch extraction, CLI) is verifiable through --stats and exit codes.

## Architecture

- `Main.kt` - CLI, folder chooser (tinyfd), macOS self-relaunch with -XstartOnFirstThread
- `Config.kt` - language/framework config: parse rules + Look (all visual tuning). Built-ins
  in src/main/resources/configs/ (android, jvm, python), picked by marker-file detection in
  BUILT_IN order (most specific first), overridable with --config <name|file>. Format is
  verbatim `key: value` lines - regexes are not escaped
- `ProjectScanner` - regex parse driven by the config; the only file that touches source text.
  Edges: imports vs project FQNs (with relative-import dots and `from x import a, b`),
  same-package name matches (off for python), wildcards. packageFrom declaration = JVM style,
  packageFrom path = FQN from file path (python, `__init__` collapses to its package)
- `Graph` - nodes/edges, adjacency, BFS depths, subgraph, search
- `Layout` - two-level: force-directed module centres, package clumps inside module blobs.
  Small graphs (<= DIRECT_LIMIT nodes) use one flat force layout instead
- `Viewer` - window, input, HUD, selection, branch morphs, render loop
- `Scene` - StarField/NodeLayer/EdgeLayer, instanced billboards and GL_LINES, shaders inline
- `Overlay` + `FontAtlas` - all 2D text/panels; Java2D bakes ASCII into one texture
- `Camera`, `Gl` - flight/fly-to, shader helpers

## Constraints that are not obvious

- macOS: GLFW needs the main thread (-XstartOnFirstThread; launcher, jar relaunch and Gradle
  run config all pass it) and AWT must stay headless (set in main) or the two fight over it.
- GLFW key callbacks report US-physical keys. Bindings for symbol keys must use the char
  callback instead (that is why search opens on the typed '/'), or German layouts break.
- Layout must stay deterministic: all randomness is seeded, never time-based.
- `positions[]` is written only by applyDrift (base layout + wobble); nodes, edges, labels,
  haze and targeting all read it, so motion stays consistent everywhere.
- Vertex buffers are rebuilt and re-uploaded every frame. Deliberate: it keeps highlighting,
  fading and morphing trivial and is far from the bottleneck at this scale.
- Branch views skip the nebula haze on purpose - their flat force layout has no package shape
  to wrap a cloud around.

## Tuning knobs

Everything tuned this project's look lives in `Look` (Config.kt) with the defaults as field
initialisers - star size curve, edge brightness/fade, drift, haze, cluster spacings. Configs
override single keys via `look.*`. Colours and UI constants stay in the Viewer companion.

## Code style

- No trailing commas, in parameter lists and call sites alike.
- Doc comments are KDoc on the declaration. `//` never sits above a member; line comments
  belong inside function bodies.
- Comments are plain language and must be understandable on first read. A comment that only
  justifies a structural choice means the structure should change: prefer an extracted
  constant or a named local over a commented expression.
- Prefer self-documenting names over names that need a comment. Check whether a callback
  parameter is really nullable before defaulting to `?`.
- Error messages name the source and the offending value, like the config errors do:
  "config jvm: missing 'extensions'".
- Unsure how to name something? Propose suggestions and ask instead of guessing.

## Testing

No tests exist yet. Conventions and a worked example for the first ones live in
TESTING_EXAMPLE.md; read it before writing any test.
