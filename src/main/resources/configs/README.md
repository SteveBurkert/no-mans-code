# Configs

A config tells depgraph what counts as a source file, how files get their names, packages and
modules, and optionally how the universe looks. It is a plain text file of `key: value` lines:

    # a comment
    extensions: kt, java
    importRegex: ^\s*import\s+([\w.*]+)

Values run to the end of the line and are taken verbatim, so regexes need no escaping and no
quotes. `#` comments whole lines only; a `#` after a value would become part of the value.
Unknown keys are ignored, so a typo in a key name fails silently. Every regex is compiled in
MULTILINE mode (`^` anchors to every line start), and its first capture group is the value
that counts.

## How a config is chosen

Three configs are built in: `android`, `jvm` and `python`. Without `-c`, their `detect`
markers are checked in that order (most specific first) and the first hit wins; no hit falls
back to `jvm`. Pass `-c` to force a built-in or to use your own file:

    ./depgraph -p ~/code/myservice -c python
    ./depgraph -p ~/code/myservice -c myconfig.conf

`--stats` parses without opening a window and prints the file and dependency counts. That is
the loop for writing a config: change a regex, run it, watch the numbers.

## Parsing keys

The required ones:

- `extensions`: comma list of file extensions that count as source, e.g. `kt, java`.
- `importRegex`: group 1 captures an imported name as a dotted path (`com.acme.Thing`,
  `alpha.core`). A name ending in `.*` is a wildcard import and links to every file of that
  package. An optional group 2 may capture a comma list of names for `from x import a, b`
  style imports (a `*` in the list is a wildcard again). Leading dots on the name mean a
  relative import: one dot is the file's own package, each further dot one level up. Keep
  any other parentheses non-capturing (`(?:...)`), because a second capture group is always
  read as that name list.
- `declarationRegex`: group 1 captures each name the file declares (classes, top-level
  functions). Imports are resolved against these names qualified with their package; dotted
  targets walk up, so `com.acme.Foo.Bar` still lands on the file declaring `Foo`.
- `packageRegex`: needed with `packageFrom: declaration` (the default). Group 1 of the first
  match is the file's package, e.g. `^\s*package\s+([\w.]+)`.

The optional ones:

- `name`: display name for terminal output. Default: the config file's name.
- `detect`: comma list of marker files for automatic detection. A `*/` prefix looks in every
  direct child directory instead of the project root. Only the built-in configs take part in
  detection, so in your own file the key does nothing.
- `skipDirs`: comma list of directory names never entered. Directories starting with a dot
  are always skipped.
- `includePath`: regex a file's project-relative path must match (`/` separators). The jvm
  config uses `(^|/)src/[^/]+/` to take only files inside a source set.
- `testPath`: regex marking test sources; they are skipped unless `--tests` is passed.
- `module`: what a module is; every module becomes one star cluster with its own colour.
  `srcParent` (default) takes the path up to `/src/`, the Gradle/Maven module (a root-level
  `src/` gets the project name). `topPackage` takes the first segment of the dotted name.
- `packageFrom`: where a file's package comes from. `declaration` (default) reads it from the
  file text with `packageRegex`. `path` derives a dotted name from the file's location
  (`alpha/core.py` is `alpha.core`, an `__init__` file collapses to its directory) and the
  file itself becomes importable under that name.
- `stripRoots`: comma list, only with `packageFrom: path`: the first matching leading
  directory is dropped before the path becomes a name, so `src` turns `src/alpha/core.py`
  into `alpha.core`.
- `memberRegex`: group 1 captures top-level members (functions, constants, properties). They
  join the declared names, so imports of a single member land on the declaring file.
- `samePackage`: `on` (default) links two files of the same package when one mentions a name
  the other declares, no import needed. Right for languages where same-package types are in
  scope automatically (Kotlin, Java); set it `off` where an import is always required
  (python), or every name mention becomes a noise edge.

## Look keys

All optional; the defaults are the tuned values, so a config lists only what it changes. The
values are plain numbers, and a comment after the number would break parsing.

| key | default | effect |
| --- | --- | --- |
| `look.minStarSize` | 1.8 | size of the smallest star |
| `look.starScale` | 1.9 | size a 200-line file adds on top |
| `look.starPower` | 1.55 | growth curve; above 1, big files grow disproportionately |
| `look.maxStarSize` | 60 | size cap |
| `look.edgeBrightness` | 0.21 | brightness of unselected dependency lines |
| `look.edgeFade` | 0.85 | how far lines stay visible, as a fraction of the graph radius |
| `look.drift` | 1.4 | amplitude of the slow star wobble |
| `look.hazePackage` | 0.022 | nebula haze around package knots |
| `look.hazeModule` | 0.012 | nebula haze around whole modules |
| `look.moduleSpacing` | 880 | distance scale between module clusters |
| `look.clusterSpacing` | 24 | module blob radius per file count |
| `look.packageSpacing` | 7 | package knot radius per file count |
| `look.clusterGap` | 2.6 | how far module blobs push apart (1 = touching) |
| `look.branchSpacing` | 55 | spacing of the flat layout in branch views and small graphs |

Star size is `minStarSize + starScale * (lines / 200) ^ starPower`, capped at `maxStarSize`.

## Adding a language

The template next to this file, [template.conf](template.conf), contains every key with the
optionals commented out. The steps, with JavaScript as the example:

1. Copy `template.conf` to `javascript.conf`, anywhere; you pass the path with `-c`.
2. `extensions: js, mjs, jsx`.
3. JS has no package statement, so take the python route: `packageFrom: path`,
   `module: topPackage`, `stripRoots: src`, and delete `packageRegex`. `src/auth/login.js`
   is now `auth.login` in module `auth`.
4. `importRegex: ^\s*import\s+(?:[^'"]*from\s+)?['"]([^'"]+)['"]` catches
   `import { url } from 'config'` and side-effect imports like `import './setup'`.
5. `declarationRegex: ^\s*(?:export\s+)?(?:default\s+)?(?:async\s+)?(?:function\*?|class)\s+(\w+)`,
   and `memberRegex: ^\s*(?:export\s+)?(?:const|let|var)\s+(\w+)\s*=` for consts and arrow
   functions.
6. `samePackage: off`, with python's reasoning: JS always imports, so name matching would
   only add noise.
7. Run `./depgraph -p ~/code/webapp -c javascript.conf --stats`, adjust until the file and
   dependency counts look right, then drop `--stats` and fly.

One honest limit: imports are resolved as dotted names, and JS imports quoted file paths.
`import config from 'config'` finds `src/config.js`, but slashes are not turned into dots, so
`'./util'` and `'../lib/helpers'` resolve to nothing and those edges go missing. The model
fits languages that import by name (the JVM family, python); for path-importing languages the
graph still shows files, sizes and modules, just fewer lines between them. Reworking this is
planned for a later version
([#1](https://github.com/SteveBurkert/no-mans-code/issues/1)).
