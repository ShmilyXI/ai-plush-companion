import ast
from pathlib import Path


ROOT = Path(__file__).parents[1]


def load_method(path, class_name, method_name):
    tree = ast.parse(path.read_text(encoding="utf-8"))
    class_node = next(
        node for node in tree.body
        if isinstance(node, ast.ClassDef) and node.name == class_name
    )
    method_node = next(
        node for node in class_node.body
        if isinstance(node, ast.FunctionDef) and node.name == method_name
    )
    namespace = {"time": __import__("time"), "threading": __import__("threading")}
    exec(
        compile(ast.fix_missing_locations(ast.Module([method_node], [])), str(path), "exec"),
        namespace,
    )
    return namespace[method_name]


class CapturingConnection:
    def __init__(self):
        self.events = []

    def emit_debug_event(self, *args, **kwargs):
        self.events.append((args, kwargs))
        return True


def test_llm_first_visible_event_is_emitted_once():
    method = load_method(ROOT / "core/connection.py", "ConnectionHandler", "_emit_llm_first_visible")
    connection = CapturingConnection()
    connection._debug_lifecycle_lock = __import__("threading").Lock()
    connection._debug_llm_started_at = {"sentence": (0.0, None)}
    connection._debug_llm_first_visible = set()

    method(connection, "sentence", "你好")
    method(connection, "sentence", "第二段")

    assert [event[0][1] for event in connection.events] == ["llm.first_visible"]
    assert connection.events[0][1]["details"] == {"textLength": 2}


def test_tts_first_audio_event_is_emitted_once():
    method = load_method(ROOT / "core/providers/tts/base.py", "TTSProviderBase", "_emit_tts_first_audio")
    provider = type("Provider", (), {})()
    provider.conn = CapturingConnection()
    provider._debug_tts_lock = __import__("threading").Lock()
    provider._debug_tts_started_at = {"sentence": 0.0}
    provider._debug_tts_first_audio = set()

    method(provider, "sentence", b"abc")
    method(provider, "sentence", b"def")

    assert [event[0][1] for event in provider.conn.events] == ["tts.first_audio"]
    assert provider.conn.events[0][1]["details"] == {"audioBytes": 3}
