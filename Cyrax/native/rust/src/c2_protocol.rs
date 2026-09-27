use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct C2Message {
    pub device_id: String,
    pub session_id: String,
    pub message_type: String,
    pub payload: Value,
    pub sequence: u64,
    pub timestamp: u64,
}
