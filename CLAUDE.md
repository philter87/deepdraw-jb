# CLAUDE.md

The JetBrains plugin for DeepDraw. The library lives in the `deepdraw`
repository (`lib/`) and is consumed here as a published bundle; this repo is the
file-backed editor around it and nothing more. It is the sibling of
`deepdraw-vs`, and where the two differ the difference is the IDE, not a
decision — §2 is the one place that matters.

## 1. Layout

| Path | What it is |
|---|---|
| `src/main/kotlin/.../DeepDrawFileEditorProvider.kt` | Which files are drawings, and where the canvas sits among their editors |
| `src/main/kotlin/.../DeepDrawFileEditor.kt` | The editor — the document, the dirty state, save, revert, export |
| `src/main/kotlin/.../DeepDrawWebView.kt` | The embedded browser and the conversation with it |
| `src/main/kotlin/.../DeepDrawResources.kt` | The page and its files, served to that browser |
| `src/main/kotlin/.../DeepDrawFormat.kt` | The two file formats, in both directions |
| `src/main/kotlin/.../DeepDrawHierarchy.kt` | The drawing's hierarchy, as a native tool window |
| `src/main/kotlin/.../DeepDrawActions.kt` | New Drawing, and the three exports |
| `src/main/kotlin/.../Protocol.kt` | Every message between plugin and webview |
| `src/main/resources/deepdraw-web/main.js` | Mounts the library, reports edits, answers questions |
| `build.gradle.kts` → `fetchDeepDrawLib` | Brings the pinned library into the plugin's resources |
| `src/test/kotlin/.../FormatTest.kt` | Round-trips a drawing through the real template |

## 2. The `Document` is the store

**This is the one real departure from `deepdraw-vs`, and it is not a choice.**
VS Code hands a custom editor its own document type and asks for the bytes on
save. IntelliJ has no such thing: unsaved state, <kbd>Ctrl+S</kbd>, Local
History, VCS, "the file changed on disk" and the tab's own modified marker all
hang off `com.intellij.openapi.editor.Document`. An editor that kept the drawing
beside it would have to reimplement every one of those.

So the drawing is written *into the file's text*, and everything else follows
from the IDE:

- the webview says **that** something changed (`edit`), carrying no payload;
- once the drawing has been still for a second, this side asks **what** it now
  is (`getContent`) and writes that text into the `Document`;
- the IDE marks the tab dirty and saves it like any other file.

**There is still no reducer here, and there should not be one.** The library
owns a document and every change to it; a copy of that model on the plugin side,
kept in step by replaying actions, would be a second implementation of the thing
that already works.

**The pull is debounced on quiet, not on a timer.** A drag reports an edit per
frame, and a `.deepdraw.html` is a 200kb-odd page; rewriting it sixty times a
second would reparse that file sixty times a second. Nothing is written while
the drawing is moving, and one write lands a second after it stops.

**A save cannot wait a second.** `beforeDocumentSaving` — the hook that strips
trailing whitespace on save — pulls the document across and *waits* for it,
bounded, so <kbd>Ctrl+S</kbd> straight after a stroke writes that stroke. It
returns the last known drawing if the canvas does not answer: a save that cannot
reach the webview should write the drawing as last known, never an empty file.
`deselectNotify` and `dispose` do the same, for the same reason.

**Writes are undo-transparent.** Undo, redo and history are already implemented
inside the drawing, per-user and per-action. A write that left an entry on the
IDE's undo stack would put two stacks over one document. The consequence to know
about: <kbd>Ctrl+Z</kbd> on the canvas is the library's undo, and the IDE's undo
history for that tab is empty.

**`selectNotify` reloads when the text moved underneath.** The text tab is one
click away (§3), and so are a revert and a branch switch. The canvas is reloaded
from the file whenever it comes forward holding something this side did not
write.

**A rename is not an action.** The drawing's title is written onto the document
rather than through the action bus, so `onTitleChange` is wired to the same
`edit` message. Without it, renaming a drawing would never mark the file dirty.

## 3. Bound by name, placed before the default editor

`accept` is a file-name test, exactly as in `deepdraw-vs`. Declaring a
`FileType` for `.deepdraw.html` or `.deepdraw.json` would take those files away
from the HTML and JSON editors that already understand them.

`PLACE_BEFORE_DEFAULT_EDITOR` is what makes clicking a drawing open the drawing:
the canvas is the first tab and the text is the tab beside it. That is
`deepdraw-vs`'s "Reopen in Text Editor" command, except that it is always there
rather than being something to go and find — which is why no such action exists
here.

## 4. The two formats (`DeepDrawFormat.kt`)

Both hold the same document (deepdraw §4). The HTML one is DeepDraw's own
standalone page: the library inlined, the document in a
`<script id="dd-document" type="application/json">`.

**That page is a string replacement into `template.html`, which the library
publishes beside its bundle.** Building the page here instead would be a second
copy of it in a second repository, and two copies of a page drift in the
direction nobody is looking. `fetchDeepDrawLib` fails the build if the template
has lost a mark, and `FormatTest` round-trips a drawing through the real file
rather than a fixture, because a page that is subtly wrong is found out by
whoever opens it, not by whoever built it.

Two details that break a page silently, both covered by tests:

- **`<` in the document is written `\u003c`.** A literal one inside a label or
  an inlined SVG closes the script tag early and the file is broken.
- **The credit is carried across saves.** It is chrome, never part of the
  document, so re-saving a generated file would quietly un-credit whatever drew
  it unless the fragment is read back out and written again.

**Exports are named `.deepdraw.<ext>`**, from the file's own name rather than
the drawing's title: in an editor the file is the thing that has a name. An
export does not write the `Document` — exporting a drawing is not editing it,
and a "Save as PNG" that quietly dirtied the file would be a surprise.

## 5. The webview

`deepdraw.js` is served as its own `<script src>`, not inlined into a page
string, so a library upgrade is a re-fetch.

**The page is served, not `loadHTML`-ed.** A scheme handler registered for
`http://deepdraw/` gives the page a real origin and keeps the library a separate
file. `loadHTML` would mean building a 350kb string on every open and an origin
the browser treats as opaque, which the icon picker's `fetch` then has to talk
its way out of. The handler resolves every path against a fixed resources root
and 404s anything that escapes it.

**The handler extends `CefResourceHandlerAdapter`, never the interface.** JCEF
carries two APIs in one interface — `processRequest`/`readResponse` and the
newer `open`/`read`/`skip` — and which of them are abstract depends on the JBR
the reader's IDE ships. A class that implements the interface directly is
complete against the JCEF it was built on and three methods short against a
newer one, which the page finds out as an `AbstractMethodError` the first time
it asks for a file. The adapter has whichever set its own JCEF declares, and its
`open` and `read` are defined to fall back to the older pair, which is what this
handler implements: they are the only ones 2023.3 has.

**The page cannot speak first.** `window.__deepdrawPost` is built from a
`JBCefJSQuery`, which only exists on the plugin's side. So the page defines
`__deepdrawBoot` and waits; when the load finishes, the plugin writes the bridge
onto `window` and calls it. That is why `ready` is sent from `__deepdrawBoot`
rather than from the bottom of `main.js` — a message sent before the bridge
exists has nowhere to go, and polling for it would be a race dressed up as a
fix.

- **`hideTopBar: true`.** The IDE is the chrome — the file has a name in a tab,
  saving is <kbd>Ctrl+S</kbd>, exporting is an action. The library's own bar
  exists for a standalone file opened off a disk, and its export items write
  through a download link, which has nowhere to land in an editor.
- **The theme follows the IDE**, through `LafManagerListener` rather than read
  once, because the reader can switch themes with the drawing open.
- **The CSP lets nothing out except over https**, which is as tight as it can be
  while the icon API is a setting rather than a constant.

## 6. The hierarchy is the IDE's tree, not the library's

The library draws a hierarchy pane of its own and is told not to (`hideTree`,
added in 0.3.0, which is what the pin is). A tree inside the canvas would
compete with the tool window the IDE already has, in the place a reader looks
for exactly this — and hiding it gives the drawing the width back.

The webview posts a `tree` snapshot — flat, already labelled, already ordered —
whenever the document, the open drawing or the selection changes, and the tool
window renders it for whichever drawing is in front. The snapshot is built with
the library's own helpers (`nodeLabel`, `childrenOf`, `resolve`), so a row here
says what the same row said in the pane it replaces — arrows included: they are
relationships rather than places, and neither tree lists them.

Editors publish their snapshots on a project topic rather than being handed to
the window, so an editor that is not in front still reports and the window
ignores it. Three things the walk has to get right:

- **`seen` is the path, not every node visited.** A link node may point at an
  ancestor, and a hierarchy that walks into one never comes back.
- **A click is not one action.** A node with a drawing inside it opens
  (`navigateTo`); a leaf is shown where it lives. Which of the two it is, is the
  webview's decision — the plugin has a flat list, not the document.
- **A rebuild moves the selection.** `rebuilding` is what stops the tree's own
  repopulation from being read as a click and navigating the canvas.

**Revealing a leaf watches for the arrival.** Every hop of an animated journey
calls `setCurrent`, which clears the selection, so a selection set before the
journey does not survive it. The library applies its own on arrival through a
callback on its canvas, which is private; a host has `navigateTo` and no
callback. So `goTo` subscribes to `current`, applies the selection when the
journey lands on the parent, and gives up after three seconds. Delete that the
day the library offers a public reveal.

## 7. The library version

`deepdrawVersion` in `gradle.properties` is the one place it is stated. The
bundle is downloaded from the CDN under an immutable version path, which is the
supported way to embed the library (deepdraw §3) and keeps this build the same
everywhere. `DEEPDRAW_LIB_DIR` points at a local `lib/dist` instead, which is
the only way to try a library change and a plugin change in one sitting.

## 8. Checks

```bash
./gradlew check          # compile, the format tests, and the plugin verifier's structure checks
./gradlew verifyPlugin   # read the plugin against the IDEs it claims (a download each)
./gradlew buildPlugin    # the installable zip, in build/distributions
./gradlew runIde         # a sandbox IDE with the plugin in it
./gradlew publishPlugin  # upload that zip to the Marketplace (PUBLISH_TOKEN)
```

A release is a `v*` tag: `.github/workflows/release.yml` checks that the tag and
`pluginVersion` agree, runs `check` and `verifyPlugin`, publishes, and attaches
the same zip to a GitHub release. `publishPlugin` by hand is the fallback, not
the route. The Marketplace page itself was created once, by hand, from a zip —
the upload API only writes to a page that already exists. Signing and the token are read
out of the environment and nothing else — a key or a token in a file here would
be a key or a token in the repository. The channel is derived from
`pluginVersion` rather than passed, so a pre-release cannot reach the default
channel by someone forgetting a flag. README, "Publishing to the Marketplace",
is the walk-through.

`verifyPlugin` reads three IDEs: the oldest `sinceBuild` claims, the one this
builds against, and the newest released. The newest end is the one that earns
its download — no `untilBuild` is a promise about IDEs that do not exist yet,
and JCEF's own interfaces gained methods in 2025.3, which a plugin built on
2024.3 meets as a missing method rather than a compile error. The Marketplace
runs the same verification after an upload and files what it finds as an issue,
which is a slow way to hear it. From 2025.3 the Community and Ultimate downloads
are one, so the newest entry is `IntellijIdeaUltimate`.

`runIde` is the only way to see the canvas: JCEF needs a display, so nothing
about the browser is covered by a test. What *is* covered is the part that
breaks silently — the page a drawing is written into (§4).
