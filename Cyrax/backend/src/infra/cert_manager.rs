// [context: Rust, Linux/x64, TLS cert auto-renewal via ACME/Let's Encrypt]

use thiserror::Error;
use std::path::PathBuf;
use std::time::Duration;
use tokio::time::interval;

#[derive(Error, Debug)]
pub enum CertError {
    #[error("acme error: {0}")]
    Acme(String),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
}

pub struct CertPair {
    pub cert_pem: Vec<u8>,
    pub key_pem: Vec<u8>,
    pub expires_in_days: i64,
}

pub struct CertManager {
    acme_email: String,
    acme_directory: String,
    cert_dir: PathBuf,
    http_port: u16,
}

impl CertManager {
    pub fn new(acme_email: String, cert_dir: PathBuf, http_port: u16) -> Self {
        Self {
            acme_email,
            acme_directory: "https://acme-v02.api.letsencrypt.org/directory".to_string(),
            cert_dir,
            http_port,
        }
    }

    pub fn check_expiry(&self, domain: &str) -> i64 {
        let cert_path = self.cert_dir.join(format!("{}.crt", domain));
        if !cert_path.exists() {
            return -1; // not found
        }
        // parse cert and check expiry via openssl command
        let output = std::process::Command::new("openssl")
            .args(["x509", "-noout", "-enddate", "-in", cert_path.to_str().unwrap()])
            .output()
            .ok();

        output.and_then(|o| {
            let s = String::from_utf8_lossy(&o.stdout).to_string();
            // parse "notAfter=Sep 30 00:00:00 2026 GMT"
            let date_str = s.trim_start_matches("notAfter=").trim().to_string();
            let expiry = chrono::DateTime::parse_from_str(&date_str, "%b %e %H:%M:%S %Y %Z").ok()?;
            let diff = expiry.signed_duration_since(chrono::Utc::now());
            Some(diff.num_days())
        }).unwrap_or(-1)
    }

    pub async fn renew_cert(&self, domain: &str) -> Result<CertPair, CertError> {
        // invoke certbot for renewal
        let status = tokio::process::Command::new("certbot")
            .args([
                "certonly",
                "--standalone",
                "--preferred-challenges", "http",
                "--http-01-port", &self.http_port.to_string(),
                "-d", domain,
                "--email", &self.acme_email,
                "--agree-tos",
                "--non-interactive",
                "--cert-path", self.cert_dir.to_str().unwrap(),
            ])
            .status()
            .await
            .map_err(|e| CertError::Acme(e.to_string()))?;

        if !status.success() {
            return Err(CertError::Acme("certbot failed".to_string()));
        }

        let cert_path = self.cert_dir.join(format!("{}.crt", domain));
        let key_path = self.cert_dir.join(format!("{}.key", domain));

        Ok(CertPair {
            cert_pem: std::fs::read(&cert_path)?,
            key_pem: std::fs::read(&key_path)?,
            expires_in_days: 90,
        })
    }

    pub fn start_auto_renew_loop(self: std::sync::Arc<Self>, domains: Vec<String>) {
        tokio::spawn(async move {
            let mut ticker = interval(Duration::from_secs(86400));
            loop {
                ticker.tick().await;
                for domain in &domains {
                    let days = self.check_expiry(domain);
                    if days < 30 {
                        tracing::info!("Renewing cert for {} ({} days left)", domain, days);
                        match self.renew_cert(domain).await {
                            Ok(_) => tracing::info!("Cert renewed for {}", domain),
                            Err(e) => tracing::error!("Cert renewal failed for {}: {}", domain, e),
                        }
                    }
                }
            }
        });
    }
}
