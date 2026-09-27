// [context: Rust, Linux/x64, module versioning and dependency tracking]

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::{Arc, RwLock};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModuleEntry {
    pub name: String,
    pub version: String,
    pub min_android_api: u32,
    pub max_android_api: Option<u32>,
    pub dependencies: Vec<String>,
    pub checksum: String,
}

#[derive(Clone)]
pub struct ModuleManifest {
    modules: Arc<RwLock<HashMap<String, Vec<ModuleEntry>>>>,
}

impl ModuleManifest {
    pub fn new() -> Self {
        Self { modules: Arc::new(RwLock::new(HashMap::new())) }
    }

    pub fn register_module(&self, entry: ModuleEntry) {
        let mut map = self.modules.write().unwrap();
        map.entry(entry.name.clone()).or_default().push(entry);
    }

    pub fn check_compatibility(&self, module_name: &str, android_api_level: u32) -> bool {
        let map = self.modules.read().unwrap();
        map.get(module_name).map(|versions| {
            versions.iter().any(|m| {
                android_api_level >= m.min_android_api
                    && m.max_android_api.map(|max| android_api_level <= max).unwrap_or(true)
            })
        }).unwrap_or(false)
    }

    pub fn get_latest_version(&self, module_name: &str) -> Option<String> {
        let map = self.modules.read().unwrap();
        map.get(module_name)?.iter()
            .max_by(|a, b| semver_compare(&a.version, &b.version))
            .map(|m| m.version.clone())
    }

    pub fn resolve_deps(&self, module_name: &str) -> Vec<ModuleEntry> {
        let map = self.modules.read().unwrap();
        let mut result = Vec::new();
        let mut visited = std::collections::HashSet::new();
        self.resolve_recursive(module_name, &map, &mut result, &mut visited);
        result
    }

    fn resolve_recursive(
        &self,
        name: &str,
        map: &HashMap<String, Vec<ModuleEntry>>,
        result: &mut Vec<ModuleEntry>,
        visited: &mut std::collections::HashSet<String>,
    ) {
        if visited.contains(name) { return; }
        visited.insert(name.to_string());
        if let Some(versions) = map.get(name) {
            if let Some(latest) = versions.iter().max_by(|a, b| semver_compare(&a.version, &b.version)) {
                for dep in &latest.dependencies {
                    self.resolve_recursive(dep, map, result, visited);
                }
                result.push(latest.clone());
            }
        }
    }
}

fn semver_compare(a: &str, b: &str) -> std::cmp::Ordering {
    let parse = |s: &str| -> (u64, u64, u64) {
        let parts: Vec<u64> = s.split('.').filter_map(|x| x.parse().ok()).collect();
        (parts.get(0).copied().unwrap_or(0), parts.get(1).copied().unwrap_or(0), parts.get(2).copied().unwrap_or(0))
    };
    parse(a).cmp(&parse(b))
}
