# 网易云歌曲转换

一个面向本地音乐文件的高性能 Python 工具。支持批量解析和转换 NCM 容器、检测多种音频格式、整理目录结构，并自动复制同名歌词与封面文件。

> 本仓库不包含任何歌曲、歌词或封面。请仅处理自己拥有或已获得合法授权的文件。

## 功能特点

- 数字菜单：单曲转换、批量转换、单曲检测、批量检测。
- NumPy 向量化解密，默认 4 线程并发，可手动调整线程数。
- 流式分块处理，避免一次性把整首歌曲读入内存。
- 自动识别 NCM 内部真实格式，保持原始音频质量，不进行有损转码。
- 从 NCM 提取歌曲名、歌手、专辑和内嵌封面，并写入 MP3/FLAC 标准标签。
- 普通音频文件原样复制，保留输入目录结构。
- 自动复制同名歌词、字幕、CUE、文本及常见封面图片。
- 原子写入、输出路径冲突保护、单文件失败隔离。
- 输出实时进度、格式、耗时、吞吐量和分类汇总。
- 自动排除位于输入目录内部的输出目录，避免重复处理。

## 支持检测的格式

NCM、FLAC、MP3、AAC、M4A、ALAC、OGG、Vorbis、Opus、Speex、WAV、WMA、AIFF、APE、WavPack、DSF、DFF、MIDI、AMR、AC3、DTS、MKA、CAF、AU、TAK、TTA、Musepack、RealMedia、VOC。

其中 NCM 会执行解密；其他已识别音频会保真复制到目标目录。

## 运行环境

- Python 3.10 或更高版本
- NumPy
- cryptography
- mutagen
- Windows、Linux 或 macOS
- 使用图形化文件选择窗口时，需要可用的 Tkinter

## 安装

克隆仓库：

```bash
git clone https://github.com/FanhuaAwA/netease-cloud-music-converter.git
cd netease-cloud-music-converter
```

建议创建虚拟环境：

```bash
python -m venv .venv
```

Windows 激活虚拟环境：

```powershell
.\.venv\Scripts\Activate.ps1
```

Linux/macOS 激活虚拟环境：

```bash
source .venv/bin/activate
```

安装依赖：

```bash
python -m pip install -r requirements.txt
```

## 使用方法

### 数字菜单

不带参数启动：

```bash
python convert_ncm.py
```

菜单内容：

```text
1. 单个歌曲解密/整理
2. 歌曲目录批量解密/整理
3. 单个歌曲格式检测
4. 歌曲目录批量格式检测
0. 退出
```

选择功能后，通过系统窗口选择歌曲、歌曲目录和输出目录。

### 命令行批量转换

```bash
python convert_ncm.py "D:\Music" -o "D:\Converted"
```

指定 8 个工作线程：

```bash
python convert_ncm.py "D:\Music" -o "D:\Converted" -j 8
```

只检测格式，不写入文件：

```bash
python convert_ncm.py "D:\Music" --check
```

覆盖已有输出：

```bash
python convert_ncm.py "D:\Music" -o "D:\Converted" --overwrite
```

查看所有参数：

```bash
python convert_ncm.py --help
```

## 音质说明

工具只恢复 NCM 内部原始音频：内部是 FLAC 就输出 FLAC，内部是 MP3 就输出 MP3。将 MP3 改为 FLAC 或 WAV 不能恢复有损压缩已经删除的信息，因此本项目不会执行这种无效转码。

## 手机播放器与椒盐音乐

转换 NCM 时，程序会把元数据直接写进音频文件：

- MP3：ID3v2.3 `TIT2`（歌曲名）、`TPE1`（歌手）、`TALB`（专辑）和 `APIC`（封面）。
- FLAC：Vorbis Comment `TITLE`、`ARTIST`、`ALBUM` 和 FLAC `PICTURE` 封面块。

将转换后的 MP3/FLAC 文件传到手机后，在椒盐音乐中重新扫描歌曲目录即可。内嵌标签随音频一起传输，不依赖电脑上的外置封面路径。

如果文件之前已经被椒盐音乐扫描过但仍显示旧信息，请在椒盐音乐中重新扫描媒体库；必要时删除旧条目后再扫描。

## 编译与打包

Python 脚本无需编译，可以直接运行。如果需要生成 Windows 单文件 EXE，可使用 PyInstaller：

```powershell
python -m pip install -U pyinstaller
python -m PyInstaller --onefile --name "网易云歌曲转换" convert_ncm.py
```

生成文件位于：

```text
dist\网易云歌曲转换.exe
```

因为程序使用控制台数字菜单，请勿添加 `--windowed` 参数。

也可以仅检查 Python 语法和生成字节码：

```bash
python -m py_compile convert_ncm.py
python convert_ncm.py --self-test
```

## 性能

程序使用 NumPy 原生向量化异或与歌曲级线程池。默认线程数为 `min(4, CPU 逻辑核心数)`，通常已经适合本地 SSD；机械硬盘建议使用 `-j 1` 或 `-j 2`，高速 SSD 可根据实测提高。

性能会受 CPU、磁盘、文件数量和音频大小影响，README 不承诺固定速度。

## 附属文件规则

输出歌曲时会复制：

- 与歌曲同名的 `.lrc`、`.srt`、`.vtt`、`.cue`、`.txt`。
- 与歌曲同名的常见图片格式。
- 当前歌曲目录中的 `cover`、`folder`、`front`、`album` 封面图片。

## 项目结构

```text
.
├── convert_ncm.py
├── requirements.txt
├── README.md
└── .gitignore
```

## 注意事项

- 转换前建议保留原始文件备份。
- 不要在不受信任的目录中使用 `--overwrite`。
- 请遵守所在地法律、版权规则及相关服务条款。
