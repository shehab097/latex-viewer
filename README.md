# LaTeX Viewer

A lightweight Android app for reading LaTeX (`.tex`) and Markdown (`.md`) documents on your phone or tablet. It renders documents locally with [KaTeX](https://katex.org/) and [marked](https://marked.js.org/) — no LaTeX installation and no network connection required.

## Features

- **Open from anywhere** — launch the app and pick a file, or open `.tex`/`.md` files directly from a file manager via the share/open-with menu.
- **Managed library** — on first use the app asks for a folder (the system picker starts at **Documents**). It creates a `LaTeX Viewer` folder inside the chosen folder and saves a copy of every document you open there. The originals are never changed. Because this is a real Storage Access Framework folder, the same structure is visible in Files.
- **Grid or list view** — toggle between a thumbnail grid and a compact file list from the toolbar.
- **Folders and multi-select** — create folders with the folder FAB. Long-press a file or folder to select it, then tap more items to add them. The selection toolbar offers **Copy**, **Cut**, **Move**, and **Delete**; use **More** for Open, Rename, Export as PDF, and Share.
- **Paste and drag-and-drop** — after Copy or Cut, navigate to a folder and tap the **Paste** icon. You can also long-press the drag handle (≡) on an item and drop it onto a folder to move it. Drag works for a single item or a multi-selection.
- **Folder browsing** — the overflow menu's **Browse folder** opens any external SAF folder for browsing, with back navigation. Files opened there are imported into the library, or you can choose **Open original** to read them in place.
- **Search** — filter the current folder by file name.
- **Markdown support** — GitHub-flavored Markdown rendered with `marked`, with LaTeX math passed through KaTeX.
- **LaTeX support** — a practical subset of LaTeX is rendered directly:
  - document metadata (`\title`, `\author`, `\date`, `\maketitle`)
  - chapters, sections, subsections and an auto-generated table of contents
  - `equation`, `align`, `gather`, `multline`, `eqnarray`, `flalign` and inline/display math
  - theorem-like environments (`theorem`, `lemma`, `proof`, `definition`, `example`, …), including `\newtheorem`
  - figures, tables, `tabular`/`tabularx`/`longtable`, captions and `\multicolumn`
  - itemize/enumerate/description lists, abstracts, quotes, footnotes
  - `\ref`, `\eqref`, `\cite`, `\label`, and `thebibliography`
  - custom macros via `\newcommand`, `\renewcommand`, `\providecommand`, `\def`, `\DeclareMathOperator`
  - inline formatting, accents, common text symbols, verbatim/code blocks
  - relative images from the document's folder
  - TikZ/PGF pictures rendered locally with a bundled WebAssembly TeX engine
- **Display settings** — choose Light, Dark, Gray, Grayscale, or Night theme; serif or sans-serif text; and a text size from 60% to 250%. Settings are remembered between sessions. Images and diagrams are inverted in the Dark theme so they stay legible.
- **Auto-hiding toolbar** — while reading, the top bar hides when you scroll down and returns when you scroll up or reach the top of the document.
- **Export as PDF** — use the viewer's overflow menu, or select a library item and choose **More → Export as PDF**, to open Android's print dialog and choose **Save as PDF**. The export is rendered in a light, print-friendly theme with A4 margins and a page-number footer, and it waits for TikZ diagrams to finish compiling.
- **Rename and share** — select a library item and choose **More → Rename/Share**, or use the viewer's overflow menu. Rename and delete act on the library copy, not on the original file.
- **Supported files only** — the in-app file picker filters to supported document types, and unsupported files are rejected.
- **Material You** — dynamic colors are applied on Android 12+ when available.
- **Offline** — KaTeX, its fonts, `marked`, and the TikZJax TeX engine are bundled in the APK; documents are read locally.

## Supported files

| Type | Extensions |
| --- | --- |
| LaTeX | `.tex`, `.ltx`, `.latex` |
| Markdown | `.md`, `.markdown`, `.mdown`, `.mkd` |
| Plain text | `.txt` |

## Requirements

- Android 7.0 (API 24) or newer
- Android Studio (or a command-line Android SDK) with JDK 17
- The project targets/compiles against Android SDK 34

## Building

```bash
# Debug APK
./gradlew assembleDebug

# Install on a connected device/emulator
./gradlew installDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Project structure

```
app/
├── src/main/
│   ├── AndroidManifest.xml
│   ├── assets/
│   │   ├── viewer.html          # LaTeX/Markdown renderer (KaTeX + marked)
│   │   ├── katex/               # bundled KaTeX JS, CSS and fonts
│   │   ├── tikzjax/             # bundled TikZJax WASM engine, TeX files and fonts
│   │   └── marked.min.js
│   ├── java/com/example/texviewer/
│   │   ├── App.kt               # Application, enables dynamic colors
│   │   ├── MainActivity.kt      # Library, managed folders, selection, drag/drop
│   │   ├── Library.kt           # SAF-backed Documents/LaTeX Viewer library
│   │   ├── ViewerActivity.kt    # WebView viewer, display settings, thumbnails
│   │   ├── DocAdapter.kt        # Grid/list adapter, multi-select and drag targets
│   │   └── Recents.kt           # Recents fallback and thumbnail files
│   └── res/                     # Layouts, menus, drawables, themes
├── build.gradle.kts
└── ...
```

## How it works

1. `MainActivity` shows the managed library tree, a real folder chosen through the Storage Access Framework. The tree grant is persisted with `takePersistableUriPermission`; the app creates `Documents/LaTeX Viewer` (or `LaTeX Viewer` inside whatever folder you chose) and imports a copy of every opened document. Older Recents entries are copied into the library the first time it is set up. If the library is not set up, the app falls back to the Recents list.
2. `ViewerActivity` reads the selected document (normally the library copy) and loads `viewer.html` into a `WebView`.
3. The HTML page receives the document text and renders it:
   - LaTeX goes through a custom parser in `viewer.html` that handles environments, macros, references, and inline formatting, using KaTeX for math.
   - Markdown is rendered with `marked` after math expressions are extracted and rendered by KaTeX.
   - TikZ blocks are compiled asynchronously by TikZJax and replaced with inline SVG.
4. After rendering, `ViewerActivity` captures the WebView with `PixelCopy` (or a canvas fallback on older devices) and stores a small JPEG thumbnail for the library grid/list. The thumbnail is keyed by the library copy's URI.
5. Images referenced with relative paths are resolved from the document's folder through `shouldInterceptRequest`. External `http`, `https`, `mailto`, and `tel` links open in the appropriate external app.
6. TikZJax requests its WASM/TeX resources from a private `https://tikzjax.local` host; `shouldInterceptRequest` serves them from the APK assets with CORS headers so the engine works fully offline.

## Limitations

This is a **reader**, not a LaTeX compiler:

- It renders a supported subset of LaTeX, not arbitrary TeX. Complex packages, custom classes, and advanced typography may not display as expected.
- TikZ rendering uses the packages and libraries bundled with TikZJax; pictures that depend on external fonts, images, or unsupported libraries may fail and fall back to a placeholder.
- TikZ rendering runs a WebAssembly TeX engine, so the first diagram in a document can take a few seconds to appear on slower devices.
- `\includegraphics` falls back to a labeled placeholder when the image cannot be resolved.
- PDF export is handled by Android's print dialog; choose **Save as PDF** as the printer. There is no built-in editor.
- The managed library needs a one-time folder permission. The default location is `Documents/LaTeX Viewer`; if you choose another parent folder, the app creates the same `LaTeX Viewer` folder there. If the permission is denied, the app can still show the Recents fallback but cannot import copies.
- Only the document file is copied. Sibling files such as images referenced by relative paths are not copied automatically; use **Browse folder → Open original** to read a document in its original folder, or place the images in the library folder yourself.
- Files opened through **Browse folder** live outside the managed library; deleting, renaming, or moving them from the app changes the original file. Delete shows an explicit warning; rename and move act immediately.
- Renaming requires the document provider to grant write access. Read-only files and some providers may refuse the rename.
- Rendering fidelity depends on the bundled KaTeX/marked versions.

## Third-party components

- [KaTeX](https://katex.org/) — math typesetting (MIT License)
- [marked](https://marked.js.org/) — Markdown parser (MIT License)
- [TikZJax](https://github.com/kisonecat/tikzjax) / [isomorphic-tikzjax](https://github.com/prinsss/node-tikzjax) — TikZ-to-SVG WebAssembly engine (LaTeX Project Public License 1.3c)
- BaKoMa Computer Modern fonts — bundled with TikZJax (see `assets/tikzjax/bakoma/LICENCE`)
- [AndroidX](https://developer.android.com/jetpack/androidx) and [Material Components for Android](https://github.com/material-components/material-components-android) — UI libraries

See the respective projects for their license texts.
