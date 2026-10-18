import os
import asyncio
import yaml
from dataclasses import dataclass
from collections.abc import Mapping
from typing import Any
from config.config_schema import validate_config
from config.config_sources import ConfigSource, resolve_config
from config.manage_api_client import (
    init_service,
    get_server_config,
    get_agent_models,
    get_correct_words,
    DeviceNotFoundException,
    DeviceBindException,
)


@dataclass(frozen=True)
class ConfigResolution:
    effective_config: Mapping[str, Any]
    source_map: Mapping[str, str]


# Exposed for runtime diagnostics without changing the long-standing dict API.
effective_config: Mapping[str, Any] = {}
source_map: Mapping[str, str] = {}


def get_project_dir():
    """获取项目根目录"""
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__))) + "/"


def read_config(config_path):
    with open(config_path, "r", encoding="utf-8") as file:
        config = yaml.safe_load(file)
    return config


async def load_config():
    """加载配置文件"""
    global effective_config, source_map
    from core.utils.cache.manager import cache_manager, CacheType

    # 检查缓存
    cached_config = cache_manager.get(CacheType.CONFIG, "main_config")
    if cached_config is not None:
        return cached_config

    default_config_path = get_project_dir() + "config.yaml"
    custom_config_path = get_project_dir() + "data/.config.yaml"

    # 加载默认配置
    default_config = read_config(default_config_path) or {}
    custom_config = read_config(custom_config_path) or {}

    if custom_config.get("manager-api", {}).get("url"):
        api_config = await get_config_from_api_async(custom_config)
        config, fields = resolve_config(
            [
                # The manager API owns deployment/provider values. Keep only
                # local safe fallbacks that the API contract does not carry.
                ConfigSource(
                    "built_in_defaults",
                    0,
                    {
                        "companion": default_config.get("companion", {}),
                        "prompt_template": default_config.get("prompt_template"),
                    },
                ),
                ConfigSource("local_override", 10, custom_config),
                ConfigSource("manager_api_server", 20, api_config),
            ]
        )
    else:
        config, fields = resolve_config(
            [
                ConfigSource("built_in_defaults", 0, default_config),
                ConfigSource("local_override", 10, custom_config),
            ]
        )
    validate_config(config)
    effective_config = config
    source_map = fields
    # 初始化目录
    ensure_directories(config)

    # 缓存配置
    cache_manager.set(CacheType.CONFIG, "main_config", config)
    return config


async def get_config_from_api_async(config):
    """从Java API获取配置（异步版本）"""
    # 初始化API客户端
    init_service(config)

    # 获取服务器配置
    config_data = await get_server_config()
    if config_data is None:
        raise Exception("Failed to fetch server config from API")

    config_data = dict(config_data)
    config_data["read_config_from_api"] = True
    config_data["manager-api"] = dict(config.get("manager-api", {}))
    # The resolver applies the explicit local transport protection rule.
    effective, _ = resolve_config(
        [
            ConfigSource("local_override", 10, config),
            ConfigSource("manager_api_server", 20, config_data),
        ]
    )
    if not effective.get("prompt_template"):
        effective["prompt_template"] = config.get("prompt_template")
    return effective


async def get_private_config_from_api(config, device_id, client_id):
    """从Java API获取私有配置"""
    results = await asyncio.gather(
        get_agent_models(device_id, client_id, config["selected_module"]),
        get_correct_words(device_id),
        return_exceptions=True,
    )
    agent_result = results[0]
    correct_words = results[1] if not isinstance(results[1], Exception) else None

    # 抛出业务异常
    if isinstance(agent_result, DeviceNotFoundException):
        raise agent_result
    if isinstance(agent_result, DeviceBindException):
        raise agent_result

    private_config = agent_result if not isinstance(agent_result, Exception) else {}
    if correct_words:
        private_config["correct_words"] = correct_words
    return private_config


def ensure_directories(config):
    """确保所有配置路径存在"""
    dirs_to_create = set()
    project_dir = get_project_dir()  # 获取项目根目录
    # 日志文件目录
    log_dir = config.get("log", {}).get("log_dir", "tmp")
    dirs_to_create.add(os.path.join(project_dir, log_dir))

    # ASR/TTS模块输出目录
    for module in ["ASR", "TTS"]:
        if config.get(module) is None:
            continue
        for provider in config.get(module, {}).values():
            output_dir = provider.get("output_dir", "")
            if output_dir:
                dirs_to_create.add(output_dir)

    # 根据selected_module创建模型目录
    selected_modules = config.get("selected_module", {})
    for module_type in ["ASR", "LLM", "TTS"]:
        selected_provider = selected_modules.get(module_type)
        if not selected_provider:
            continue
        if config.get(module_type) is None:
            continue
        if config.get(selected_provider) is None:
            continue
        provider_config = config.get(module_type, {}).get(selected_provider, {})
        output_dir = provider_config.get("output_dir")
        if output_dir:
            full_model_dir = os.path.join(project_dir, output_dir)
            dirs_to_create.add(full_model_dir)

    # 统一创建目录（保留原data目录创建）
    for dir_path in dirs_to_create:
        try:
            os.makedirs(dir_path, exist_ok=True)
        except PermissionError:
            print(f"警告：无法创建目录 {dir_path}，请检查写入权限")


def merge_configs(default_config, custom_config):
    """
    递归合并配置，custom_config优先级更高

    Args:
        default_config: 默认配置
        custom_config: 用户自定义配置

    Returns:
        合并后的配置
    """
    if not isinstance(default_config, Mapping) or not isinstance(
        custom_config, Mapping
    ):
        return custom_config

    merged = dict(default_config)

    for key, value in custom_config.items():
        if (
            key in merged
            and isinstance(merged[key], Mapping)
            and isinstance(value, Mapping)
        ):
            merged[key] = merge_configs(merged[key], value)
        else:
            merged[key] = value

    return merged
