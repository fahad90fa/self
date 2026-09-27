// [context: Rust, Linux/x64, JWT operator auth]

use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};
use thiserror::Error;
use std::time::{SystemTime, UNIX_EPOCH};
use uuid::Uuid;

#[derive(Error, Debug)]
pub enum AuthError {
    #[error("invalid token")]
    InvalidToken,
    #[error("expired token")]
    ExpiredToken,
    #[error("insufficient role")]
    InsufficientRole,
}

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq)]
pub enum OperatorRole {
    Admin,
    ReadOnly,
    Campaign,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct Claims {
    pub sub: String,
    pub iat: u64,
    pub exp: u64,
    pub role: OperatorRole,
    pub jti: String,
}

pub struct AuthService {
    secret: Vec<u8>,
    token_ttl_secs: u64,
}

impl AuthService {
    pub fn new(secret: &str, token_ttl_secs: u64) -> Self {
        Self {
            secret: secret.as_bytes().to_vec(),
            token_ttl_secs,
        }
    }

    pub fn create_token(&self, operator_id: &str, role: OperatorRole) -> Result<String, AuthError> {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs();
        let claims = Claims {
            sub: operator_id.to_string(),
            iat: now,
            exp: now + self.token_ttl_secs,
            role,
            jti: Uuid::new_v4().to_string(),
        };
        encode(
            &Header::default(),
            &claims,
            &EncodingKey::from_secret(&self.secret),
        )
        .map_err(|_| AuthError::InvalidToken)
    }

    pub fn verify_token(&self, token: &str) -> Result<Claims, AuthError> {
        decode::<Claims>(
            token,
            &DecodingKey::from_secret(&self.secret),
            &Validation::default(),
        )
        .map(|data| data.claims)
        .map_err(|e| {
            if e.to_string().contains("expired") {
                AuthError::ExpiredToken
            } else {
                AuthError::InvalidToken
            }
        })
    }

    pub fn refresh_token(&self, token: &str) -> Result<String, AuthError> {
        let claims = self.verify_token(token)?;
        self.create_token(&claims.sub, claims.role)
    }

    pub fn require_role(&self, token: &str, required: &OperatorRole) -> Result<Claims, AuthError> {
        let claims = self.verify_token(token)?;
        if &claims.role != required && claims.role != OperatorRole::Admin {
            return Err(AuthError::InsufficientRole);
        }
        Ok(claims)
    }
}
