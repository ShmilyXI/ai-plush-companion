"""Explicit plugin manifests used by runtime-scoped registries."""

from __future__ import annotations

import importlib.util
from dataclasses import dataclass
from typing import Iterable


SUPPORTED_EXECUTORS = frozenset(
    {"SERVER_PLUGIN", "SERVER_MCP", "DEVICE_MCP", "DEVICE_IOT", "MCP_ENDPOINT"}
)
SUPPORTED_TOOL_TYPES = frozenset({"PLUGIN", "MCP", "ROLE_MCP", "DEVICE_TOOL"})


@dataclass(frozen=True)
class PluginManifest:
    name: str
    module: str
    executor: str
    tool_type: str
    scope: str | None
    config_key: str | None = None
    required: bool = False


# The manifest is intentionally boring and reviewable. Importing a package no
# longer implies that every module below is active for every connection.
BUILTIN_MANIFEST = (
    PluginManifest("handle_exit_intent", "plugins_func.functions.handle_exit_intent", "SERVER_PLUGIN", "PLUGIN", "connection", "handle_exit_intent", True),
    PluginManifest("get_lunar", "plugins_func.functions.get_time", "SERVER_PLUGIN", "PLUGIN", "connection", "get_lunar"),
    PluginManifest("get_weather", "plugins_func.functions.get_weather", "SERVER_PLUGIN", "PLUGIN", "public", "get_weather"),
    PluginManifest("get_news_from_newsnow", "plugins_func.functions.get_news_from_newsnow", "SERVER_PLUGIN", "PLUGIN", "public", "get_news_from_newsnow"),
    PluginManifest("get_news_from_chinanews", "plugins_func.functions.get_news_from_chinanews", "SERVER_PLUGIN", "PLUGIN", "connection", "get_news_from_chinanews"),
    PluginManifest("play_music", "plugins_func.functions.play_music", "SERVER_PLUGIN", "PLUGIN", "connection", "play_music"),
    PluginManifest("change_role", "plugins_func.functions.change_role", "SERVER_PLUGIN", "PLUGIN", "connection", "change_role"),
    PluginManifest("call_device", "plugins_func.functions.call_device", "SERVER_PLUGIN", "DEVICE_TOOL", "connection", "call_device"),
    PluginManifest("web_search", "plugins_func.functions.web_search", "SERVER_PLUGIN", "PLUGIN", "connection", "web_search"),
    PluginManifest("search_from_ragflow", "plugins_func.functions.search_from_ragflow", "SERVER_PLUGIN", "PLUGIN", "connection", "search_from_ragflow"),
    PluginManifest("hass_get_state", "plugins_func.functions.hass_get_state", "SERVER_PLUGIN", "PLUGIN", "connection", "hass_get_state"),
    PluginManifest("hass_set_state", "plugins_func.functions.hass_set_state", "SERVER_PLUGIN", "PLUGIN", "connection", "hass_set_state"),
    PluginManifest("hass_play_music", "plugins_func.functions.hass_play_music", "SERVER_PLUGIN", "PLUGIN", "connection", "hass_play_music"),
)


def validate_manifests(manifests: Iterable[PluginManifest], *, check_modules: bool = False) -> tuple[PluginManifest, ...]:
    values = tuple(manifests)
    names: set[str] = set()
    for manifest in values:
        if not manifest.name or manifest.name in names:
            raise ValueError(f"duplicate plugin manifest: {manifest.name}")
        names.add(manifest.name)
        if not manifest.module:
            raise ValueError(f"plugin {manifest.name} has no module")
        if manifest.executor not in SUPPORTED_EXECUTORS:
            raise ValueError(f"plugin {manifest.name} has unsupported executor")
        if manifest.tool_type not in SUPPORTED_TOOL_TYPES:
            raise ValueError(f"plugin {manifest.name} has unsupported tool type")
        if manifest.tool_type == "PLUGIN" and not manifest.scope:
            raise ValueError(f"public/plugin tool {manifest.name} requires an explicit scope")
        if check_modules and importlib.util.find_spec(manifest.module) is None:
            raise ValueError(f"plugin {manifest.name} module is unavailable")
    return tuple(sorted(values, key=lambda manifest: manifest.name))
