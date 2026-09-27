// [context: Rust, Android ARM64 JNI, device fingerprint verification]

use jni::JNIEnv;
use jni::objects::{JClass, JString, JByteArray};
use jni::sys::{jboolean, jbyteArray};
use sha2::{Sha256, Digest};

extern "C" {
    fn __system_property_get(name: *const libc::c_char, value: *mut libc::c_char) -> libc::c_int;
}

pub fn read_system_property(name: &str) -> String {
    let c_name = std::ffi::CString::new(name).unwrap();
    let mut buf = [0i8; 256];
    unsafe {
        __system_property_get(c_name.as_ptr(), buf.as_mut_ptr());
        let bytes: Vec<u8> = buf.iter()
            .take_while(|&&b| b != 0)
            .map(|&b| b as u8)
            .collect();
        String::from_utf8_lossy(&bytes).into_owned()
    }
}

pub fn derive_device_key(parts: &[&str]) -> [u8; 32] {
    let mut hasher = Sha256::new();
    for part in parts {
        hasher.update(part.as_bytes());
        hasher.update(b"|");
    }
    hasher.finalize().into()
}

pub fn verify_fingerprint(expected_hash: &[u8; 32]) -> bool {
    let fingerprint = read_system_property("ro.build.fingerprint");
    let board = read_system_property("ro.product.board");
    let bootloader = read_system_property("ro.bootloader");
    let hardware = read_system_property("ro.hardware");

    let derived = derive_device_key(&[
        &fingerprint,
        &board,
        &bootloader,
        &hardware,
    ]);

    // constant-time comparison
    let mut diff = 0u8;
    for (a, b) in derived.iter().zip(expected_hash.iter()) {
        diff |= a ^ b;
    }
    diff == 0
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_system_service_jni_NativeBridge_deriveKey(
    mut env: JNIEnv,
    _class: JClass,
    seed: JByteArray,
) -> jbyteArray {
    let seed_bytes = match env.convert_byte_array(&seed) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    let fingerprint = read_system_property("ro.build.fingerprint");
    let board = read_system_property("ro.product.board");

    let mut hasher = Sha256::new();
    hasher.update(&seed_bytes);
    hasher.update(fingerprint.as_bytes());
    hasher.update(board.as_bytes());
    let key: [u8; 32] = hasher.finalize().into();

    env.byte_array_from_slice(&key)
        .map(|a| a.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_system_service_jni_NativeBridge_verifyEnvironment(
    mut env: JNIEnv,
    _class: JClass,
    expected: JByteArray,
) -> jboolean {
    let expected_bytes = match env.convert_byte_array(&expected) {
        Ok(b) => b,
        Err(_) => return 0,
    };
    if expected_bytes.len() != 32 { return 0; }
    let mut arr = [0u8; 32];
    arr.copy_from_slice(&expected_bytes);
    if verify_fingerprint(&arr) { 1 } else { 0 }
}
