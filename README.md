# Memory Keeper

一款只为个人使用的 Android 应用，每天被动捕获你的对话，当晚生成一封约 300 字的「今日纪录」让你读。

完整产品设计见 `docs/spec.md`（v0.1）。本仓库当前只包含 Android 实现。

---

## 拿到 APK 的两种方式

### 1. 直接下载（推荐）

每次 push 到 `claude/android-app-apk-build-BCipZ` 分支，GitHub Actions 会自动构建 APK 并以 Release Asset 形式发布。

- 打开仓库的 **Releases** 页面
- 找到名为 `Memory Keeper — latest from claude/android-app-apk-build-BCipZ` 的 prerelease
- 用手机浏览器下载 `app-release.apk`
- 安装时如果系统提示「来自未知来源」，授予一次「允许此源」即可

Workflow 也会把 APK 作为 workflow artifact 保留 30 天。

### 2. 本地用 Android Studio 构建

如果你想自己改代码或本地构建：

```bash
cd android
./gradlew :app:assembleRelease
# APK 在 app/build/outputs/apk/release/
```

或在 Android Studio 打开 `android/` 目录，Run / Build APK 即可。

---

## 首次使用

1. 安装 APK，打开应用
2. Onboarding：3 个问题 + 30 秒录音（约 2.5 分钟）
3. 进入主屏后打开「设置」→「AI 服务」，选择服务商并填入 API Key
   - 支持：Anthropic、OpenAI、DeepSeek、GLM（智谱）、OpenRouter，以及任意 OpenAI 兼容的自定义端点（需填 Base URL、模型 ID）
   - 每个服务商各自保存 Key / 模型 / Base URL；默认模型可改
   - Key 通过 `EncryptedSharedPreferences` 加密本地存储，只在调用所选服务商接口时被读出
4. 回到主屏，开「正在监听」toggle（首次会弹麦克风权限）
5. 当晚回来打开 app，点「现在生成今天的纪录」

---

## 设计取舍 / 已知简化（MVP）

MVP 严格忠于 spec 的「诚实而不是精致」原则。这一版有意做减法：

| Spec 要求 | MVP 实现 |
|---|---|
| Voice fingerprint 过滤（§13） | 暂不做声纹比对；transcript 全部标注 `speaker=self?`，第一周通过 correction loop 重归属 |
| 家人 / 工作人物自动识别（§13.2/13.3） | 全部走 USER.md 手填 + correction loop |
| Semantic retrieval 旧 MEMORY entry（§12.5） | 只取最近 14 天的全文；语义检索 v2 再做 |
| 日历集成（§13.3） | 暂无 |
| Long-press 三个选项（§7） | MVP 暂只实现「记住这件事」（pin to MEMORY.md），「这不是我」「这不重要」v2 加入 |
| 月度/年度回顾、检索（§16） | 显式不做，与 spec 一致 |

后续迭代会沿 §15 的成长性 narrative 推进。

---

## 隐私 & 数据流（实现实况）

- 监听时持续读麦克风，由本地开源 VAD（Silero VAD，经 sherpa-onnx）判断是否有人声，只把含人声的片段（前后各留 ~0.3 秒，停顿 < 1.5 秒合并，单段最长 30 秒）存成 AAC 文件（`data/audio/recordings/<日期>/HHmmss.aac`），静音/纯噪音直接丢弃；保留 7 天，可在「记忆 → 原始转录」页导出
- 语音转文字完全在手机本地完成：开源模型 SenseVoice-Small（int8，约 160 MB，首次在「设置 → 本地语音转文字」里下载），通过 sherpa-onnx 运行。手机充电且空闲时自动批量转录，生成信件前也会补转剩余的片段。音频不上传，上云的只有转录后的文字
- Onboarding 时的 voice fingerprint 录音 (`~/MemoryKeeper/data/audio/voice_self.m4a`) 暂作占位保存，未来用于声纹比对
- Transcripts 本地存 7 天，逾期自动删（`FileRepository.pruneOldTranscripts`）
- API Key 用 AES-256-GCM 加密存储（`EncryptedSharedPreferences`）
- 信件生成时上云的内容：SOUL.md + USER.md + 近 14 天 MEMORY.md + 当日 transcript（已按 `no_go_topics` 过滤）
- 信件文本不回传给 Anthropic、不写入任何云端

---

## 本地构建

sherpa-onnx 的原生库不入库，本地构建前先运行一次 `scripts/fetch-sherpa-libs.sh`（CI 会自动执行）。

## 第三方组件

- sherpa-onnx（Apache-2.0）：`android/app/src/main/java/com/k2fsa/sherpa/onnx/` 为其 Kotlin API 源码原样拷贝
- Silero VAD（MIT）
- SenseVoice（FunASR Model License，个人使用）

## 构建要求

- JDK 17（CI 已固定）
- Android Gradle Plugin 8.5.2
- Kotlin 2.0.21
- compileSdk 34, minSdk 26, targetSdk 34

APK 用 AGP 自动生成的 debug keystore 签名，所以可以直接安装。这是单用户私人应用，正式上线再换正式签名。
