from typing import Any, Dict, Optional
from urllib.parse import urlsplit, urlunsplit


_ENDPOINT_FIELDS = ("base_url", "url", "ws_url", "api_url")


def safe_endpoint(value: Any) -> Optional[str]:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = urlsplit(value.strip())
    except ValueError:
        return None
    if parsed.scheme not in {"http", "https", "ws", "wss"} or not parsed.hostname:
        return None
    host = parsed.hostname
    if ":" in host and not host.startswith("["):
        host = f"[{host}]"
    try:
        port = f":{parsed.port}" if parsed.port is not None else ""
    except ValueError:
        return None
    return urlunsplit((parsed.scheme, f"{host}{port}", parsed.path or "", "", ""))


def module_details(config: Dict[str, Any], module_type: str) -> Dict[str, Any]:
    selected = config.get("selected_module", {}).get(module_type)
    if not isinstance(selected, str) or not selected:
        return {}
    module_config = config.get(module_type, {}).get(selected, {})
    if not isinstance(module_config, dict) or not module_config:
        return {}
    details: Dict[str, Any] = {"module": selected}
    provider = module_config.get("type", selected)
    if isinstance(provider, str) and provider:
        details["provider"] = provider
    if module_type in {"LLM", "VLLM"}:
        model = module_config.get("model_name") or module_config.get("model")
        if isinstance(model, str) and model:
            details["model"] = model
    if module_type == "TTS":
        speaker = module_config.get("speaker") or module_config.get("voice")
        if isinstance(speaker, str) and speaker:
            details["speaker"] = speaker
    for field in _ENDPOINT_FIELDS:
        endpoint = safe_endpoint(module_config.get(field))
        if endpoint:
            details["endpoint"] = endpoint
            break
    return details
