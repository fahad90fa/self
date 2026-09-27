// [context: Rust, Linux/x64, encrypted module cache with LRU]

use aes_gcm::{Aes256Gcm, Key, Nonce, aead::{Aead, KeyInit}};
use rand_core::{OsRng, RngCore};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use thiserror::Error;

#[derive(Error, Debug)]
pub enum StorageError {
    #[error("not found")]
    NotFound,
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("crypto error")]
    Crypto,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModuleInfo {
    pub id: String,
    pub module_type: String,
    pub size: usize,
    pub version: String,
    pub hash: String,
}

struct CacheEntry {
    data: Vec<u8>,
    last_accessed: std::time::Instant,
}

pub struct ModuleStorage {
    cache: Arc<Mutex<HashMap<String, CacheEntry>>>,
    disk_path: PathBuf,
    storage_key: [u8; 32],
    max_cache_entries: usize,
}

impl ModuleStorage {
    pub fn new(disk_path: PathBuf, storage_key: [u8; 32]) -> Self {
        std::fs::create_dir_all(&disk_path).ok();
        Self {
            cache: Arc::new(Mutex::new(HashMap::new())),
            disk_path,
            storage_key,
            max_cache_entries: 50,
        }
    }

    pub fn store_module(&self, id: &str, encrypted_bytes: Vec<u8>) -> Result<(), StorageError> {
        let on_disk = self.encrypt_for_disk(&encrypted_bytes)?;
        let path = self.disk_path.join(format!("{}.bin", id));
        std::fs::write(&path, on_disk)?;

        let mut cache = self.cache.lock().unwrap();
        if cache.len() >= self.max_cache_entries {
            // evict oldest
            if let Some(oldest_key) = cache.iter()
                .min_by_key(|(_, v)| v.last_accessed)
                .map(|(k, _)| k.clone())
            {
                cache.remove(&oldest_key);
            }
        }
        cache.insert(id.to_string(), CacheEntry {
            data: encrypted_bytes,
            last_accessed: std::time::Instant::now(),
        });
        Ok(())
    }

    pub fn get_module(&self, id: &str) -> Option<Vec<u8>> {
        {
            let mut cache = self.cache.lock().unwrap();
            if let Some(entry) = cache.get_mut(id) {
                entry.last_accessed = std::time::Instant::now();
                return Some(entry.data.clone());
            }
        }
        // try disk
        let path = self.disk_path.join(format!("{}.bin", id));
        let on_disk = std::fs::read(&path).ok()?;
        let decrypted = self.decrypt_from_disk(&on_disk).ok()?;
        self.store_module(id, decrypted.clone()).ok();
        Some(decrypted)
    }

    pub fn list_modules(&self) -> Vec<ModuleInfo> {
        std::fs::read_dir(&self.disk_path)
            .map(|entries| {
                entries
                    .filter_map(|e| e.ok())
                    .filter(|e| e.path().extension().map(|x| x == "bin").unwrap_or(false))
                    .map(|e| {
                        let id = e.path().file_stem().unwrap().to_string_lossy().into_owned();
                        let size = e.metadata().map(|m| m.len() as usize).unwrap_or(0);
                        ModuleInfo { id, module_type: "unknown".into(), size, version: "1.0".into(), hash: String::new() }
                    })
                    .collect()
            })
            .unwrap_or_default()
    }

    pub fn invalidate(&self, id: &str) {
        self.cache.lock().unwrap().remove(id);
        let path = self.disk_path.join(format!("{}.bin", id));
        std::fs::remove_file(path).ok();
    }

    fn encrypt_for_disk(&self, data: &[u8]) -> Result<Vec<u8>, StorageError> {
        let key = Key::<Aes256Gcm>::from_slice(&self.storage_key);
        let cipher = Aes256Gcm::new(key);
        let mut nonce_bytes = [0u8; 12];
        OsRng.fill_bytes(&mut nonce_bytes);
        let nonce = Nonce::from_slice(&nonce_bytes);
        let mut ct = cipher.encrypt(nonce, data).map_err(|_| StorageError::Crypto)?;
        let mut out = nonce_bytes.to_vec();
        out.append(&mut ct);
        Ok(out)
    }

    fn decrypt_from_disk(&self, data: &[u8]) -> Result<Vec<u8>, StorageError> {
        if data.len() < 13 { return Err(StorageError::Crypto); }
        let key = Key::<Aes256Gcm>::from_slice(&self.storage_key);
        let cipher = Aes256Gcm::new(key);
        let nonce = Nonce::from_slice(&data[..12]);
        cipher.decrypt(nonce, &data[12..]).map_err(|_| StorageError::Crypto)
    }
}
