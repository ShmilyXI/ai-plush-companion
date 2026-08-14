from typing import Any, Dict, Optional
from urllib.parse import urlsplit, urlunsplit


_ENDPOINT_FIELDS = ("base_url", "url", "ws_url", "api_url")
_MISSING = object()


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


def memory_query_details(
    query: Any,
    result: Any = _MISSING,
    diagnostics: Any = None,
) -> Dict[str, Any]:
    query_text = query if isinstance(query, str) else ""
    details: Dict[str, Any] = {"queryLength": len(query_text)}
    if result is _MISSING:
        return details

    result_text = result if isinstance(result, str) else ""
    details.update({
        "hit": bool(result_text),
        "resultLength": len(result_text),
    })
    if not isinstance(diagnostics, dict):
        return details

    request_id = diagnostics.get("request_id")
    if isinstance(request_id, str) and request_id.strip():
        details["requestId"] = request_id.strip()[:128]
    strategy = diagnostics.get("recall_strategy")
    if isinstance(strategy, str) and strategy.strip():
        details["recallStrategy"] = strategy.strip()[:128]
    layer_hits = diagnostics.get("layer_hits")
    if isinstance(layer_hits, dict):
        safe_hits = {
            layer: value
            for layer in ("L1", "L2", "L3")
            if isinstance((value := layer_hits.get(layer)), int)
            and not isinstance(value, bool)
            and value >= 0
        }
        if safe_hits:
            details["layerHits"] = safe_hits
    degraded = diagnostics.get("degraded_reason")
    if isinstance(degraded, str) and degraded.strip():
        details["degradedReason"] = degraded.strip()[:256]
    return details
