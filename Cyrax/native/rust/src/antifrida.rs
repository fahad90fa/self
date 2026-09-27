// [context: Rust, Android ARM64, native Frida/Xposed/Magisk detection via /proc]

use std::fs;

pub fn check_frida() -> bool {
    if scan_maps_for("frida") { return true; }
    if scan_maps_for("gadget") { return true; }
    if scan_maps_for("libfrida") { return true; }
    if check_frida_port() { return true; }
    if check_frida_fds() { return true; }
    false
}

pub fn check_xposed() -> bool {
    scan_maps_for("XposedBridge")
        || scan_maps_for("de.robv.android.xposed")
        || scan_maps_for("xposed")
}

pub fn check_magisk() -> bool {
    scan_maps_for("MagiskInit")
        || path_exists("/sbin/.magisk")
        || path_exists("/dev/.magisk")
        || path_exists("/data/adb/magisk")
}

pub fn scan_loaded_libraries() -> Vec<String> {
    fs::read_to_string("/proc/self/maps")
        .unwrap_or_default()
        .lines()
        .filter(|line| line.contains(".so"))
        .filter_map(|line| line.split_whitespace().last().map(|s| s.to_string()))
        .collect()
}

pub fn is_hooked() -> bool {
    // check our own function preamble for JMP patching (inline hook detection)
    let func_ptr = check_frida as *const () as *const u8;
    unsafe {
        // ARM64: a JMP is typically ADRP or LDR+BR with a non-standard preamble
        // First 4 bytes should be a valid function prologue
        let first_byte = *func_ptr;
        // If first instruction is 0xFF (common hook trampoline start), we're hooked
        first_byte == 0xFF || first_byte == 0xE9 // 0xE9 = x86 JMP, 0xFF = common ARM stub
    }
}

fn scan_maps_for(needle: &str) -> bool {
    fs::read_to_string("/proc/self/maps")
        .map(|content| content.to_lowercase().contains(&needle.to_lowercase()))
        .unwrap_or(false)
}

fn check_frida_port() -> bool {
    use std::net::TcpStream;
    TcpStream::connect("127.0.0.1:27042").is_ok()
        || TcpStream::connect("127.0.0.1:27043").is_ok()
}

fn check_frida_fds() -> bool {
    let fds_dir = "/proc/self/fd";
    if let Ok(entries) = fs::read_dir(fds_dir) {
        for entry in entries.flatten() {
            if let Ok(link) = fs::read_link(entry.path()) {
                let path = link.to_string_lossy().to_lowercase();
                if path.contains("frida") || path.contains("linjector") {
                    return true;
                }
            }
        }
    }
    false
}

fn path_exists(p: &str) -> bool {
    std::path::Path::new(p).exists()
}

pub fn full_check() -> bool {
    check_frida() || check_xposed() || check_magisk() || is_hooked()
}
