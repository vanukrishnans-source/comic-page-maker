package com.vanu.comicpage.model

import android.net.Uri

enum class PageStyle(val label: String) {
    WEBTOON("Webtoon"), CLASSIC("Classic"), MANGA("Manga"), GRID("Grid")
}

enum class PanelFit(val label: String) { FILL("Crop"), FIT("Fit") }

enum class BubbleKind { SPEECH, BOX }

data class Bubble(
    val id: Long,
    val text: String,
    /** Centre of the bubble inside its panel, 0..1 of panel width / height. */
    val cx: Float = 0.5f,
    val cy: Float = 0.22f,
    /** Bubble width as a fraction of the panel width. Text size scales with it. */
    val size: Float = 0.5f,
    val kind: BubbleKind = BubbleKind.SPEECH,
)

data class Panel(
    val id: Long,
    val uri: Uri,
    val name: String,
    val dateMs: Long,
    /** Display width/height after EXIF rotation. */
    val width: Int,
    val height: Int,
    val bubbles: List<Bubble> = emptyList(),
) {
    val aspect: Float get() = if (height <= 0) 1f else width.toFloat() / height
}

/** Layout + look of the page. Lengths are expressed at a 1080 px page width and scale with the page width. */
data class PageSettings(
    val style: PageStyle = PageStyle.CLASSIC,
    val gutter: Int = 24,
    val borderWidth: Int = 4,
    val borderColor: Int = 0xFF000000.toInt(),
    val radius: Int = 0,
    val background: Int = 0xFFFFFFFF.toInt(),
    val fit: PanelFit = PanelFit.FILL,
    val pageWidth: Int = 1080,
    /** Classic / Manga: maximum panels per row (1..3). */
    val maxPerRow: Int = 3,
    /** Grid: number of columns (2..5). */
    val gridCols: Int = 2,
    /** Grid: cell width / height. */
    val gridAspect: Float = 1f,
)
