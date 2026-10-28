# Comic Page Maker

Android app (Kotlin + Jetpack Compose, package `com.vanu.comicpage`) that turns a list of your own images into one long, scrollable comic-style page, fully offline. No network permission, no machine learning, no accounts.

## What it does
1. **Add** pictures from the photo picker, from files (many at once) or from a whole folder. You can also share images to the app from the gallery.
2. **Arrange**: thumbnails with a number badge. Hold and drag to reorder, or select one and use **Earlier / Later / Remove**. Sort by **Name** (natural order: page2 before page10) or **Date**, **Reverse**, **Clear**.
3. **Page preview**: choose a style and see the page live (this is the only scrolling area).
   * **Webtoon**: full-width vertical strip with an adjustable gap.
   * **Classic**: rows of 1, 2 or 3 panels with gutters and borders. Rows are chosen with a small dynamic program so row heights stay balanced for the mix of portrait and landscape images.
   * **Manga**: the same, read right to left.
   * **Grid**: 2 to 5 columns with a chosen cell shape (1:1, 4:3, 3:4, 16:9).
   * **Tune**: gap, border width and colour, corner radius, page colour, crop or fit, page width 1080 / 1440 / 2160 px.
   * **Text**: tap a panel, add a speech bubble or caption box, type, drag it, make it bigger or smaller.
4. **Export** to **PNG** (streamed row by row), **JPG** or multi-page **PDF**. Images are saved to `Pictures/ComicPage` (PDF to `Download/ComicPage`). Very tall pages are split into numbered parts at panel-row boundaries, or kept as one PNG on request. A share button is shown afterwards.

## Memory design
Images are probed for size only, then decoded down-sampled for the thumbnails, the preview and the export. A byte-bounded LRU keeps decoded bitmaps in check. The export renders the page in tiles of about 3 megapixels and writes them straight into a PNG with a small streaming encoder (`PngStreamWriter`), so a 2160 px wide page with 60 big photos never exists as one bitmap.

## Build
```
./gradlew testDebugUnitTest   # layout engine + PNG writer tests
./gradlew assembleRelease     # needs keystore.properties + release.jks (not in the repo)
```
The release APK is signed with a private key that is not in this repository, so `assembleRelease` falls back to the debug key when `keystore.properties` is missing.

Debug builds contain a test hook (intent extras `demo_dir`, `demo_style`, `demo_export`, ...) used for emulator trials. It is disabled in release builds (`DEMO_HOOK=false`).
