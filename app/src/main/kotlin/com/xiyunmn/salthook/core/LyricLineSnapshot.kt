package com.xiyunmn.salthook.core

class LyricLineSnapshot(
    @JvmField val startMs: Long,
    @JvmField val endMs: Long,
    @JvmField val text: String?,
    @JvmField val translation: String?,
    @JvmField val words: List<LyricWordSnapshot>?,
    @JvmField val translationWords: List<LyricWordSnapshot>?,
)
