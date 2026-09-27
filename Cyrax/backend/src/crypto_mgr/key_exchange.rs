// [context: Rust, Linux/x64, ECDH x25519 device enrollment]

use x25519_dalek::{EphemeralSecret, PublicKey, StaticSecret};
use rand_core::OsRng;
use sha2::{Sha256, Digest};
use thiserror::Error;

#[derive(Error, Debug)]
pub enum KeyExchangeError {
    #[error("invalid public key")]
    InvalidPublicKey,
    #[error("enrollment failed")]
    EnrollmentFailed,
}

#[derive(Clone)]
pub struct SessionKeys {
    pub send_key: [u8; 32],
    pub recv_key: [u8; 32],
}

pub struct ServerKeypair {
    pub public: [u8; 32],
    secret: StaticSecret,
}

pub struct KeyExchange;

impl KeyExchange {
    pub fn generate_server_keypair() -> ServerKeypair {
        let secret = StaticSecret::random_from_rng(OsRng);
        let public = PublicKey::from(&secret);
        ServerKeypair {
            public: *public.as_bytes(),
            secret,
        }
    }

    pub fn derive_shared_secret(server_priv: &StaticSecret, device_pub_bytes: &[u8; 32]) -> [u8; 32] {
        let device_pub = PublicKey::from(*device_pub_bytes);
        let shared = server_priv.diffie_hellman(&device_pub);
        *shared.as_bytes()
    }

    pub fn enroll_device(
        device_id: &str,
        device_pub_key_bytes: &[u8; 32],
    ) -> Result<SessionKeys, KeyExchangeError> {
        let keypair = Self::generate_server_keypair();
        let shared = Self::derive_shared_secret(&keypair.secret, device_pub_key_bytes);

        let mut hasher_send = Sha256::new();
        hasher_send.update(&shared);
        hasher_send.update(b"send");
        hasher_send.update(device_id.as_bytes());
        let send_key: [u8; 32] = hasher_send.finalize().into();

        let mut hasher_recv = Sha256::new();
        hasher_recv.update(&shared);
        hasher_recv.update(b"recv");
        hasher_recv.update(device_id.as_bytes());
        let recv_key: [u8; 32] = hasher_recv.finalize().into();

        Ok(SessionKeys { send_key, recv_key })
    }
}
