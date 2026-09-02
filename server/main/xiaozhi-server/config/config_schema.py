"""Validation for the effective runtime configuration."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping
from urllib.parse import urlparse


class ConfigValidationError(ValueError):
    """Raised when a required runtime configuration value is invalid."""


@dataclass(frozen=True)
class ConfigValidation:
    values: Mapping[str, Any]


def validate_config(
    config: Mapping[str, Any], *, require_manager_credentials: bool = False
) -> ConfigValidation:
    if not isinstance(config, Mapping):
        raise ConfigValidationError("configuration must be a mapping")

    server = config.get("server", {})
    if not isinstance(server, Mapping):
        raise ConfigValidationError("server must be a mapping")
    if "ip" in server:
        _required_text(server, "ip")
    if "port" in server:
        _port(server.get("port"), "server.port")
    if "http_port" in server and server["http_port"] is not None:
        _port(server["http_port"], "server.http_port")
    for key in ("websocket", "vision_explain"):
        if key in server and server[key]:
            _url(server[key], f"server.{key}")

    selected = config.get("selected_module", {})
    if not isinstance(selected, Mapping):
        raise ConfigValidationError("selected_module must be a mapping")
    for key, value in selected.items():
        if not isinstance(key, str) or not isinstance(value, str) or not value.strip():
            raise ConfigValidationError(f"invalid selected_module.{key}")

    for key in ("tts_timeout", "tool_call_timeout", "close_connection_no_voice_time"):
        if key in config:
            _positive_number(config[key], key)

    companion = config.get("companion", {})
    if companion is not None:
        if not isinstance(companion, Mapping):
            raise ConfigValidationError("companion must be a mapping")
        if "enabled" in companion and not isinstance(companion["enabled"], bool):
            raise ConfigValidationError("companion.enabled must be boolean")
        if "mode" in companion and (
            not isinstance(companion["mode"], str) or not companion["mode"].strip()
        ):
            raise ConfigValidationError("companion.mode must be non-empty")

    manager_api = config.get("manager-api", {})
    if manager_api is not None:
        if not isinstance(manager_api, Mapping):
            raise ConfigValidationError("manager-api must be a mapping")
        if manager_api.get("url"):
            _url(manager_api["url"], "manager-api.url")
            if require_manager_credentials:
                _required_text(manager_api, "secret")

    return ConfigValidation(config)


def _required_text(mapping: Mapping[str, Any], key: str) -> str:
    value = mapping.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ConfigValidationError(f"{key} must be non-empty")
    return value.strip()


def _port(value: Any, path: str) -> None:
    if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= 65535:
        raise ConfigValidationError(f"{path} must be between 1 and 65535")


def _positive_number(value: Any, path: str) -> None:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or value <= 0:
        raise ConfigValidationError(f"{path} must be positive")


def _url(value: Any, path: str) -> None:
    if not isinstance(value, str):
        raise ConfigValidationError(f"{path} must be a URL")
    parsed = urlparse(value)
    if parsed.scheme not in {"http", "https", "ws", "wss"} or not parsed.netloc:
        raise ConfigValidationError(f"{path} must be an absolute URL")
