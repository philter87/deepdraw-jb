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
./gradlew buildPlugin      # build/distributions/deepdraw-jb-<pluginVersion>.zip
```

then **Settings → Plugins → ⚙ → Install Plugin from Disk…**.

## Publishing to the Marketplace

**A new version on `main` is the release.** `.github/workflows/release.yml` runs
on every push to `main`, and its first step asks one question: did this push
change `pluginVersion`? Almost none do, and the job stops there in seconds. The
one that does is a release — the checks run, the zip goes to the Marketplace,
and a GitHub release with the same zip attached records it.

So releasing is bumping the version and merging it:

```bash
../deepdraw/sync-js.sh --bump 0.6.2   # writes pluginVersion here, and everywhere else
git commit -am "Take the shared DeepDraw version, 0.6.2" && git push
```

The version itself is never typed into this repo — it moves with
`deepdraw/sync-js.sh`, since the library and everything embedding it share one
number. Nothing else has to agree with it: the version *is* the request, so
there is no tag to push by hand, none to forget, and none that can name a
version nobody released. The `v` tag appears afterwards because a GitHub
release is a tag, written by the job that published, after the upload.

The build fetches the pinned DeepDraw bundle from the CDN, so a version can only
be released once the library's own CI has published it. A release failing on a
404 for `v<deepdrawVersion>` is that, and the fix is to wait.

**Run workflow releases whatever `main` says**, gate and all skipped —
Settings → Actions → Release → *Run workflow*. It is for a release that failed
after the version bump had already landed, when there is no push left to carry
it. On a version that already went up it stops at the Marketplace, which will
not take one twice.

**One secret is required**, under Settings → Secrets and variables → Actions:
`PUBLISH_TOKEN`, from plugins.jetbrains.com → your profile → **My Tokens**. The
same token publishes from a laptop, which is the fallback when the workflow is
in the way:

```bash
PUBLISH_TOKEN=… ./gradlew check verifyPlugin publishPlugin
```

`check` compiles and runs the format tests; `verifyPlugin` reads the plugin
against every IDE `sinceBuild` claims, with the same verifier JetBrains runs on
what arrives. Both run in the workflow too.

**The page was created by hand, once** — the upload API only writes to a page
that already exists, so a new plugin starts at
[plugins.jetbrains.com/plugin/add](https://plugins.jetbrains.com/plugin/add)
and waits for JetBrains to approve it. Everything the page shows comes out of
the zip: the name, the description and the compatibility range are
`plugin.xml`, so correcting a typo in the description means releasing a new
build, not editing the page.

**The channel is read off the version.** A plain `0.5.0` goes to the default
channel, which every IDE checks; a `0.6.0-beta.1` goes to a `beta` channel,
which only reaches readers who have added that channel's repository URL. There
is nothing to set — the suffix is the switch.

**Signing is optional.** The Marketplace signs whatever arrives unsigned, and
that is enough for the IDE to trust it. To sign with your own certificate
instead, add `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD` as
secrets (or as environment variables locally); `signPlugin` then runs before
`publishPlugin` and the signed zip is what goes up. JetBrains'
[Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)
page is how the key is made.

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
