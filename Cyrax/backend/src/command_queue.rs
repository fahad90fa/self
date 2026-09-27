use anyhow::Result;
use chrono::Utc;
use sqlx::SqlitePool;
use tracing::debug;
use uuid::Uuid;

pub struct CommandQueue {
    db: SqlitePool,
}

#[derive(Debug, Clone, sqlx::FromRow)]
pub struct CommandRecord {
    pub command_id: String,
    pub device_id: String,
    pub command_type: String,
    pub priority: i32,
    pub payload: Option<Vec<u8>>,
    pub status: String,
    pub created_at: String,
    pub sent_at: Option<String>,
    pub executed_at: Option<String>,
    pub result: Option<String>,
    pub error_msg: Option<String>,
    pub retry_count: i32,
    pub max_retries: i32,
}

impl CommandQueue {
    pub fn new(db: SqlitePool) -> Self {
        Self { db }
    }

    /// Queue a new command for a device
    pub async fn queue_command(
        &self,
        device_id: &str,
        command_type: &str,
        priority: i32,
        payload: Option<Vec<u8>>,
    ) -> Result<String> {
        let command_id = Uuid::new_v4().to_string();
        let now = Utc::now();

        sqlx::query(
            r#"
            INSERT INTO commands (
                command_id, device_id, command_type, priority,
                payload, status, created_at, retry_count, max_retries
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            "#,
        )
        .bind(&command_id)
        .bind(device_id)
        .bind(command_type)
        .bind(priority)
        .bind(payload)
        .bind("pending")
        .bind(&now)
        .bind(0)
        .bind(3)
        .execute(&self.db)
        .await?;

        debug!("Command queued: command_id={}, device_id={}, type={}", 
               command_id, device_id, command_type);

        Ok(command_id)
    }

    /// Get pending commands for a device (ordered by priority)
    pub async fn get_pending_commands(&self, device_id: &str, limit: i32) -> Result<Vec<String>> {
        let records = sqlx::query_as::<_, CommandRecord>(
            r#"
            SELECT * FROM commands 
            WHERE device_id = ? AND status = 'pending' AND retry_count < max_retries
            ORDER BY priority DESC, created_at ASC
            LIMIT ?
            "#
        )
        .bind(device_id)
        .bind(limit)
        .fetch_all(&self.db)
        .await?;

        // Convert to JSON strings for transport
        let commands: Vec<String> = records
            .into_iter()
            .map(|r| {
                serde_json::json!({
                    "command_id": r.command_id,
                    "device_id": r.device_id,
                    "command_type": r.command_type,
                    "priority": r.priority,
                    "payload": r.payload.as_ref().map(|p| hex::encode(p)),
                }).to_string()
            })
            .collect();

        Ok(commands)
    }

    /// Mark command as sent to device
    pub async fn mark_sent(&self, command_id: &str) -> Result<()> {
        let now = Utc::now();

        sqlx::query(
            "UPDATE commands SET status = 'sent', sent_at = ? WHERE command_id = ?"
        )
        .bind(&now)
        .bind(command_id)
        .execute(&self.db)
        .await?;

        debug!("Command marked sent: {}", command_id);
        Ok(())
    }

    /// Mark command as executed with result
    pub async fn mark_executed(&self, command_id: &str, result: Option<String>) -> Result<()> {
        let now = Utc::now();

        sqlx::query(
            "UPDATE commands SET status = 'executed', executed_at = ?, result = ? WHERE command_id = ?"
        )
        .bind(&now)
        .bind(result)
        .bind(command_id)
        .execute(&self.db)
        .await?;

        debug!("Command marked executed: {}", command_id);
        Ok(())
    }

    /// Mark command as failed and retry
    pub async fn mark_failed(&self, command_id: &str, error: &str) -> Result<()> {
        sqlx::query(
            r#"
            UPDATE commands 
            SET status = CASE 
                WHEN retry_count < max_retries THEN 'pending'
                ELSE 'failed'
            END,
            retry_count = retry_count + 1,
            error_msg = ?
            WHERE command_id = ?
            "#
        )
        .bind(error)
        .bind(command_id)
        .execute(&self.db)
        .await?;

        debug!("Command marked failed (retry): {}", command_id);
        Ok(())
    }

    /// Get all commands for a device
    pub async fn get_device_commands(&self, device_id: &str) -> Result<Vec<CommandRecord>> {
        let records = sqlx::query_as::<_, CommandRecord>(
            "SELECT * FROM commands WHERE device_id = ? ORDER BY created_at DESC"
        )
        .bind(device_id)
        .fetch_all(&self.db)
        .await?;

        Ok(records)
    }

    /// Get command by ID
    pub async fn get_command(&self, command_id: &str) -> Result<Option<CommandRecord>> {
        let record = sqlx::query_as::<_, CommandRecord>(
            "SELECT * FROM commands WHERE command_id = ?"
        )
        .bind(command_id)
        .fetch_optional(&self.db)
        .await?;

        Ok(record)
    }

    /// Count pending commands per device (useful for UI)
    pub async fn get_pending_count(&self, device_id: &str) -> Result<i64> {
        let count: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM commands WHERE device_id = ? AND status = 'pending'"
        )
        .bind(device_id)
        .fetch_one(&self.db)
        .await?;

        Ok(count)
    }

    /// Cancel a command
    pub async fn cancel_command(&self, command_id: &str) -> Result<()> {
        sqlx::query("UPDATE commands SET status = 'cancelled' WHERE command_id = ? AND status = 'pending'")
            .bind(command_id)
            .execute(&self.db)
            .await?;

        debug!("Command cancelled: {}", command_id);
        Ok(())
    }

    /// Cleanup old completed commands (retention policy)
    pub async fn cleanup_old_commands(&self, days_old: i32) -> Result<i64> {
        let result = sqlx::query(
            r#"
            DELETE FROM commands 
            WHERE status IN ('executed', 'failed', 'cancelled') 
            AND datetime(created_at) < datetime('now', '-' || ? || ' days')
            "#
        )
        .bind(days_old)
        .execute(&self.db)
        .await?;

        let deleted = result.rows_affected();
        debug!("Cleaned up {} old commands", deleted);
        Ok(deleted as i64)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    #[ignore] // Requires database
    async fn test_command_queue() {
        // This would require a test database setup
        // Leaving as placeholder for CI/CD integration
    }
}
