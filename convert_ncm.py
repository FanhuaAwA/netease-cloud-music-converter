#!/usr/bin/env python3
import argparse
import os
import shutil
import struct
import sys
import threading
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

try:
    import numpy as np
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
except ImportError as error:
    raise SystemExit(f"缺少运行库 {error.name}，请执行：python -m pip install numpy cryptography")


CORE_KEY = bytes.fromhex("687a4852416d736f356b496e62617857")
AUDIO_EXTENSIONS = {
    ".ncm", ".flac", ".mp3", ".aac", ".m4a", ".alac",
    ".ogg", ".oga", ".opus", ".wav", ".wma", ".aif", ".aiff",
    ".ape", ".wv", ".dsf", ".dff", ".mid", ".midi", ".amr",
    ".ac3", ".dts", ".mka", ".caf", ".au", ".tak", ".tta",
    ".mpc", ".spx", ".ra", ".rm", ".voc",
}
SIDECAR_EXTENSIONS = {
    ".lrc", ".srt", ".vtt", ".cue", ".txt",
    ".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif",
}
COVER_NAMES = {"cover", "folder", "front", "album"}
LOSSLESS_FORMATS = {"FLAC", "OGG-FLAC", "ALAC", "WAV", "AIFF", "APE", "WAVPACK", "DSF", "DFF", "TAK", "TTA"}
ACTIONS = {"1": (False, False), "2": (True, False), "3": (False, True), "4": (True, True), "0": None}
CHUNK_SIZE = 2 * 1024 * 1024
DEFAULT_WORKERS = min(4, os.cpu_count() or 1)


class NcmError(Exception):
    pass


def read_exact(file, size):
    data = file.read(size)
    if len(data) != size:
        raise NcmError("NCM 文件已截断")
    return data


def decrypt_aes_ecb(data, key):
    decryptor = Cipher(algorithms.AES(key), modes.ECB()).decryptor()
    plain = decryptor.update(data) + decryptor.finalize()
    padding = plain[-1]
    if not 1 <= padding <= 16 or plain[-padding:] != bytes([padding]) * padding:
        raise NcmError("NCM 音频密钥解密失败")
    return plain[:-padding]


def detect_format(head):
    if head.startswith(b"fLaC"):
        return "FLAC", ".flac"
    if head.startswith(b"ID3"):
        return "MP3", ".mp3"
    if head.startswith(b"OggS"):
        if b"OpusHead" in head:
            return "OPUS", ".opus"
        if b"Speex   " in head:
            return "SPEEX", ".spx"
        if b"\x01vorbis" in head:
            return "VORBIS", ".ogg"
        if b"fLaC" in head:
            return "OGG-FLAC", ".oga"
        return "OGG", ".ogg"
    if head.startswith((b"RIFF", b"RF64")):
        return "WAV", ".wav"
    if len(head) >= 8 and head[4:8] == b"ftyp":
        if b"alac" in head:
            return "ALAC", ".m4a"
        return "M4A", ".m4a"
    if head.startswith(bytes.fromhex("3026B2758E66CF11A6D900AA0062CE6C")):
        return "WMA", ".wma"
    if head.startswith(b"FORM") and head[8:12] in {b"AIFF", b"AIFC"}:
        return "AIFF", ".aiff"
    if head.startswith(b"MAC "):
        return "APE", ".ape"
    if head.startswith(b"wvpk"):
        return "WAVPACK", ".wv"
    if head.startswith(b"DSD "):
        return "DSF", ".dsf"
    if head.startswith(b"FRM8"):
        return "DFF", ".dff"
    if head.startswith(b"MThd"):
        return "MIDI", ".mid"
    if head.startswith((b"#!AMR\n", b"#!AMR-WB\n")):
        return "AMR", ".amr"
    if head.startswith(b"\x0b\x77"):
        return "AC3", ".ac3"
    if head.startswith((b"\x7f\xfe\x80\x01", b"\xfe\x7f\x01\x80", b"\x1f\xff\xe8\x00", b"\xff\x1f\x00\xe8")):
        return "DTS", ".dts"
    if head.startswith(b"\x1a\x45\xdf\xa3"):
        return "MKA", ".mka"
    if head.startswith(b"caff"):
        if b"alac" in head:
            return "ALAC", ".caf"
        return "CAF", ".caf"
    if head.startswith(b".snd"):
        return "AU", ".au"
    if head.startswith(b"tBaK"):
        return "TAK", ".tak"
    if head.startswith(b"TTA1"):
        return "TTA", ".tta"
    if head.startswith((b"MPCK", b"MP+")):
        return "MUSEPACK", ".mpc"
    if head.startswith(b".RMF"):
        return "REALMEDIA", ".ra"
    if head.startswith(b"Creative Voice File\x1a"):
        return "VOC", ".voc"
    if len(head) >= 2 and head[0] == 0xFF and head[1] & 0xF6 == 0xF0:
        return "AAC", ".aac"
    if (len(head) >= 2 and head[0] == 0xFF and head[1] & 0xE0 == 0xE0
            and head[1] & 0x18 != 0x08 and head[1] & 0x06):
        return "MP3", ".mp3"
    raise NcmError("无法识别音频格式，文件头：" + head[:8].hex(" ").upper())


def make_stream(box):
    stream = bytearray(256)
    for i in range(256):
        n = (i + 1) & 0xFF
        stream[i] = box[(box[n] + box[(box[n] + n) & 0xFF]) & 0xFF]
    return bytes(stream)


def xor_audio(data, stream, position=0):
    if not data:
        return b""
    shift = position & 0xFF
    key = np.frombuffer(stream[shift:] + stream[:shift], dtype=np.uint8)
    return np.bitwise_xor(np.frombuffer(data, dtype=np.uint8), np.resize(key, len(data))).tobytes()


def parse_ncm(path):
    size = path.stat().st_size
    with path.open("rb") as file:
        if read_exact(file, 8) != b"CTENFDAM":
            raise NcmError("不是受支持的标准 NCM 文件")
        read_exact(file, 2)
        key_length = struct.unpack("<I", read_exact(file, 4))[0]
        if not key_length or key_length % 16 or file.tell() + key_length > size:
            raise NcmError("NCM 密钥区无效")
        encrypted_key = bytes(byte ^ 0x64 for byte in read_exact(file, key_length))
        plain_key = decrypt_aes_ecb(encrypted_key, CORE_KEY)
        if len(plain_key) <= 17 or not plain_key.startswith(b"neteasecloudmusic"):
            raise NcmError("NCM 音频密钥无效")
        key = plain_key[17:]

        box = list(range(256))
        j = 0
        for i in range(256):
            j = (box[i] + j + key[i % len(key)]) & 0xFF
            box[i], box[j] = box[j], box[i]
        stream = make_stream(box)

        metadata_length = struct.unpack("<I", read_exact(file, 4))[0]
        if file.tell() + metadata_length + 9 > size:
            raise NcmError("NCM 元数据区无效")
        file.seek(metadata_length + 9, 1)
        image_length = struct.unpack("<I", read_exact(file, 4))[0]
        if file.tell() + image_length >= size:
            raise NcmError("NCM 封面区或音频区无效")
        file.seek(image_length, 1)
        audio_offset = file.tell()
        encrypted_head = file.read(64 * 1024)

    head = xor_audio(encrypted_head, stream)
    audio_format, extension = detect_format(head)
    return audio_format, extension, audio_offset, stream


def inspect_audio(path):
    if path.suffix.lower() == ".ncm":
        return parse_ncm(path)[0]
    with path.open("rb") as file:
        return detect_format(file.read(64 * 1024))[0]


def related_files(song):
    found = []
    for path in song.parent.iterdir():
        if not path.is_file() or path.suffix.lower() not in SIDECAR_EXTENSIONS:
            continue
        same_song = path.stem.casefold() == song.stem.casefold()
        folder_cover = path.suffix.lower() != ".lrc" and path.stem.casefold() in COVER_NAMES
        if same_song or folder_cover:
            found.append(path)
    return found


def copy_sidecars(source, target_dir, overwrite, reserved, lock):
    copied = 0
    for sidecar in related_files(source):
        destination = target_dir / sidecar.name
        if sidecar.resolve() == destination.resolve():
            continue
        if not reserve_output(destination, reserved, lock, duplicate_error=False):
            continue
        if overwrite or not destination.exists():
            shutil.copy2(sidecar, destination)
            copied += 1
    return copied


def reserve_output(output, reserved, lock, duplicate_error=True):
    key = str(output.resolve()).casefold()
    with lock:
        if key in reserved:
            if duplicate_error:
                raise NcmError(f"同一批次存在重复输出路径：{output}")
            return False
        reserved.add(key)
    return True


def convert_ncm(source, target_dir, overwrite, reserved, lock):
    audio_format, extension, audio_offset, stream = parse_ncm(source)
    target_dir.mkdir(parents=True, exist_ok=True)
    output = target_dir / (source.stem + extension)
    reserve_output(output, reserved, lock)
    if output.exists() and not overwrite:
        return "跳过", audio_format, output, 0

    temp = output.with_name(f"{output.name}.{os.getpid()}.{threading.get_ident()}.tmp")
    try:
        with source.open("rb") as src, temp.open("wb") as dst:
            src.seek(audio_offset)
            position = 0
            while chunk := src.read(CHUNK_SIZE):
                dst.write(xor_audio(chunk, stream, position))
                position += len(chunk)
        if temp.stat().st_size != source.stat().st_size - audio_offset:
            raise NcmError("输出文件大小校验失败")
        temp.replace(output)
    finally:
        temp.unlink(missing_ok=True)

    return "解密", audio_format, output, copy_sidecars(source, target_dir, overwrite, reserved, lock)


def copy_audio(source, target_dir, overwrite, reserved, lock):
    audio_format = inspect_audio(source)
    target_dir.mkdir(parents=True, exist_ok=True)
    output = target_dir / source.name
    if source.resolve() == output.resolve():
        return "原位", audio_format, output, 0
    reserve_output(output, reserved, lock)
    if output.exists() and not overwrite:
        return "跳过", audio_format, output, 0

    temp = output.with_name(f"{output.name}.{os.getpid()}.{threading.get_ident()}.tmp")
    try:
        shutil.copy2(source, temp)
        if temp.stat().st_size != source.stat().st_size:
            raise NcmError("输出文件大小校验失败")
        temp.replace(output)
    finally:
        temp.unlink(missing_ok=True)
    return "复制", audio_format, output, copy_sidecars(source, target_dir, overwrite, reserved, lock)


def collect_files(source):
    if source.is_file():
        return [source]
    if not source.is_dir():
        raise FileNotFoundError(f"输入路径不存在：{source}")
    return sorted(
        (path for path in source.rglob("*") if path.is_file() and path.suffix.lower() in AUDIO_EXTENSIONS),
        key=lambda path: str(path).casefold(),
    )


def choose_action():
    print("\n=== 音乐解密与格式检测 ===")
    print("1. 单个歌曲解密/整理")
    print("2. 歌曲目录批量解密/整理")
    print("3. 单个歌曲格式检测")
    print("4. 歌曲目录批量格式检测")
    print("0. 退出")
    while True:
        try:
            choice = input("请选择功能 [0-4]：").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return None
        if choice in ACTIONS:
            return ACTIONS[choice]
        print("输入无效，请输入 0、1、2、3 或 4。")


def choose_input(directory):
    from tkinter import Tk, filedialog
    root = Tk()
    root.withdraw()
    root.update()
    if directory:
        value = filedialog.askdirectory(title="选择歌曲目录")
    else:
        patterns = " ".join(f"*{extension}" for extension in sorted(AUDIO_EXTENSIONS))
        value = filedialog.askopenfilename(title="选择歌曲", filetypes=[("音乐文件", patterns), ("所有文件", "*.*")])
    root.destroy()
    return Path(value) if value else None


def choose_output(initial_dir):
    from tkinter import Tk, filedialog
    root = Tk()
    root.withdraw()
    root.update()
    value = filedialog.askdirectory(title="选择输出目录", initialdir=str(initial_dir))
    root.destroy()
    return Path(value) if value else None


def process_song(song, source, output, check_only, overwrite, reserved, lock):
    started = time.perf_counter()
    if check_only:
        audio_format = inspect_audio(song)
        return "检测", audio_format, None, 0, song.stat().st_size, time.perf_counter() - started

    relative_parent = song.parent.relative_to(source) if source.is_dir() else Path()
    target_dir = output / relative_parent
    if song.suffix.lower() == ".ncm":
        status, audio_format, destination, sidecars = convert_ncm(song, target_dir, overwrite, reserved, lock)
    else:
        status, audio_format, destination, sidecars = copy_audio(song, target_dir, overwrite, reserved, lock)
    processed_size = song.stat().st_size if status in {"解密", "复制"} else 0
    return status, audio_format, destination, sidecars, processed_size, time.perf_counter() - started


def run(source, output, check_only, overwrite, workers=DEFAULT_WORKERS):
    files = collect_files(source)
    if (not check_only and source.is_dir() and output != source
            and output.is_relative_to(source)):
        files = [path for path in files if not path.is_relative_to(output)]
    if not files:
        raise NcmError("没有找到可处理的歌曲文件")

    counts = Counter()
    statuses = Counter()
    failed = 0
    copied = 0
    reserved = set()
    lock = threading.Lock()
    workers = max(1, min(workers, len(files)))
    total_bytes = 0
    started = time.perf_counter()
    print(f"发现 {len(files)} 首歌曲，使用 {workers} 个工作线程，解密后端：NumPy 向量化。")

    with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="music") as pool:
        futures = {
            pool.submit(process_song, song, source, output, check_only, overwrite, reserved, lock): song
            for song in files
        }
        for completed, future in enumerate(as_completed(futures), 1):
            song = futures[future]
            try:
                status, audio_format, destination, sidecars, size, elapsed = future.result()
                counts[audio_format] += 1
                statuses[status] += 1
                copied += sidecars
                total_bytes += size
                quality = "FLAC/无损" if audio_format == "FLAC" else ("无损" if audio_format in LOSSLESS_FORMATS else "非 FLAC")
                target = f" -> {destination}" if destination else ""
                print(f"[{completed}/{len(files)}] [{audio_format}] [{quality}] [{status}] {song.name}{target} ({elapsed:.2f}s)")
            except Exception as error:
                failed += 1
                print(f"[{completed}/{len(files)}] [失败] {song}: {error}", file=sys.stderr)

    total = sum(counts.values())
    elapsed = time.perf_counter() - started
    speed = total_bytes / max(elapsed, 0.001) / 1024 / 1024
    formats = "，".join(f"{name} {count} 首" for name, count in sorted(counts.items()))
    summary = f"处理 {len(files)} 首：识别 {total} 首（{formats or '无可识别格式'}），失败 {failed} 首，耗时 {elapsed:.2f} 秒"
    if not check_only:
        operations = "，".join(f"{name} {count} 首" for name, count in sorted(statuses.items()))
        summary += f"，{operations}，平均吞吐 {speed:.1f} MiB/s，复制歌词/封面 {copied} 个"
    print("\n" + summary)
    return 1 if failed else 0


def positive_int(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError("线程数必须大于 0")
    return number


def main():
    parser = argparse.ArgumentParser(description="批量检测、整理音乐文件；自动解密 NCM，并复制同名歌词和封面")
    parser.add_argument("input", nargs="?", help="单个歌曲文件或歌曲目录；省略时弹窗选择")
    parser.add_argument("-o", "--output", help="输出目录；省略时弹窗选择")
    parser.add_argument("--check", action="store_true", help="只检测格式，不转换文件")
    parser.add_argument("--overwrite", action="store_true", help="覆盖已有输出和附属文件")
    parser.add_argument("-j", "--workers", type=positive_int, default=DEFAULT_WORKERS, help=f"并发线程数，默认 {DEFAULT_WORKERS}")
    parser.add_argument("--self-test", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()

    if args.self_test:
        assert detect_format(b"fLaC\0\0\0\0")[0] == "FLAC"
        assert detect_format(b"ID3\4\0\0\0\0")[0] == "MP3"
        assert detect_format(b"RIFF\0\0\0\0")[0] == "WAV"
        assert detect_format(b"OggS" + bytes(24) + b"OpusHead")[0] == "OPUS"
        assert detect_format(b"MAC \0\0\0\0")[0] == "APE"
        assert detect_format(b"wvpk\0\0\0\0")[0] == "WAVPACK"
        assert ACTIONS["1"] == (False, False) and ACTIONS["4"] == (True, True) and ACTIONS["0"] is None
        data, stream = bytes(range(256)) * 2, bytes(reversed(range(256)))
        expected = bytes(byte ^ stream[(17 + i) & 0xFF] for i, byte in enumerate(data))
        assert xor_audio(data, stream, 17) == expected
        print("SELF_TEST_OK")
        return 0

    if args.input:
        source = Path(args.input).expanduser().resolve()
        check_only = args.check
    else:
        action = choose_action()
        if action is None:
            print("已退出。")
            return 0
        directory, check_only = action
        source = choose_input(directory)
    if not source:
        print("已取消。")
        return 0
    if check_only:
        return run(source, None, True, args.overwrite, args.workers)

    output = Path(args.output).expanduser().resolve() if args.output else choose_output(source.parent if source.is_file() else source)
    if not output:
        print("已取消。")
        return 0
    return run(source, output, False, args.overwrite, args.workers)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, NcmError) as error:
        raise SystemExit(f"错误：{error}")
