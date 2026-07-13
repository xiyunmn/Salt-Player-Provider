# Salt Player 歌词支持

Salt Player 歌词支持是面向词幕 Lyricon 的 LSPosed 歌词提供者模块，用于把 Salt Player（椒盐音乐）的当前歌曲、逐字歌词和翻译歌词提供给词幕显示。

当前主要适配：

```text
Salt Player 10.8.3-release ~ 12.1.0-release
com.salt.music
```

## 安装

1. 在支持 libxposed API 101 的 LSPosed 中安装并启用模块。
2. 将作用域设置为 Salt Player（`com.salt.music`）。
3. 强制停止并重新启动 Salt Player。
4. 打开词幕，确认可以看到 `Salt Player 歌词支持` 提供者。
5. 播放歌曲后，词幕会自动接收当前歌曲、逐字歌词和翻译歌词。

## 功能

- 向词幕提供 Salt Player 当前歌曲信息。
- 向词幕提供完整逐字歌词。
- 按词幕标准字段提供翻译歌词。
- 缓存 Hook 方法，减少重复扫描开销。

## 使用建议

- 宿主版本更新后，若出现无歌词、翻译异常或播放进度异常，请先确认 Salt Player 版本是否在适配范围内。
- 翻译显示受词幕自身的翻译开关控制。
- 本模块不会修改 Salt Player 的音乐文件或歌词文件。

## 构建

```powershell
.\gradlew.bat :app:assembleDebug
```

构建需要 JDK 17 和 Android SDK。

## 开源许可

Copyright (C) 2026 xiyunmn

本项目依据 [GNU General Public License v3.0](LICENSE)（SPDX：`GPL-3.0-only`）开源。
