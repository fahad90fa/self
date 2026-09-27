// [context: Rust, Linux/x64, auto-register DGA candidate domains via Namecheap API]

use reqwest::Client;
use serde::{Deserialize, Serialize};
use thiserror::Error;
use chrono::Utc;
use std::collections::HashSet;
use std::sync::{Arc, Mutex};

#[derive(Error, Debug)]
pub enum RegistrarError {
    #[error("http error: {0}")]
    Http(#[from] reqwest::Error),
    #[error("api error: {0}")]
    Api(String),
}

pub struct DomainRegistrar {
    client: Client,
    api_key: String,
    api_user: String,
    registrant_email: String,
    registered: Arc<Mutex<HashSet<String>>>,
}

impl DomainRegistrar {
    pub fn new(api_key: String, api_user: String, registrant_email: String) -> Self {
        Self {
            client: Client::new(),
            api_key,
            api_user,
            registrant_email,
            registered: Arc::new(Mutex::new(HashSet::new())),
        }
    }

    pub async fn check_domain_available(&self, domain: &str) -> bool {
        let sld_tld = self.split_domain(domain);
        let url = format!(
            "https://api.namecheap.com/xml.response?ApiUser={}&ApiKey={}&UserName={}&Command=namecheap.domains.check&ClientIp=127.0.0.1&DomainList={}",
            self.api_user, self.api_key, self.api_user, sld_tld
        );
        self.client.get(&url).send().await
            .ok()
            .and_then(|r| r.text().await.ok())
            .map(|body| body.contains("Available=\"true\""))
            .unwrap_or(false)
    }

    pub async fn register_domain(&self, domain: &str, nameservers: &[&str]) -> Result<(), RegistrarError> {
        let ns_list = nameservers.join(",");
        let url = format!(
            "https://api.namecheap.com/xml.response?ApiUser={}&ApiKey={}&UserName={}&Command=namecheap.domains.create&ClientIp=127.0.0.1&DomainName={}&Years=1&RegistrantEmailAddress={}&TechEmailAddress={}&Nameservers={}",
            self.api_user, self.api_key, self.api_user, domain,
            self.registrant_email, self.registrant_email, ns_list
        );
        let resp = self.client.get(&url).send().await?;
        let body = resp.text().await?;
        if body.contains("Status=\"OK\"") {
            self.registered.lock().unwrap().insert(domain.to_string());
            Ok(())
        } else {
            Err(RegistrarError::Api(body))
        }
    }

    pub fn get_registered_today(&self) -> Vec<String> {
        self.registered.lock().unwrap().iter().cloned().collect()
    }

    pub fn cleanup_expired(&self) {
        self.registered.lock().unwrap().clear();
    }

    fn split_domain(&self, domain: &str) -> String {
        domain.to_string()
    }
}
