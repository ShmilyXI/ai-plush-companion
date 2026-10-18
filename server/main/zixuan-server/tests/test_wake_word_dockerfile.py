from pathlib import Path


def test_server_image_initializes_local_server_package_before_importing_shared_wake_word_code():
    dockerfile = Path(__file__).resolve().parents[3] / "Dockerfile-server"
    content = dockerfile.read_text(encoding="utf-8")

    assert "touch ./server/__init__.py ./server/main/__init__.py" in content


def test_server_image_contains_the_fst_model_required_by_multinet():
    dockerfile = Path(__file__).resolve().parents[3] / "Dockerfile-server"
    content = dockerfile.read_text(encoding="utf-8")

    assert "test -d ./models/wake_word/fst" in content
    assert "manager-web" not in content
