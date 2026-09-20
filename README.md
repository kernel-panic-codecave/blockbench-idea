# blockbench-idea

![Build](https://github.com/kernel-panic-codecave/blockbench-idea/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

Open and edit [Blockbench](https://www.blockbench.net) models directly inside IntelliJ IDEA, without an external editor.

Double-clicking a `.bbmodel` file opens an editor tab with the Blockbench web app
embedded via JCEF:

- the model is loaded from disk into Blockbench,
- <kbd>Ctrl/Cmd+S</kbd> serializes the current project (using Blockbench's own codec, so both formats round-trip
  correctly) and writes it back to the file,
- the editor tab shows the unsaved-modified indicator,
- the **Text** tab remains available for raw JSON access.

The editor loads a local Blockbench web build. Set `BLOCKBENCH_URL` to a self-hosted URL, or
`BLOCKBENCH_WEB_ROOT` to an unpacked Blockbench checkout/build containing `index.html`. The web build runs in the
IDE's JCEF profile; Electron's `app.asar` and native profile cannot be embedded directly.

Managed runtimes synchronize the standalone Blockbench plugins and settings with the embedded web instance in both
directions. Changes made in the embedded instance are written back to the discovered standalone profile, and changes
already present in that profile are imported when a runtime starts. The standalone Electron profile itself is not
reused because it depends on Electron APIs and a separate Chromium profile.

## Usage

1. Create/edit a file ending in `.bbmodel` or `.geo.json`.
2. Double-click it — the Blockbench editor tab opens (Text tab stays available).
3. Edit, then press <kbd>Ctrl/Cmd+S</kbd> to save back to disk.

### Configure the Blockbench runtime

Blockbench versions can also be managed from **Settings/Preferences > Tools > Blockbench**. The plugin loads all
released tags from the GitHub API into a release selector. Choose a version and click **Install**. The plugin clones
the matching release tag, runs `npm install`, runs
`npm run build-web`, and serves the generated web distribution through a loopback service owned by the plugin;
`npm run serve` is not required. Installation runs as an IntelliJ background task with progress and cancellation
support.

Before the web bundle starts, the plugin generates a bootstrap file containing compatible settings and local plugin
references from the standalone Blockbench data directory. The data directory is discovered using the platform's
standard application-data location, so the same configuration works on Windows, macOS, and Linux.

For a manual local Blockbench checkout, start its supported web server first:

```bash
git clone https://github.com/JannisX11/blockbench.git
cd blockbench
npm install
npm run serve
```

Then set the runtime URL before starting the IDE:

```text
BLOCKBENCH_URL=http://localhost:3000/
```

Alternatively, `BLOCKBENCH_WEB_ROOT` can point to an unpacked published web build containing both `index.html` and
`dist/bundle.js`.

If neither variable is set and no local runtime is found, the plugin downloads and caches the latest Blockbench
web build under the IDE system directory on first use. The cached copy is reused for subsequent editor tabs.

## Development

Build and test locally with:

```
./gradlew build
```

Run the IDE with the plugin installed:

```
./gradlew runIde
```

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "blockbench-idea"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/kernel-panic-codecave/blockbench-idea/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>