# LaTeX Viewer

A lightweight Android app for reading LaTeX (`.tex`) and Markdown (`.md`) documents on your phone or tablet. It renders documents locally with [KaTeX](https://katex.org/) and [marked](https://marked.js.org/) — no LaTeX installation, no network connection, and no PDF export required.

## Features

- **Open from anywhere** — launch the app and pick a file/folder, or open `.tex`/`.md` files directly from a file manager via the share/open-with menu.
- **Library view** — recently opened documents are kept in a grid with generated page thumbnails. Long-press a recent item to remove it from the list (the file itself is untouched).
- **Folder browsing** — pick a folder once and browse its subfolders and supported documents, with back navigation.
- **Search** — filter the current list by file name.
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
- **Display settings** — choose Light, Dark, Gray, Grayscale, or Night theme; serif or sans-serif text; and a text size from 60% to 250%. Settings are remembered between sessions.
- **Material You** — dynamic colors are applied on Android 12+ when available.
- **Offline** — KaTeX, its fonts, and `marked` are bundled in the APK; documents are read locally.

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
│   │   └── marked.min.js
│   ├── java/com/example/texviewer/
│   │   ├── App.kt               # Application, enables dynamic colors
│   │   ├── MainActivity.kt      # Library, recents, folder browsing, search
│   │   ├── ViewerActivity.kt    # WebView viewer, display settings, thumbnails
│   │   ├── DocAdapter.kt        # Grid adapter with thumbnail cache
│   │   └── Recents.kt           # Recents storage and thumbnail files
│   └── res/                     # Layouts, menus, drawables, themes
├── build.gradle.kts
└── ...
```

## How it works

1. `MainActivity` lists recent documents or the contents of a folder chosen through the Storage Access Framework. File access is persisted with `takePersistableUriPermission`, so recents keep working across launches.
2. `ViewerActivity` reads the selected document and loads `viewer.html` into a `WebView`.
3. The HTML page receives the document text and renders it:
   - LaTeX goes through a custom parser in `viewer.html` that handles environments, macros, references, and inline formatting, using KaTeX for math.
   - Markdown is rendered with `marked` after math expressions are extracted and rendered by KaTeX.
4. After rendering, `ViewerActivity` captures the WebView with `PixelCopy` (or a canvas fallback on older devices) and stores a small JPEG thumbnail for the recents grid.
5. Images referenced with relative paths are resolved from the document's folder through `shouldInterceptRequest`. External `http`, `https`, `mailto`, and `tel` links open in the appropriate external app.

## Limitations

This is a **reader**, not a LaTeX compiler:

- It renders a supported subset of LaTeX, not arbitrary TeX. Complex packages, custom classes, and advanced typography may not display as expected.
- TikZ/PGF pictures are shown as placeholders rather than rendered drawings.
- `\includegraphics` falls back to a labeled placeholder when the image cannot be resolved.
- There is no PDF output and no editing capability.
- Rendering fidelity depends on the bundled KaTeX/marked versions.

## Third-party components

- [KaTeX](https://katex.org/) — math typesetting (MIT License)
- [marked](https://marked.js.org/) — Markdown parser (MIT License)
- [AndroidX](https://developer.android.com/jetpack/androidx) and [Material Components for Android](https://github.com/material-components/material-components-android) — UI libraries

See the respective projects for their license texts.
