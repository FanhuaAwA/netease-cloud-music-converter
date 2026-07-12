#!/usr/bin/env python3
import argparse
import base64
import json
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
    from mutagen.flac import FLAC, Picture
    from mutagen.id3 import APIC, ID3, ID3NoHeaderError, TALB, TIT2, TPE1
except ImportError as error:
    raise SystemExit(f"缺少运行库 {error.name}，请执行：python -m pip install numpy cryptography mutagen")


CORE_KEY = bytes.fromhex("687a4852416d736f356b496e62617857")
META_KEY = bytes.fromhex("2331346c6a6b5f215c5d2630553c2728")
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
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif"}
COVER_NAMES = {"cover", "folder", "front", "album"}
LOSSLESS_FORMATS = {"FLAC", "OGG-FLAC", "ALAC", "WAV", "AIFF", "APE", "WAVPACK", "DSF", "DFF", "TAK", "TTA"}
ACTIONS = {"1": (False, False), "2": (True, False), "3": (False, True), "4": (True, True), "0": None}
CHUNK_SIZE = 2 * 1024 * 1024
DEFAULT_WORKERS = min(4, os.cpu_count() or 1)
MAX_KEY_SIZE = 1024 * 1024
MAX_METADATA_SIZE = 16 * 1024 * 1024
MAX_COVER_SIZE = 64 * 1024 * 1024


class NcmError(Exception):
    pass


def read_exact(file, size):
    data = file.read(size)
    if len(data) != size:
        raise NcmError("NCM 文件已截断")
    return data


def decrypt_aes_ecb(data, key):
    if not data or len(data) % 16:
        raise NcmError("AES 密文长度无效")
    decryptor = Cipher(algorithms.AES(key), modes.ECB()).decryptor()
    plain = decryptor.update(data) + decryptor.finalize()
    if not plain:
        raise NcmError("AES 解密结果为空")
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


def decode_metadata(data):
    if not data:
        return {}
    try:
        decoded = np.bitwise_xor(np.frombuffer(data, dtype=np.uint8), 0x63).tobytes()
        prefix = b"163 key(Don't modify):"
        if not decoded.startswith(prefix):
            return {}
        plain = decrypt_aes_ecb(base64.b64decode(decoded[len(prefix):]), META_KEY)
        if not plain.startswith(b"music:"):
            return {}
        metadata = json.loads(plain[6:].decode("utf-8"))
        return metadata if isinstance(metadata, dict) else {}
    except (NcmError, ValueError, UnicodeDecodeError, json.JSONDecodeError):
        return {}


def metadata_artists(metadata):
    raw_artists = metadata.get("artist", [])
    if isinstance(raw_artists, str):
        return raw_artists.strip()
    if not isinstance(raw_artists, list):
        return ""
    artists = []
    for artist in raw_artists:
        if isinstance(artist, (list, tuple)) and artist and artist[0] is not None:
            artists.append(str(artist[0]))
        elif isinstance(artist, dict) and artist.get("name"):
            artists.append(str(artist["name"]))
        elif isinstance(artist, str):
            artists.append(artist)
    return "; ".join(artists)


def image_mime(data):
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data.startswith(b"RIFF") and data[8:12] == b"WEBP":
        return "image/webp"
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    return None


def write_tags(path, audio_format, metadata, cover):
    if not metadata and not cover:
        return False
    title = metadata.get("musicName", "")
    album = metadata.get("album", "")
    title = title.strip() if isinstance(title, str) else ""
    album = album.strip() if isinstance(album, str) else ""
    artists = metadata_artists(metadata)
    cover_mime = image_mime(cover)
    if not title and not album and not artists and not cover_mime:
        return False

    if audio_format == "MP3":
        try:
            tags = ID3(path)
        except ID3NoHeaderError:
            tags = ID3()
        if title:
            tags.setall("TIT2", [TIT2(encoding=3, text=[title])])
        if artists:
            tags.setall("TPE1", [TPE1(encoding=3, text=[artists])])
        if album:
            tags.setall("TALB", [TALB(encoding=3, text=[album])])
        if cover_mime:
            tags.delall("APIC")
            tags.add(APIC(encoding=3, mime=cover_mime, type=3, desc="Cover", data=cover))
        tags.save(path, v2_version=3)
        verified = ID3(path)
        if ((title and str(verified.get("TIT2", "")) != title)
                or (artists and str(verified.get("TPE1", "")) != artists)
                or (album and str(verified.get("TALB", "")) != album)
                or (cover_mime and (not verified.getall("APIC") or verified.getall("APIC")[0].data != cover))):
            raise NcmError("MP3 标签写入校验失败")
        return True

    if audio_format == "FLAC":
        tags = FLAC(path)
        if title:
            tags["TITLE"] = title
        if artists:
            tags["ARTIST"] = artists
        if album:
            tags["ALBUM"] = album
        if cover_mime:
            picture = Picture()
            picture.type = 3
            picture.mime = cover_mime
            picture.desc = "Cover"
            picture.data = cover
            tags.clear_pictures()
            tags.add_picture(picture)
        tags.save()
        verified = FLAC(path)
        if ((title and verified.get("TITLE") != [title])
                or (artists and verified.get("ARTIST") != [artists])
                or (album and verified.get("ALBUM") != [album])
                or (cover_mime and (not verified.pictures or verified.pictures[0].data != cover))):
            raise NcmError("FLAC 标签写入校验失败")
        return True

    return False


def parse_ncm(path, with_tags=True):
    size = path.stat().st_size
    with path.open("rb") as file:
        if read_exact(file, 8) != b"CTENFDAM":
            raise NcmError("不是受支持的标准 NCM 文件")
        read_exact(file, 2)
        key_length = struct.unpack("<I", read_exact(file, 4))[0]
        if not key_length or key_length > MAX_KEY_SIZE or key_length % 16 or file.tell() + key_length > size:
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
        if with_tags and metadata_length > MAX_METADATA_SIZE:
            raise NcmError("NCM 元数据过大")
        metadata = decode_metadata(read_exact(file, metadata_length)) if with_tags else {}
        if not with_tags:
            file.seek(metadata_length, 1)
        read_exact(file, 9)
        image_length = struct.unpack("<I", read_exact(file, 4))[0]
        if file.tell() + image_length >= size:
            raise NcmError("NCM 封面区或音频区无效")
        if with_tags and image_length > MAX_COVER_SIZE:
            raise NcmError("NCM 封面过大")
        cover = read_exact(file, image_length) if with_tags else b""
        if not with_tags:
            file.seek(image_length, 1)
        audio_offset = file.tell()
        encrypted_head = file.read(64 * 1024)

    head = xor_audio(encrypted_head, stream)
    audio_format, extension = detect_format(head)
    return audio_format, extension, audio_offset, stream, metadata, cover


def inspect_audio(path):
    if path.suffix.lower() == ".ncm":
        return parse_ncm(path, with_tags=False)[0]
    with path.open("rb") as file:
        return detect_format(file.read(64 * 1024))[0]


def related_files(song):
    found = []
    for path in song.parent.iterdir():
        if not path.is_file() or path.suffix.lower() not in SIDECAR_EXTENSIONS:
            continue
        same_song = path.stem.casefold() == song.stem.casefold()
        folder_cover = path.suffix.lower() in IMAGE_EXTENSIONS and path.stem.casefold() in COVER_NAMES
        if same_song or folder_cover:
            found.append(path)
    return found


def copy_sidecars(source, target_dir, overwrite, reserved, lock):
    copied = 0
    failed = 0
    try:
        sidecars = related_files(source)
    except OSError:
        return 0, 1
    for sidecar in sidecars:
        destination = target_dir / sidecar.name
        if sidecar.resolve() == destination.resolve():
            continue
        if not reserve_output(destination, reserved, lock, duplicate_error=False):
            continue
        try:
            if overwrite or not destination.exists():
                shutil.copy2(sidecar, destination)
                copied += 1
        except OSError:
            failed += 1
            with lock:
                reserved.discard(str(destination.resolve()).casefold())
    return copied, failed


def reserve_output(output, reserved, lock, duplicate_error=True):
    key = str(output.resolve()).casefold()
    with lock:
        if key in reserved:
            if duplicate_error:
                raise NcmError(f"同一批次存在重复输出路径：{output}")
            return False
        reserved.add(key)
    return True


def commit_temp(temp, output, overwrite):
    if output.exists() and not overwrite:
        raise NcmError(f"输出文件在处理期间出现：{output}")
    temp.replace(output)


def convert_ncm(source, target_dir, overwrite, reserved, lock):
    audio_format, extension, audio_offset, stream, metadata, cover = parse_ncm(source)
    target_dir.mkdir(parents=True, exist_ok=True)
    output = target_dir / (source.stem + extension)
    reserve_output(output, reserved, lock)
    if output.exists() and not overwrite:
        copied, sidecar_failures = copy_sidecars(source, target_dir, overwrite, reserved, lock)
        return "跳过", audio_format, output, copied, sidecar_failures

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
        tagged = write_tags(temp, audio_format, metadata, cover)
        commit_temp(temp, output, overwrite)
    finally:
        temp.unlink(missing_ok=True)

    status = "解密+标签" if tagged else "解密"
    copied, sidecar_failures = copy_sidecars(source, target_dir, overwrite, reserved, lock)
    return status, audio_format, output, copied, sidecar_failures


def copy_audio(source, target_dir, overwrite, reserved, lock):
    audio_format = inspect_audio(source)
    target_dir.mkdir(parents=True, exist_ok=True)
    output = target_dir / source.name
    if source.resolve() == output.resolve():
        return "原位", audio_format, output, 0, 0
    reserve_output(output, reserved, lock)
    if output.exists() and not overwrite:
        copied, sidecar_failures = copy_sidecars(source, target_dir, overwrite, reserved, lock)
        return "跳过", audio_format, output, copied, sidecar_failures

    temp = output.with_name(f"{output.name}.{os.getpid()}.{threading.get_ident()}.tmp")
    try:
        shutil.copy2(source, temp)
        if temp.stat().st_size != source.stat().st_size:
            raise NcmError("输出文件大小校验失败")
        commit_temp(temp, output, overwrite)
    finally:
        temp.unlink(missing_ok=True)
    copied, sidecar_failures = copy_sidecars(source, target_dir, overwrite, reserved, lock)
    return "复制", audio_format, output, copied, sidecar_failures


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


def dialog_root():
    from tkinter import TclError, Tk
    root = None
    try:
        root = Tk()
        root.withdraw()
        root.update()
        return root
    except TclError as error:
        if root is not None:
            root.destroy()
        raise NcmError("无法打开文件选择窗口，请改用命令行传入路径") from error


def choose_input(directory):
    from tkinter import filedialog
    root = dialog_root()
    try:
        if directory:
            value = filedialog.askdirectory(title="选择歌曲目录")
        else:
            patterns = " ".join(f"*{extension}" for extension in sorted(AUDIO_EXTENSIONS))
            value = filedialog.askopenfilename(title="选择歌曲", filetypes=[("音乐文件", patterns), ("所有文件", "*.*")])
    finally:
        root.destroy()
    return Path(value) if value else None


def choose_output(initial_dir):
    from tkinter import filedialog
    root = dialog_root()
    try:
        value = filedialog.askdirectory(title="选择输出目录", initialdir=str(initial_dir))
    finally:
        root.destroy()
    return Path(value) if value else None


def process_song(song, source, output, check_only, overwrite, reserved, lock):
    started = time.perf_counter()
    if check_only:
        audio_format = inspect_audio(song)
        return "检测", audio_format, None, 0, 0, song.stat().st_size, time.perf_counter() - started

    relative_parent = song.parent.relative_to(source) if source.is_dir() else Path()
    target_dir = output / relative_parent
    if song.suffix.lower() == ".ncm":
        status, audio_format, destination, sidecars, sidecar_failures = convert_ncm(song, target_dir, overwrite, reserved, lock)
    else:
        status, audio_format, destination, sidecars, sidecar_failures = copy_audio(song, target_dir, overwrite, reserved, lock)
    processed_size = song.stat().st_size if status.startswith("解密") or status == "复制" else 0
    return status, audio_format, destination, sidecars, sidecar_failures, processed_size, time.perf_counter() - started


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
    sidecar_failures = 0
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
                status, audio_format, destination, sidecars, sidecar_errors, size, elapsed = future.result()
                counts[audio_format] += 1
                statuses[status] += 1
                copied += sidecars
                sidecar_failures += sidecar_errors
                total_bytes += size
                quality = "FLAC/无损" if audio_format == "FLAC" else ("无损" if audio_format in LOSSLESS_FORMATS else "非 FLAC")
                target = f" -> {destination}" if destination else ""
                warning = f" [附属文件失败 {sidecar_errors}]" if sidecar_errors else ""
                print(f"[{completed}/{len(files)}] [{audio_format}] [{quality}] [{status}] {song.name}{target} ({elapsed:.2f}s){warning}")
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
        summary += f"，{operations}，平均吞吐 {speed:.1f} MiB/s，复制歌词/封面 {copied} 个，附属文件失败 {sidecar_failures} 个"
    print("\n" + summary)
    return 1 if failed else 0


def positive_int(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError("线程数必须大于 0")
    return number


def main():
    parser = argparse.ArgumentParser(description="批量检测、整理音乐文件；自动解密 NCM，并复制同名歌词和封面")
    parser.add_argument("input", nargs="?", help="单个歌曲文件或歌曲目录；省略时显示数字菜单")
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
        assert metadata_artists({"artist": [["A", 1], ["B", 2]]}) == "A; B"
        assert metadata_artists({"artist": "Solo"}) == "Solo"
        assert image_mime(b"\x89PNG\r\n\x1a\n") == "image/png"
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
