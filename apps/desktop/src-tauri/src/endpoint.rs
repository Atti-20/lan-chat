use std::net::IpAddr;

use url::Url;

const MAX_ADDRESS_LENGTH: usize = 2_048;

pub fn normalize_origin(value: &str) -> Result<String, String> {
    let parsed = parse_http_url(value)?;
    if parsed.path() != "/" || parsed.query().is_some() || parsed.fragment().is_some() {
        return Err("node origin must not contain a path, query, or fragment".to_string());
    }
    Ok(parsed.origin().ascii_serialization())
}

pub fn normalize_node_address(value: &str) -> Result<String, String> {
    let trimmed = value.trim();
    let with_scheme = if trimmed.contains("://") {
        trimmed.to_string()
    } else {
        format!("http://{trimmed}")
    };
    let parsed = parse_http_url(&with_scheme)?;
    if !matches!(
        parsed.path(),
        "/" | "/app" | "/app/" | "/api/v1" | "/api/v1/"
    ) {
        return Err("node address path must be /, /app/, or /api/v1".to_string());
    }
    if parsed.query().is_some() || parsed.fragment().is_some() {
        return Err("node address must not contain a query or fragment".to_string());
    }
    Ok(parsed.origin().ascii_serialization())
}

fn parse_http_url(value: &str) -> Result<Url, String> {
    let trimmed = value.trim();
    if trimmed.is_empty() || trimmed.len() > MAX_ADDRESS_LENGTH {
        return Err("node address is empty or too long".to_string());
    }
    let parsed = Url::parse(trimmed).map_err(|_| "node address is invalid".to_string())?;
    if !matches!(parsed.scheme(), "http" | "https") {
        return Err("node address must use http or https".to_string());
    }
    if parsed.username() != "" || parsed.password().is_some() {
        return Err("node address must not contain credentials".to_string());
    }
    let host = parsed
        .host_str()
        .ok_or_else(|| "node address must contain a host".to_string())?;
    if let Ok(ip) = host.parse::<IpAddr>() {
        if ip.is_unspecified() || ip.is_multicast() {
            return Err("node address is not connectable".to_string());
        }
    }
    Ok(parsed)
}

pub fn valid_node_id(value: &str) -> bool {
    let bytes = value.as_bytes();
    (3..=64).contains(&bytes.len())
        && bytes[0].is_ascii_alphanumeric()
        && bytes.iter().all(|byte| {
            byte.is_ascii_lowercase() || byte.is_ascii_digit() || matches!(byte, b'_' | b'-')
        })
}

#[cfg(test)]
mod tests {
    use super::{normalize_node_address, normalize_origin, valid_node_id};

    #[test]
    fn normalizes_supported_manual_addresses() {
        assert_eq!(
            normalize_node_address("192.168.1.8:8080/app/").unwrap(),
            "http://192.168.1.8:8080"
        );
        assert_eq!(
            normalize_node_address("https://chat.local/api/v1").unwrap(),
            "https://chat.local"
        );
    }

    #[test]
    fn rejects_unsafe_origins() {
        assert!(normalize_origin("file:///tmp/app").is_err());
        assert!(normalize_origin("http://user:pass@host").is_err());
        assert!(normalize_origin("http://224.0.0.1").is_err());
        assert!(normalize_origin("http://host/app/").is_err());
    }

    #[test]
    fn validates_node_ids() {
        assert!(valid_node_id("node_123"));
        assert!(!valid_node_id("NODE"));
        assert!(!valid_node_id("-bad"));
    }
}
