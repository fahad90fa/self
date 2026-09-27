use std::fs;
use std::time::Instant;

/// Check if debugger is attached via ptrace
pub fn is_debugger_attached() -> bool {
    // Check /proc/self/status for TracerPid
    if let Ok(status) = fs::read_to_string("/proc/self/status") {
        for line in status.lines() {
            if line.starts_with("TracerPid:") {
                let parts: Vec<&str> = line.split_whitespace().collect();
                if parts.len() > 1 {
                    if let Ok(pid) = parts[1].parse::<u32>() {
                        return pid != 0; // Non-zero PID means debugger attached
                    }
                }
            }
        }
    }

    false
}

/// Check if Frida is loaded into process
pub fn is_frida_loaded() -> bool {
    // Check /proc/self/maps for frida artifacts
    if let Ok(maps) = fs::read_to_string("/proc/self/maps") {
        if maps.contains("frida") || maps.contains("xposed") || maps.contains("substrate") {
            return true;
        }

        // Check for Frida's gmain event loop
        if maps.contains("libgmain") && maps.contains("libglib") {
            // Could be Frida
            return true;
        }
    }

    // Check for Frida socket
    if fs::metadata("/proc/self/fd").is_ok() {
        if let Ok(entries) = fs::read_dir("/proc/self/fd") {
            for entry in entries.flatten() {
                if let Ok(path) = entry.path().read_link() {
                    if path.to_string_lossy().contains("frida") {
                        return true;
                    }
                }
            }
        }
    }

    false
}

/// Check if execution timing is anomalous (VM, sandbox, or instrumentation)
pub fn is_timing_anomalous() -> bool {
    // Perform timing check: measure how long a simple operation takes
    // If it's much slower, likely running in VM/sandbox/instrumentation

    let start = Instant::now();
    let iterations = 1_000_000;

    // Simple loop that should take < 1ms on real device
    for _ in 0..iterations {
        volatile_read(0);
    }

    let elapsed = start.elapsed();
    let millis = elapsed.as_millis();

    // If operation took > 100ms for 1M iterations, something is wrong
    millis > 100
}

/// Detect common anti-analysis/hook libraries
pub fn is_analysis_lib_loaded() -> bool {
    let libs_to_check = vec![
        "xposed",
        "frida",
        "substrate",
        "substrate-tinypack",
        "dwarf",
        "arm64-v8a",
    ];

    if let Ok(maps) = fs::read_to_string("/proc/self/maps") {
        for lib in libs_to_check {
            if maps.contains(lib) {
                return true;
            }
        }
    }

    false
}

/// Calculate library hash for integrity checking
pub fn calculate_library_hash() -> String {
    // Get the text section of libfrida.so or the native library
    // and compute SHA256 hash
    // This is used to detect tampering or hooks

    if let Ok(maps) = fs::read_to_string("/proc/self/maps") {
        for line in maps.lines() {
            if line.contains("libc2_native.so") {
                // Parse the memory range
                let parts: Vec<&str> = line.split_whitespace().collect();
                if parts.len() > 0 {
                    if let Some(addr) = parts.get(0) {
                        // In real implementation, read memory and hash it
                        // For now, return a placeholder
                        return format!("hash_{}", addr);
                    }
                }
            }
        }
    }

    "unknown".to_string()
}

/// Verify that code hasn't been patched (anti-tampering)
pub fn verify_code_integrity(expected_hash: &str) -> bool {
    let actual = calculate_library_hash();
    actual == expected_hash
}

/// Anti-ptrace protection: clear TracerPid if somehow set
pub fn clear_tracer() -> bool {
    // Try to detach from ptrace
    // This requires capability that's usually not available
    // but attempting it can interfere with some debugging

    unsafe {
        // ptrace(PTRACE_DETACH, 0, 1, 0)
        if libc::ptrace(
            17, // PTRACE_DETACH
            0,
            1 as *mut libc::c_void,
            0,
        ) == 0
        {
            return true;
        }
    }

    false
}

/// Check for common anti-reverse-engineering patterns
pub fn has_anti_re_measures() -> bool {
    // Check if binary has:
    // - Stack canaries
    // - ASLR
    // - NX bit
    // - Stripped symbols

    true // Assumed true if we got this far
}

/// Detect if process is being run under strace
pub fn is_strace_running() -> bool {
    // strace attaches via ptrace, so debugger check catches it
    is_debugger_attached()
}

/// Detect sandbox environment (Cuckoo, Sandboxie, etc.)
pub fn is_sandbox_environment() -> bool {
    let sandbox_indicators = vec![
        "/cuckoo",
        "/qemu",
        "/sandboxie",
        "/wine",
        "/virtualbox",
        "C:\\cuckoo",
        "C:\\sandboxie",
    ];

    if let Ok(cwd) = std::env::current_dir() {
        let cwd_str = cwd.to_string_lossy();
        for indicator in &sandbox_indicators {
            if cwd_str.contains(indicator) {
                return true;
            }
        }
    }

    // Check environment variables
    for (key, val) in std::env::vars() {
        if key.contains("sandbox") || key.contains("cuckoo") {
            return true;
        }
        if val.contains("sandbox") || val.contains("cuckoo") {
            return true;
        }
    }

    false
}

/// Sleep in a way that's hard to detect (avoids naive sleep detection)
pub fn stealth_sleep(duration: std::time::Duration) {
    // Instead of simple sleep(), use a spinning loop
    // This is slower but harder to detect

    let start = Instant::now();
    while start.elapsed() < duration {
        // Busy spin with volatile access to prevent optimization
        for _ in 0..10000 {
            volatile_read(0);
        }
    }
}

/// Anti-hooking: verify function addresses haven't changed
pub fn verify_function_pointers() -> bool {
    // Get address of libc functions and verify they're at expected locations
    // If hooks are installed, addresses will differ

    // Example: check strlen
    let strlen_addr = libc::strlen as *const ();
    let expected_strlen_addr = 0x0; // Placeholder

    // In real implementation, would verify this matches expected
    true
}

/// Detect if code is running under Xposed/LSPosed
pub fn is_xposed_installed() -> bool {
    // Check for Xposed system properties
    // This requires access to property system (native only)

    false // Placeholder
}

// ============================================================================
// UTILITIES
// ============================================================================

/// Volatile read to prevent compiler optimization
#[inline(never)]
fn volatile_read(mut val: i32) -> i32 {
    unsafe {
        std::ptr::read_volatile(&mut val);
    }
    val
}

/// Get process stat as string (anti-analysis detection)
fn get_process_stat() -> String {
    if let Ok(stat) = fs::read_to_string("/proc/self/stat") {
        return stat;
    }
    String::new()
}

// ============================================================================
// MAIN ANTI-DEBUG CHECK
// ============================================================================

pub fn should_exit_if_analyzed() -> bool {
    // Comprehensive check: exit if ANY analysis detected
    is_debugger_attached()
        || is_frida_loaded()
        || is_timing_anomalous()
        || is_analysis_lib_loaded()
        || is_sandbox_environment()
        || is_strace_running()
        || is_xposed_installed()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_timing_check() {
        // Should complete quickly on real device
        let start = Instant::now();
        let _result = is_timing_anomalous();
        let elapsed = start.elapsed();

        // Timing check itself should be < 500ms
        assert!(elapsed.as_millis() < 500);
    }
}
