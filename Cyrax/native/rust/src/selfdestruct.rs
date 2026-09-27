// [context: Rust, Android ARM64 JNI, native wipe routine]

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use std::fs;
use std::io::Write;
use std::path::Path;

pub fn zero_fill_file(path: &str) -> bool {
    let Ok(metadata) = fs::metadata(path) else { return false; };
    let size = metadata.len() as usize;
    let Ok(mut file) = fs::OpenOptions::new().write(true).open(path) else { return false; };
    let zeros = vec![0u8; size.min(1024 * 1024)];
    let mut remaining = size;
    while remaining > 0 {
        let chunk = zeros.len().min(remaining);
        let _ = file.write_all(&zeros[..chunk]);
        remaining -= chunk;
    }
    let _ = file.flush();
    let _ = file.sync_all();
    drop(file);
    fs::remove_file(path).is_ok()
}

pub fn wipe_directory(path: &str) {
    let dir = Path::new(path);
    if !dir.exists() { return; }
    if let Ok(entries) = fs::read_dir(dir) {
        for entry in entries.flatten() {
            let p = entry.path();
            if p.is_dir() {
                wipe_directory(p.to_str().unwrap_or_default());
                let _ = fs::remove_dir(&p);
            } else {
                zero_fill_file(p.to_str().unwrap_or_default());
            }
        }
    }
    let _ = fs::remove_dir(dir);
}

pub fn clear_dex_caches(pkg_name: &str) {
    let paths = [
        format!("/data/data/{}/code_cache", pkg_name),
        format!("/data/data/{}/dalvik-cache", pkg_name),
        format!("/data/dalvik-cache"),
    ];
    for p in &paths {
        wipe_directory(p);
    }
}

pub fn wipe_app_data(pkg_name: &str) {
    let paths = [
        format!("/data/data/{}/shared_prefs", pkg_name),
        format!("/data/data/{}/databases", pkg_name),
        format!("/data/data/{}/files", pkg_name),
        format!("/data/data/{}/cache", pkg_name),
        format!("/data/data/{}/app_webview", pkg_name),
        format!("/data/data/{}/no_backup", pkg_name),
    ];
    for p in &paths {
        wipe_directory(p);
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_system_service_jni_NativeBridge_nativeWipe(
    mut env: JNIEnv,
    _class: JClass,
    pkg_name: JString,
) {
    let pkg: String = env.get_string(&pkg_name)
        .map(|s| s.into())
        .unwrap_or_default();

    wipe_app_data(&pkg);
    clear_dex_caches(&pkg);
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_system_service_jni_NativeBridge_zeroFillFile(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jni::sys::jboolean {
    let p: String = env.get_string(&path)
        .map(|s| s.into())
        .unwrap_or_default();
    if zero_fill_file(&p) { 1 } else { 0 }
}
