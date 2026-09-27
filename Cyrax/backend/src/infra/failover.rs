// [context: Rust, Linux/x64, automatic C2 endpoint failover chain]

use std::collections::VecDeque;
use std::sync::{Arc, RwLock};
use thiserror::Error;

#[derive(Error, Debug)]
pub enum FailoverError {
    #[error("no endpoints available")]
    NoEndpoints,
}

#[derive(Debug, Clone)]
pub struct Endpoint {
    pub address: String,
    pub priority: u8,
    pub is_active: bool,
    pub failure_count: u32,
}

pub struct FailoverManager {
    endpoints: Arc<RwLock<VecDeque<Endpoint>>>,
    active_idx: Arc<RwLock<usize>>,
    max_failures: u32,
}

impl FailoverManager {
    pub fn new(endpoints: Vec<String>, max_failures: u32) -> Self {
        let eps: VecDeque<Endpoint> = endpoints.iter().enumerate().map(|(i, addr)| Endpoint {
            address: addr.clone(),
            priority: (endpoints.len() - i) as u8,
            is_active: true,
            failure_count: 0,
        }).collect();
        Self {
            endpoints: Arc::new(RwLock::new(eps)),
            active_idx: Arc::new(RwLock::new(0)),
            max_failures,
        }
    }

    pub fn get_active_endpoint(&self) -> Option<String> {
        let eps = self.endpoints.read().unwrap();
        let idx = *self.active_idx.read().unwrap();
        eps.get(idx).filter(|e| e.is_active).map(|e| e.address.clone())
    }

    pub fn mark_endpoint_down(&self, endpoint: &str) {
        let mut eps = self.endpoints.write().unwrap();
        if let Some(ep) = eps.iter_mut().find(|e| e.address == endpoint) {
            ep.failure_count += 1;
            if ep.failure_count >= self.max_failures {
                ep.is_active = false;
            }
        }
        // advance active index to next healthy endpoint
        let next = eps.iter().position(|e| e.is_active && e.address != endpoint);
        if let Some(idx) = next {
            *self.active_idx.write().unwrap() = idx;
        }
    }

    pub fn restore_endpoint(&self, endpoint: &str) {
        let mut eps = self.endpoints.write().unwrap();
        if let Some(ep) = eps.iter_mut().find(|e| e.address == endpoint) {
            ep.is_active = true;
            ep.failure_count = 0;
        }
    }

    pub fn all_active_endpoints(&self) -> Vec<String> {
        self.endpoints.read().unwrap()
            .iter()
            .filter(|e| e.is_active)
            .map(|e| e.address.clone())
            .collect()
    }

    pub fn failover_devices(
        &self,
        from_endpoint: &str,
        to_endpoint: &str,
        ws_manager: &crate::transport::websocket_handler::WsManager,
    ) {
        use axum::extract::ws::Message;
        let redirect_msg = serde_json::json!({
            "type": "redirect",
            "endpoint": to_endpoint
        }).to_string();
        ws_manager.broadcast(Message::Text(redirect_msg));
        self.mark_endpoint_down(from_endpoint);
    }
}
