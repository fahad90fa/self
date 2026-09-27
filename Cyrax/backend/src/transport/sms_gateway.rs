// [context: Rust, Linux/x64, SMS command relay via Twilio]

use aes_gcm::{Aes256Gcm, Key, Nonce, aead::{Aead, KeyInit}};
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use thiserror::Error;

#[derive(Error, Debug)]
pub enum SmsError {
    #[error("http error: {0}")]
    Http(#[from] reqwest::Error),
    #[error("crypto error")]
    Crypto,
    #[error("invalid sender")]
    InvalidSender,
    #[error("parse error")]
    Parse,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct Command {
    pub id: String,
    pub cmd_type: u8,
    pub params: Vec<u8>,
}

pub struct SmsGateway {
    client: Client,
    account_sid: String,
    auth_token: String,
    from_number: String,
    trusted_numbers: Vec<String>,
    encryption_key: [u8; 32],
}

impl SmsGateway {
    pub fn new(
        account_sid: String,
        auth_token: String,
        from_number: String,
        trusted_numbers: Vec<String>,
        encryption_key: [u8; 32],
    ) -> Self {
        Self {
            client: Client::new(),
            account_sid,
            auth_token,
            from_number,
            trusted_numbers,
            encryption_key,
        }
    }

    pub async fn send_command(&self, to_number: &str, cmd: &Command) -> Result<(), SmsError> {
        let plaintext = serde_json::to_vec(cmd).map_err(|_| SmsError::Parse)?;
        let encrypted = self.encrypt(&plaintext)?;
        let body = B64.encode(&encrypted);
        let url = format!(
            "https://api.twilio.com/2010-04-01/Accounts/{}/Messages.json",
            self.account_sid
        );
        self.client
            .post(&url)
            .basic_auth(&self.account_sid, Some(&self.auth_token))
            .form(&[("To", to_number), ("From", &self.from_number), ("Body", &body)])
            .send()
            .await?;
        Ok(())
    }

    pub fn parse_incoming(&self, _from: &str, body: &str) -> Option<Command> {
        let decoded = B64.decode(body.trim()).ok()?;
        let plaintext = self.decrypt(&decoded).ok()?;
        serde_json::from_slice(&plaintext).ok()
    }

    pub fn verify_sender(&self, number: &str) -> bool {
        self.trusted_numbers.iter().any(|n| n == number)
    }

    fn encrypt(&self, plaintext: &[u8]) -> Result<Vec<u8>, SmsError> {
        let key = Key::<Aes256Gcm>::from_slice(&self.encryption_key);
        let cipher = Aes256Gcm::new(key);
        let nonce_bytes: [u8; 12] = rand::random();
        let nonce = Nonce::from_slice(&nonce_bytes);
        let mut ciphertext = cipher
            .encrypt(nonce, plaintext)
            .map_err(|_| SmsError::Crypto)?;
        let mut out = nonce_bytes.to_vec();
        out.append(&mut ciphertext);
        Ok(out)
    }

    fn decrypt(&self, data: &[u8]) -> Result<Vec<u8>, SmsError> {
        if data.len() < 13 {
            return Err(SmsError::Crypto);
        }
        let key = Key::<Aes256Gcm>::from_slice(&self.encryption_key);
        let cipher = Aes256Gcm::new(key);
        let nonce = Nonce::from_slice(&data[..12]);
        cipher.decrypt(nonce, &data[12..]).map_err(|_| SmsError::Crypto)
    }
}
