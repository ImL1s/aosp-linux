// guest/portal-agent/src/desktop_parser.rs
// Desktop Entry (.desktop) parser validating syntax, NoDisplay filtering, and icon resolution (F-R4-005)

use serde::{Deserialize, Serialize};
use std::fs;
use std::path::Path;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct DesktopAppInfo {
    pub app_id: String,
    pub name: String,
    pub generic_name: String,
    pub comment: String,
    pub icon: String,
    pub exec: String,
    pub categories: String,
    pub mime_types: String,
    pub is_terminal: bool,
    pub no_display: bool,
}

pub fn parse_desktop_file(file_path: &Path) -> Result<Option<DesktopAppInfo>, String> {
    let content = fs::read_to_string(file_path)
        .map_err(|e| format!("Failed to read desktop file {:?}: {}", file_path, e))?;

    if !content.contains("[Desktop Entry]") {
        return Err(format!("Malformed desktop entry: missing [Desktop Entry] in {:?}", file_path));
    }

    let app_id = file_path
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or("unknown_app")
        .to_string();

    let mut name = String::new();
    let mut generic_name = String::new();
    let mut comment = String::new();
    let mut icon = String::new();
    let mut exec = String::new();
    let mut categories = String::new();
    let mut mime_types = String::new();
    let mut is_terminal = false;
    let mut no_display = false;

    let mut in_desktop_entry = false;

    for line in content.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with('#') || trimmed.is_empty() {
            continue;
        }

        if trimmed.starts_with('[') && trimmed.ends_with(']') {
            in_desktop_entry = trimmed == "[Desktop Entry]";
            continue;
        }

        if !in_desktop_entry {
            continue;
        }

        if let Some((key, value)) = trimmed.split_once('=') {
            let k = key.trim();
            let v = value.trim();

            match k {
                "Name" => name = v.to_string(),
                "GenericName" => generic_name = v.to_string(),
                "Comment" => comment = v.to_string(),
                "Icon" => icon = v.to_string(),
                "Exec" => exec = v.to_string(),
                "Categories" => categories = v.to_string(),
                "MimeType" => mime_types = v.to_string(),
                "Terminal" => is_terminal = v.eq_ignore_ascii_case("true"),
                "NoDisplay" => no_display = v.eq_ignore_ascii_case("true"),
                _ => {}
            }
        }
    }

    if no_display {
        return Ok(None);
    }

    if name.is_empty() {
        name = app_id.clone();
    }

    // Resolve icon path or fallback
    if icon.is_empty() || (!icon.starts_with('/') && !Path::new(&icon).exists()) {
        let candidate = format!("/usr/share/icons/{}.png", icon);
        if Path::new(&candidate).exists() {
            icon = candidate;
        } else {
            icon = "/usr/share/icons/default_linux_app_icon.png".to_string();
        }
    }

    Ok(Some(DesktopAppInfo {
        app_id,
        name,
        generic_name,
        comment,
        icon,
        exec,
        categories,
        mime_types,
        is_terminal,
        no_display,
    }))
}
