# 云澄 Android

本地、手动触发的 Android 音乐整理工具。应用通过系统目录选择器访问用户明确授权的目录，不申请全盘存储权限，不联网，也不在后台自动扫描。

## 功能

- 手动选择歌曲来源与输出目录，记住已授权位置
- 递归检测 NCM、FLAC、MP3、WAV、M4A、OGG、OPUS、AAC、APE
- NCM 本地流式解析与转换，不依赖 Python、服务器或 Root
- 自动识别解密后的真实格式，不把无损音频转码为 MP3
- 为 FLAC/MP3 写入标题、歌手、专辑和内嵌封面
- 复制同名 LRC 歌词
- 1–4 路可调并发，逐首显示结果

## 手机使用

1. 安装 `app-debug.apk` 后打开“云澄”。
2. 点击“歌曲来源”，选择 `/storage/emulated/0/Download/netease/cloudmusic/Music`。Android 文件选择器中通常显示为“内部存储 > Download > netease > cloudmusic > Music”。
3. 点击“输出位置”，选择希望椒盐音乐扫描的目录。
4. 点击“检测歌曲”，确认格式、数量和选择状态。
5. 点击“开始转换”。完成后在椒盐音乐中重新扫描输出目录。

## 构建

要求 JDK 17 或更高版本、Android SDK 36。项目自带 Gradle Wrapper：

```powershell
.\gradlew.bat :app:assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

真实样本不会存入仓库。可用外部文件执行回归测试：

```powershell
.\gradlew.bat "-Dncm.sample=C:\path\song.ncm" :app:testDebugUnitTest
```

Windows 下若项目位于中文路径，APK 可以正常编译；Gradle/JUnit 的测试 worker 可能受非 ASCII classpath 缺陷影响，此时从一个纯英文目录联接运行测试即可。

## 数据与安全

- 所有处理均在设备本地完成。
- 原始歌曲以只读方式打开，输出写入用户指定目录。
- 转换采用临时文件保证标签写入完整；任务结束后清理缓存。
- 仅用于处理你有权访问和转换的个人文件。
