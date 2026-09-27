// [context: Rust, Linux/x64, monthly DGA seed distribution]

use rand_core::{OsRng, RngCore};
use std::sync::{Arc, RwLock};
use chrono::{Utc, Datelike};

pub struct SeedManager {
    current_seed: Arc<RwLock<[u8; 32]>>,
    next_seed: Arc<RwLock<[u8; 32]>>,
}

impl SeedManager {
    pub fn new() -> Self {
        let mut current = [0u8; 32];
        let mut next = [0u8; 32];
        OsRng.fill_bytes(&mut current);
        OsRng.fill_bytes(&mut next);
        Self {
            current_seed: Arc::new(RwLock::new(current)),
            next_seed: Arc::new(RwLock::new(next)),
        }
    }

    pub fn current_seed(&self) -> [u8; 32] {
        *self.current_seed.read().unwrap()
    }

    pub fn next_month_seed(&self) -> [u8; 32] {
        *self.next_seed.read().unwrap()
    }

    pub fn rotate_seed(&self) {
        let next = *self.next_seed.read().unwrap();
        *self.current_seed.write().unwrap() = next;
        let mut new_next = [0u8; 32];
        OsRng.fill_bytes(&mut new_next);
        *self.next_seed.write().unwrap() = new_next;
    }

    pub fn should_rotate_now(&self) -> bool {
        let now = Utc::now();
        now.day() == 1 && now.hour() == 0
    }

    pub fn start_rotation_loop(self: Arc<Self>) {
        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(tokio::time::Duration::from_secs(3600));
            loop {
                ticker.tick().await;
                if self.should_rotate_now() {
                    self.rotate_seed();
                    tracing::info!("DGA seed rotated for new month");
                }
            }
        });
    }

    pub async fn distribute_seed_via_fcm(
        &self,
        device_ids: &[String],
        fcm_relay: &crate::fcm_relay::FcmRelay,
    ) {
        let next = self.next_month_seed();
        let seed_hex = hex::encode(next);
        for device_id in device_ids {
            let _ = fcm_relay.send_data(device_id, &[
                ("type".to_string(), "seed_update".to_string()),
                ("seed".to_string(), seed_hex.clone()),
            ]).await;
        }
    }
}
