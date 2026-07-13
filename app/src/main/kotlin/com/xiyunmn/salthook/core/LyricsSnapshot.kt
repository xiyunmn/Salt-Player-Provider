package com.xiyunmn.salthook.core

class LyricsSnapshot(
    @JvmField val songId: String?,
    @JvmField val lines: List<LyricLineSnapshot>?,
    @JvmField val source: String?,
)
