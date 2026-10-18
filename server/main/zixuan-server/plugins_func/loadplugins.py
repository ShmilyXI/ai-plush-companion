import importlib
from config.logger import setup_logging
from plugins_func.manifest import BUILTIN_MANIFEST, PluginManifest, validate_manifests
from plugins_func.register import FunctionRegistry, all_function_registry

TAG = __name__

logger = setup_logging()

def auto_import_modules(package_name):
    """Compatibility entry point that now imports the explicit built-in list."""
    if package_name != "plugins_func.functions":
        raise ValueError(f"unsupported plugin package: {package_name}")
    return load_plugin_registry()


def load_plugin_registry(
    manifests=None,
    *,
    enabled_names=None,
    required_names=None,
    registry: FunctionRegistry | None = None,
):
    selected = validate_manifests(
        BUILTIN_MANIFEST if manifests is None else manifests,
        check_modules=manifests is not None,
    )
    enabled = set(enabled_names) if enabled_names is not None else {item.name for item in selected}
    required = set(required_names or ())
    target = registry or FunctionRegistry()
    for manifest in selected:
        if manifest.name not in enabled and manifest.name not in required:
            continue
        try:
            importlib.import_module(manifest.module)
        except Exception as exc:
            if manifest.required or manifest.name in required:
                raise RuntimeError(f"failed to load required plugin {manifest.name}") from exc
            logger.bind(tag=TAG).warning(f"plugin {manifest.name} unavailable: {type(exc).__name__}")
            continue
        item = all_function_registry.get(manifest.name)
        if item is None:
            if manifest.required or manifest.name in required:
                raise RuntimeError(f"plugin {manifest.name} did not register")
            continue
        target.register_function(manifest.name, item)
    return target
