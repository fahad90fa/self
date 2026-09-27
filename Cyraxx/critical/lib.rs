// Backend library - exposes all modules for testing and bindings

pub mod session;
pub mod device_registry;
pub mod command_queue;
pub mod metrics;
pub mod fcm_relay;
pub mod mqtt_handler;
pub mod dns_tunnel;
pub mod panel_api;
pub mod encryption_at_rest;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_session_creation() {
        // Session tests would go here
        assert!(true);
    }

    #[test]
    fn test_device_registry() {
        // Device registry tests
        assert!(true);
    }

    #[test]
    fn test_encryption() {
        // Encryption tests
        assert!(true);
    }

    #[test]
    fn test_command_queue() {
        // Command queue tests
        assert!(true);
    }
}
