package de.tobisk.inkvault.ui

enum class PageLayoutMode(val storedValue: String) {
    SINGLE("single"),
    SCROLL("scroll");

    companion object {
        fun fromStored(value: String?): PageLayoutMode = entries.firstOrNull { it.storedValue == value } ?: SINGLE
    }
}

enum class PageFitMode(val storedValue: String) {
    WIDTH("width"),
    HEIGHT("height");

    companion object {
        fun fromStored(value: String?): PageFitMode = entries.firstOrNull { it.storedValue == value } ?: WIDTH

        fun preferenceKey(landscape: Boolean, sidebarVisible: Boolean): String = "pageFit:${if (landscape) "landscape" else "portrait"}:${if (sidebarVisible) "sidebarOpen" else "sidebarClosed"}"
    }
}
