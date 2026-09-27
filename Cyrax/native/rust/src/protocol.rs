// [context: Rust, Android/server shared, C2 wire protocol implementation]

use aes_gcm::{Aes256Gcm, Key, Nonce, aead::{Aead, KeyInit}};
use hmac::{Hmac, Mac};
use rand_core::{OsRng, RngCore};
use sha2::Sha256;
use thiserror::Error;

type HmacSha256 = Hmac<Sha256>;

#[derive(Error, Debug)]
pub enum ProtocolError {
    #[error("invalid magic")]
    InvalidMagic,
    #[error("hmac mismatch")]
    HmacMismatch,
    #[error("decrypt failed")]
    DecryptFailed,
    #[error("buffer too short")]
    BufferTooShort,
    #[error("serialize error")]
    SerializeError,
}

pub const MAGIC: &[u8; 4] = b"CXPR";

/// Wire format:
/// [4]  magic (CXPR)
/// [1]  version
/// [16] device_id
/// [4]  sequence number (LE)
/// [1]  command_type
/// [4]  payload_len (LE)
/// [N]  encrypted_payload
/// [32] hmac-sha256 over all preceding bytes
#[derive(Debug, Clone)]
pub struct C2Message {
    pub version: u8,
    pub device_id: [u8; 16],
    pub sequence: u32,
    pub command_type: u8,
    pub payload: Vec<u8>,
    pub hmac: [u8; 32],
}

pub fn serialize(msg: &C2Message) -> Vec<u8> {
    let mut buf = Vec::with_capacity(4 + 1 + 16 + 4 + 1 + 4 + msg.payload.len() + 32);
    buf.extend_from_slice(MAGIC);
    buf.push(msg.version);
    buf.extend_from_slice(&msg.device_id);
    buf.extend_from_slice(&msg.sequence.to_le_bytes());
    buf.push(msg.command_type);
    buf.extend_from_slice(&(msg.payload.len() as u32).to_le_bytes());
    buf.extend_from_slice(&msg.payload);
    buf.extend_from_slice(&msg.hmac);
    buf
}

pub fn deserialize(bytes: &[u8]) -> Result<C2Message, ProtocolError> {
    if bytes.len() < 4 + 1 + 16 + 4 + 1 + 4 + 32 {
        return Err(ProtocolError::BufferTooShort);
    }
    if &bytes[..4] != MAGIC {
        return Err(ProtocolError::InvalidMagic);
    }
    let version = bytes[4];
    let mut device_id = [0u8; 16];
    device_id.copy_from_slice(&bytes[5..21]);
    let sequence = u32::from_le_bytes(bytes[21..25].try_into().unwrap());
    let command_type = bytes[25];
    let payload_len = u32::from_le_bytes(bytes[26..30].try_into().unwrap()) as usize;

    if bytes.len() < 30 + payload_len + 32 {
        return Err(ProtocolError::BufferTooShort);
    }
    let payload = bytes[30..30 + payload_len].to_vec();
    let mut hmac = [0u8; 32];
    hmac.copy_from_slice(&bytes[30 + payload_len..30 + payload_len + 32]);

    Ok(C2Message { version, device_id, sequence, command_type, payload, hmac })
}

pub fn sign_message(msg: &mut C2Message, key: &[u8; 32]) {
    let mut tmp = msg.clone();
    tmp.hmac = [0u8; 32];
    let raw = serialize(&tmp);
    let mut mac = HmacSha256::new_from_slice(key).unwrap();
    mac.update(&raw[..raw.len() - 32]);
    let result = mac.finalize().into_bytes();
    msg.hmac.copy_from_slice(&result);
}

pub fn verify_message(msg: &C2Message, key: &[u8; 32]) -> bool {
    let mut tmp = msg.clone();
    tmp.hmac = [0u8; 32];
    let raw = serialize(&tmp);
    let mut mac = HmacSha256::new_from_slice(key).unwrap();
    mac.update(&raw[..raw.len() - 32]);
    mac.verify_slice(&msg.hmac).is_ok()
}

pub fn encrypt_payload(payload: &[u8], key: &[u8; 32]) -> Vec<u8> {
    let k = Key::<Aes256Gcm>::from_slice(key);
    let cipher = Aes256Gcm::new(k);
    let mut nonce_bytes = [0u8; 12];
    OsRng.fill_bytes(&mut nonce_bytes);
    let nonce = Nonce::from_slice(&nonce_bytes);
    let mut ct = cipher.encrypt(nonce, payload).unwrap_or_default();
    let mut out = nonce_bytes.to_vec();
    out.append(&mut ct);
    out
}

pub fn decrypt_payload(ciphertext: &[u8], key: &[u8; 32]) -> Result<Vec<u8>, ProtocolError> {
    if ciphertext.len() < 13 {
        return Err(ProtocolError::BufferTooShort);
    }
    let k = Key::<Aes256Gcm>::from_slice(key);
    let cipher = Aes256Gcm::new(k);
    let nonce = Nonce::from_slice(&ciphertext[..12]);
    cipher.decrypt(nonce, &ciphertext[12..])
        .map_err(|_| ProtocolError::DecryptFailed)
}
