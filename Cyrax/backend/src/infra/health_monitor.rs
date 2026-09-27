// [context: Rust, Linux/x64, C2 node health monitoring]

use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::{Arc, RwLock};
use std::time::{Duration, Instant};
use thiserror::Error;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NodeHealth {
    pub node_id: String,
    pub latency_ms: u64,
    pub uptime_secs: u64,
    pub error_rate: f64,
    pub connection_count: u64,
    pub is_healthy: bool,
}

#[derive(Debug, Clone)]
pub struct NodeStatus {
    pub id: String,
    pub address: String,
    pub health: Option<NodeHealth>,
    pub last_checked: Option<Instant>,
    pub consecutive_failures: u32,
}

#[derive(Error, Debug)]
pub enum MonitorError {
    #[error("http error: {0}")]
    Http(#[from] reqwest::Error),
}

pub struct HealthMonitor {
    client: Client,
    nodes: Arc<RwLock<HashMap<String, NodeStatus>>>,
    alert_threshold_failures: u32,
}

impl HealthMonitor {
    pub fn new(alert_threshold_failures: u32) -> Self {
        Self {
            client: Client::builder().timeout(Duration::from_secs(5)).build().unwrap(),
            nodes: Arc::new(RwLock::new(HashMap::new())),
            alert_threshold_failures,
        }
    }

    pub fn register_node(&self, id: String, address: String) {
        self.nodes.write().unwrap().insert(id.clone(), NodeStatus {
            id, address, health: None, last_checked: None, consecutive_failures: 0,
        });
    }

    pub async fn check_node(&self, addr: &str) -> NodeHealth {
        let start = Instant::now();
        let result = self.client.get(&format!("{}/health", addr)).send().await;
        let latency_ms = start.elapsed().as_millis() as u64;

        match result {
            Ok(resp) if resp.status().is_success() => {
                let body: serde_json::Value = resp.json().await.unwrap_or_default();
                NodeHealth {
                    node_id: addr.to_string(),
                    latency_ms,
                    uptime_secs: body.get("uptime").and_then(|v| v.as_u64()).unwrap_or(0),
                    error_rate: body.get("error_rate").and_then(|v| v.as_f64()).unwrap_or(0.0),
                    connection_count: body.get("connections").and_then(|v| v.as_u64()).unwrap_or(0),
                    is_healthy: true,
                }
            }
            _ => NodeHealth {
                node_id: addr.to_string(),
                latency_ms,
                uptime_secs: 0,
                error_rate: 1.0,
                connection_count: 0,
                is_healthy: false,
            }
        }
    }

    pub async fn check_all(&self) {
        let addresses: Vec<(String, String)> = self.nodes.read().unwrap()
            .iter()
            .map(|(id, status)| (id.clone(), status.address.clone()))
            .collect();

        for (id, addr) in addresses {
            let health = self.check_node(&addr).await;
            let is_healthy = health.is_healthy;
            let mut nodes = self.nodes.write().unwrap();
            if let Some(status) = nodes.get_mut(&id) {
                if !is_healthy {
                    status.consecutive_failures += 1;
                } else {
                    status.consecutive_failures = 0;
                }
                status.health = Some(health);
                status.last_checked = Some(Instant::now());
            }
        }
    }

    pub fn get_all_nodes(&self) -> Vec<NodeStatus> {
        self.nodes.read().unwrap().values().cloned().collect()
    }

    pub fn get_unhealthy_nodes(&self) -> Vec<String> {
        self.nodes.read().unwrap()
            .values()
            .filter(|n| n.consecutive_failures >= self.alert_threshold_failures)
            .map(|n| n.id.clone())
            .collect()
    }

    pub fn start_monitor_loop(self: Arc<Self>, interval_secs: u64) {
        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(Duration::from_secs(interval_secs));
            loop {
                ticker.tick().await;
                self.check_all().await;
            }
        });
    }
}
