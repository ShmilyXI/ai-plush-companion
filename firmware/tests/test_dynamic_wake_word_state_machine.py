from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def source(name):
    return (ROOT / "main" / name).read_text(encoding="utf-8")


def test_runtime_uses_two_slots_and_commits_only_after_validation():
    header = source("wake_word_assets.h")
    implementation = source("wake_word_assets.cc")
    assets = source("assets.cc")
    custom_header = source("audio/wake_words/custom_wake_word.h")
    custom_implementation = source("audio/wake_words/custom_wake_word.cc")
    audio_header = source("audio/audio_service.h")
    audio_implementation = source("audio/audio_service.cc")
    application = source("application.cc")

    assert "kSlotCount = 2" in header
    assert "inactive_slot" in implementation
    assert "esp_partition_erase_range" in implementation
    assert "esp_partition_write" in implementation
    assert "status_code != 200" in implementation
    assert "content_length" in implementation
    assert "mbedtls_sha256_update" in implementation
    assert '"XZWK"' in implementation
    assert "WakeSlotHeader provisional_header" not in implementation
    download = implementation[implementation.index("bool WakeWordAssets::DownloadPending"):implementation.index("bool WakeWordAssets::ActivateCandidate")]
    assert download.index("mbedtls_sha256_finish") < download.index("&header, sizeof(header)")
    assert "kLayoutVersion = 2" in header
    assert '"esp32s3"' in implementation
    assert '"index.json"' in implementation
    assert '"srmodels.bin"' in implementation
    assert '"wake"' in implementation
    assert implementation.index("ValidateConfiguration") < implementation.index('SetInt("active_slot"')
    assert 'SetString("error_code"' in implementation
    assert "WakeWordAssets::GetInstance().GetAssetData" in assets
    assert "ValidateConfiguration" in custom_header
    assert '"wake_word_bundle"' in custom_implementation
    assert "owns_models_" in custom_header
    assert "bool ValidateWakeWord()" in audio_header
    assert "wake_word_initialized_ = false" in audio_implementation
    assert application.index("DownloadPending") < application.index("ActivateCandidate")
    assert application.index("ReleaseSrmodels") < application.index("ActivateCandidate")
    assert application.index("ActivateCandidate") < application.index("ValidateWakeWord")
    assert application.index("ReleaseSrmodels") < application.index("RollbackCandidate")
    assert "RollbackCandidate" in application


def test_legacy_asset_download_remains_available():
    assert "bool Assets::Download" in source("assets.cc")
