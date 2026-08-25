# DeepDraw for JetBrains IDEs

Opens `.deepdraw.html` and `.deepdraw.json` files as drawings instead of as
text. It is [DeepDraw](https://deepdraw.ai) itself running in the IDE's embedded
browser — the same canvas, hierarchy, notes and icons — with the file you opened
as the place everything is stored.

Works in IntelliJ IDEA, WebStorm, PyCharm, GoLand, Rider and the rest: it uses
only platform API, so there is nothing language-specific in it.

## What it does

- **Draw, and press Ctrl+S.** Every edit marks the tab modified; saving writes
  the whole drawing back to the file that is open. Nothing is stored anywhere
  else.
- **The drawing is the first tab, the text is the second.** Clicking a
  `.deepdraw.json` opens the canvas; the JSON is one tab away at the bottom of
  the editor, with the IDE's own JSON support intact. Edit it there, come back,
  and the canvas reloads from it.
- **Two formats, one editor.**
  - `MyDrawing.deepdraw.html` — the drawing *and* the library in one file. It
    opens in any browser with no server behind it, which is the file to mail on
    or drop in a repo for people who do not have the IDE.
  - `MyDrawing.deepdraw.json` — the document on its own. Small, and it diffs.

  Save-as between them converts: the format follows the name you give the file.
- **Nested drawings.** A shape's children *are* its own drawing, so one file
  holds a whole hierarchy — double-click into a shape and keep going.
- **The hierarchy is an IDE tool window**, on the right, where a JetBrains
  reader looks for one. Clicking a shape that holds a drawing opens it; clicking
  a leaf goes to where it lives and selects it. The library's own hierarchy pane
  is switched off, so the canvas gets the width back.
- **Export** (Tools → DeepDraw → Export as …) writes `MyDrawing.deepdraw.html`,
  `.deepdraw.json` or `.deepdraw.png` wherever you point it. The PNG carries the
  document inside it, so the picture can be imported back as the drawing.

## Getting started

Needs a JDK 17 or newer on `JAVA_HOME` (the one bundled with your IDE will do —
**Settings → Build Tools → Gradle → Gradle JVM** if you run it from inside the
IDE). Then:

```bash
./gradlew runIde
```

That fetches the pinned DeepDraw bundle, builds the plugin, and starts a sandbox
IDE with it installed. **Tools → DeepDraw → New Drawing** creates a file and
opens it.

To make one by hand, a `.deepdraw.json` holding
`{"nodes":{"root":{"type":"root"}}}` is a valid blank drawing — DeepDraw fills
in every default it does not find.

To install it into your own IDE:

```bash
./gradlew buildPlugin      # build/distributions/deepdraw-jb-0.1.0.zip
```

then **Settings → Plugins → ⚙ → Install Plugin from Disk…**.

## Settings

**Settings → Tools → DeepDraw**

| Setting | What it does |
|---|---|
| Iconify API | Where the icon picker searches. Empty means fully offline, and it is the only thing in a drawing that reaches the network. |
| Create self-contained `.deepdraw.html` | What **New Drawing** makes. Unchecked, it creates a `.deepdraw.json`. |

## Undo is the drawing's

<kbd>Ctrl+Z</kbd> on the canvas is DeepDraw's undo, not the IDE's, and the IDE's
undo history for that tab stays empty. The drawing already implements undo and
redo per action; a second stack over the same file would fight with it. Undo in
the *text* tab is the IDE's, as usual.

## Requirements

A JetBrains Runtime with JCEF, which is the default for every JetBrains IDE
since 2020.2 — the drawing is a browser view. If the IDE was started on a
runtime without it, the editor says so and points at **Choose Boot Java Runtime
for the IDE**.

Built against 2024.3; `sinceBuild` is **233** (2023.3). `./gradlew verifyPlugin`
checks that claim against both with JetBrains' plugin verifier.

## Updating the library

`deepdrawVersion` in `gradle.properties` pins the DeepDraw version, and the
`fetchDeepDrawLib` task downloads that build from the CDN.

The pin is **0.3.0**, the first version with `hideTree` — the option that lets
this plugin draw the hierarchy itself.

To build against a version that is not published yet, point at a local checkout,
which is also how to try a library change and a plugin change in one sitting:

```bash
DEEPDRAW_LIB_DIR=../deepdraw/lib/dist ./gradlew runIde
```

## Notes

- The library is BSL 1.1 (`LICENSE` and `NOTICE` beside its bundle — both are
  downloaded with it and packaged into the plugin zip).
- Saving a `.deepdraw.html` rewrites it from the template of the *pinned*
  library version, so an old file picks up the current library when it is
  edited. The document itself is unchanged.
- This is the sibling of [`deepdraw-vs`](https://github.com/philter87/deepdraw-vs),
  the VS Code extension. The editors behave the same; where the code differs,
  `CLAUDE.md` §2 says why.
