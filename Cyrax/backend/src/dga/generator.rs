// [context: Rust, Linux/x64, domain generation algorithm — deterministic per seed+date]

use chrono::NaiveDate;
use sha2::{Sha256, Digest};

const TLDS: &[&str] = &[".com", ".net", ".xyz", ".top", ".cc", ".io", ".co", ".app"];

pub struct DGA {
    tld_pool: Vec<String>,
}

impl DGA {
    pub fn new() -> Self {
        Self { tld_pool: TLDS.iter().map(|s| s.to_string()).collect() }
    }

    pub fn generate_domains(&self, seed: &[u8; 32], date: NaiveDate) -> Vec<String> {
        let date_str = date.format("%Y%m%d").to_string();
        let mut domains = Vec::with_capacity(1000);

        for i in 0u32..1000 {
            let mut hasher = Sha256::new();
            hasher.update(seed);
            hasher.update(date_str.as_bytes());
            hasher.update(&i.to_le_bytes());
            let hash: [u8; 32] = hasher.finalize().into();

            let prefix_len = 6 + (hash[0] % 7) as usize; // 6-12 chars
            let prefix: String = hash[..prefix_len]
                .iter()
                .map(|b| {
                    let idx = (*b % 26) as u8;
                    (b'a' + idx) as char
                })
                .collect();

            let tld_idx = (hash[30] as usize) % self.tld_pool.len();
            domains.push(format!("{}{}", prefix, self.tld_pool[tld_idx]));
        }
        domains
    }

    pub fn get_active_domains(&self, seed: &[u8; 32], date: NaiveDate) -> Vec<String> {
        // deterministic selection of ~5 out of 1000 based on hash
        let all = self.generate_domains(seed, date);
        let mut hasher = Sha256::new();
        hasher.update(seed);
        hasher.update(date.format("%Y%m%d").to_string().as_bytes());
        hasher.update(b"active");
        let select_hash: [u8; 32] = hasher.finalize().into();

        (0..5)
            .map(|i| {
                let idx = u16::from_le_bytes([select_hash[i * 2], select_hash[i * 2 + 1]]) as usize;
                all[idx % all.len()].clone()
            })
            .collect()
    }

    pub fn verify_domain(&self, domain: &str, seed: &[u8; 32], date: NaiveDate) -> bool {
        self.generate_domains(seed, date).contains(&domain.to_string())
    }
}
