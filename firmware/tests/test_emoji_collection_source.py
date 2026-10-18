import json
from pathlib import Path

from firmware.scripts.build_default_assets import (
    get_board_default_emoji_collection,
    get_emoji_collection_path,
)


ROOT = Path(__file__).resolve().parents[1]

EMOTION_NAMES = [
    "neutral",
    "happy",
    "laughing",
    "funny",
    "sad",
    "angry",
    "crying",
    "loving",
    "embarrassed",
    "surprised",
    "shocked",
    "thinking",
    "winking",
    "cool",
    "relaxed",
    "delicious",
    "kissy",
    "confident",
    "sleepy",
    "silly",
    "confused",
]

MAINTAINED_BOARDS = ("zhengchen-cam", "bread-compact-wifi-s3cam")


def test_resolves_project_local_emoji_collection(tmp_path):
    collection = tmp_path / "resources" / "emoji" / "fluent_3d"
    collection.mkdir(parents=True)
    (collection / "happy.png").write_bytes(b"png")

    resolved = get_emoji_collection_path("fluent_3d", str(tmp_path / "fonts"), str(tmp_path))

    assert resolved == str(collection)


def test_project_local_collection_wins_over_fonts_dir(tmp_path):
    local = tmp_path / "resources" / "emoji" / "emo"
    local.mkdir(parents=True)
    (local / "happy.png").write_bytes(b"png")
    fonts_copy = tmp_path / "fonts" / "png" / "emo"
    fonts_copy.mkdir(parents=True)
    (fonts_copy / "happy.png").write_bytes(b"png")

    resolved = get_emoji_collection_path("emo", str(tmp_path / "fonts"), str(tmp_path))

    assert resolved == str(local)


def test_unknown_collection_returns_none(tmp_path):
    assert get_emoji_collection_path("missing", str(tmp_path / "fonts"), str(tmp_path)) is None


def test_otto_gif_collection_still_resolves(tmp_path):
    otto = tmp_path / "managed_components" / "txp666__otto-emoji-gif-component" / "gifs"
    otto.mkdir(parents=True)
    (otto / "happy.gif").write_bytes(b"gif")

    resolved = get_emoji_collection_path("otto-gif", str(tmp_path / "fonts"), str(tmp_path))

    assert resolved == str(otto)


def test_fonts_component_png_collection_still_resolves(tmp_path):
    png_dir = tmp_path / "fonts" / "png" / "twemoji_64"
    png_dir.mkdir(parents=True)
    (png_dir / "happy.png").write_bytes(b"png")

    resolved = get_emoji_collection_path("twemoji_64", str(tmp_path / "fonts"), str(tmp_path))

    assert resolved == str(png_dir)


def test_shipped_fluent_collection_contains_all_emotions():
    collection = ROOT / "resources" / "emoji" / "fluent_3d"
    for name in EMOTION_NAMES:
        assert (collection / f"{name}.png").exists(), f"missing {name}.png in fluent_3d"


def test_maintained_boards_declare_fluent_emoji_collection():
    for board in MAINTAINED_BOARDS:
        config = json.loads(
            (ROOT / "main" / "boards" / board / "config.json").read_text(encoding="utf-8")
        )
        for build in config["builds"]:
            assert build["assets"]["default_emoji_collection"] == "fluent_3d", (
                f"{board}/{build['name']} 未声明默认表情集"
            )


def test_board_default_emoji_collection_reads_board_config(tmp_path):
    board_dir = tmp_path / "main" / "boards" / "demo-board"
    board_dir.mkdir(parents=True)
    (board_dir / "config.json").write_text(
        json.dumps({"builds": [{"name": "demo-board", "assets": {"default_emoji_collection": "fluent_3d"}}]}),
        encoding="utf-8",
    )

    assert get_board_default_emoji_collection("demo-board", str(tmp_path)) == "fluent_3d"


def test_board_default_emoji_collection_missing_board_returns_none(tmp_path):
    assert get_board_default_emoji_collection("ghost", str(tmp_path)) is None
