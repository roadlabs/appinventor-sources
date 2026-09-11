# CLAUDE.md

Guidance for Claude Code working in `appinventor_2026`.

## What this repository is

`appinventor_2026` is **roadlabs' working copy of the MIT App Inventor sources** (forked from `mit-cml/appinventor-sources`, pushed to `roadlabs/appinventor-sources`), extended into the **offline-webapp** product: a fully static, Android-only App Inventor that runs from a local `offline-webapp/` folder with no App Engine backend.

The companion repo `appinventor-sources/` (in the same workspace root) is the **upstream** mirror — read-only, tracked against `mit-cml`. Treat that checkout's `CLAUDE.md` as authoritative for upstream behavior. `appinventor_2026` is where real work happens; it carries the offline fork delta on top of upstream.

## Hard constraints

- **Never modify AI Companion code.** All offline behavior must be implemented in the browser/GWT/Blockly build and existing Companion-compatible protocols. The one allowed exception is `ComponentConstants.java` (`components/common/`, not `runtime/`) setting `DEFAULT_THEME = "AppTheme.Light"`.
- Work on a dedicated non-`master`/non-`main` branch (`offline-webapp/<topic>`).
- The offline fork delta is documented by the `appinventor-offline-webapp` skill (see below). Consult it before changing offline-specific code.

## Directory map

| Dir | What |
|---|---|
| `appinventor/` | Upstream MIT App Inventor sources (Java + Blockly/GWT) + the offline fork delta |
| `appinventor/blocklyeditor/src/replmgr.js` | Companion connection manager. **Contains the WebRTC asset-transfer fix** — see "WebRTC asset transfer" below |
| `appinventor/blocklyeditor/src/generators/yail.js` | YAIL generator. Must stay clean — the `file://` property rewrite that broke asset display was reverted |
| `offline-webapp/` | **Generated artifact** (disposable): static site the user opens. `index.html` selects one `ode/aiblockly-*.cache.js` |
| `offline-tauri/` | Tauri 2 desktop wrapper (added, not upstream) |
| `solution/` | Scratch / one-off |

## Build commands

All commands run from `appinventor/`.

```bash
cd appinventor

ant MakeAuthKey                                   # once, before first build
ant -Dlocal.services=true -Dlocale=en -Drelease=true webapp   # build offline webapp (blocklyeditor + appengine)
```

After `ant webapp`, sync the build to `offline-webapp/` — use the skill's builder, not manual copies:

```bash
bash <skill>/scripts/build-webapp.sh "$PWD"       # from repo root; copies war → offline-webapp/, regenerates index.html
```

The browser executes the generated `ode/aiblockly-*.cache.js` selected by `offline-webapp/index.html` — **not** `appinventor/blocklyeditor/src/*.js`. Editing source alone does nothing; rebuild and confirm the active cache changed.

## WebRTC asset transfer (verified fix, 2026-09)

Symptom that motivated the fix: asset-bearing projects connect to the AI Companion over WebRTC but images/audio do not display.

Two root causes were fixed in `replmgr.js`:

1. **Querying Companion state via `RetValManager:setReturnValue` is impossible** — `RetValManager` has only the 3-argument `appendReturnValue blockid ok item`. Any `setReturnValue` call fails as `no method`, so the result is `NOK` and downstream capture never fires. A `file://` property rewrite in `yail.js` built on that dead query was reverted; `yail.js` must stay at upstream.
2. **Ordinary-asset chunks must be sent as bare eval.** Wrapping file-transfer chunks in `process-repl-input` changes a chunk's Base64 length in transit; the Companion then fails `Base64:getDecoder` with `Input byte array has incorrect ending byte at ...`. The verified pattern (mirrors `saveProjectArchiveWebRTC`):
   - encode once with `btoa`, keep full `=` padding;
   - chunk in 4-multiple sizes (`STR_CHUNK = 8000`);
   - mark Init/Chunk/Final messages `bare: true` and send straight through the DataChannel (bypass `process-repl-input` and `engine.chunker`);
   - per chunk: `(invoke (java.util.Base64:getDecoder) (quote decode) chunk)` → `FileOutputStream(File, append)` → `write` → `close`; build the `File` via `Paths:get(...)` then `.toFile()` (the `FileOutputStream(File, boolean)` overload resolves reliably in Kawa);
   - `Files:createDirectories` the asset root before writing;
   - **after the last chunk, set `ReplForm.assetsLoaded`** with `(invoke (Form:getActiveForm) (quote setAssetsLoaded))` before `assetTransferred`. Without `assetsLoaded == true`, `MediaUtil` falls back to APK assets and the transferred file is never read (silent no-display). Do not use `PhoneStatus:new (Form:getActiveForm)` — that constructor is ambiguous in Kawa.

See the `appinventor-offline-webapp` skill's `references/companion-transfer.md` and `references/failure-cookbook.md` for the full contract and known failures.

## The `appinventor-offline-webapp` skill

The offline fork delta and all diagnostics live in the `appinventor-offline-webapp` skill:

- `SKILL.md` — porting workflow, hard boundaries, troubleshooting index
- `references/companion-transfer.md` — Companion transfer contracts (legacy HTTP PUT vs WebRTC DataChannel)
- `references/failure-cookbook.md` — known failures with exact fixes (asset display, extension load, Save Project, etc.)
- `references/source-files/` — embedded offline-fork source files with SHA-256 hashes (`replmgr.js` included; keep in sync when it changes)
- `scripts/verify-port.py` — signature checks that must pass when the embedded sources drift
- `scripts/check-offline-webapp.py` — artifact-level checks on `offline-webapp/`

**After editing `replmgr.js`** (or any embedded source), also update the skill's embedded copy + `HASHES.json` + `MANIFEST.md`, or `verify-port.py` will report drift.

The skill repo lives at `appinventor-offline-webapp/` (workspace root); its installed copy at `~/.claude/skills/appinventor-offline-webapp/` is the same files (same inode).
