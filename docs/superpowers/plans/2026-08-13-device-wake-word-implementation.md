# Per-Device Dynamic Wake Word Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow an eligible ESP32-S3 device to accept a two-to-eight-character Chinese wake word in the companion console, generate its Multinet resource automatically, download it into a rollback-safe A/B slot, reboot once, and report the actually active word back to the server.

**Architecture:** Manager API owns the per-device desired/active state machine and immutable resource files. The existing Python `xiaozhi-server` HTTP process exposes an authenticated internal generator endpoint and uses the repository's shared assets packer plus `pypinyin`; manager API then calls the existing device MCP channel. Firmware layout version 2 preserves normal theme assets at the front of the existing `assets` partition and reserves two fixed 3 MiB wake-word slots at its tail, switching the active slot through NVS only after download, SHA-256, container, index, chip, and Multinet validation succeed.

**Tech Stack:** Java 21, Spring Boot, MyBatis-Plus, Liquibase, MySQL, Redis, aiohttp, Python 3.10, pypinyin, pytest, React 19, TypeScript, Ant Design, Vitest, ESP-IDF C++, ESP-SR Multinet, NVS.

---

## File map and fixed contracts

The implementation uses one database row per device in `ai_device_wake_word`. `desired_version` is monotonically increasing; every mutating SQL statement includes both `device_id` and `desired_version`, so a stale worker cannot overwrite a newer request. The public state names are exactly `IDLE`, `GENERATING`, `WAITING_DEVICE`, `DOWNLOADING`, `WAITING_REBOOT`, `ACTIVE`, and `FAILED`.

The generated wake-word package remains an ordinary mmap `assets.bin`. It contains only `index.json`, `wake_word.json`, and `srmodels.bin`. `index.json` uses this exact shape:

```json
{
  "version": 1,
  "srmodels": "srmodels.bin",
  "wake_word_bundle": {
    "schema": 1,
    "version": 7,
    "word": "小布小布",
    "chip": "esp32s3",
    "model": "mn7_cn"
  },
  "multinet_model": {
    "language": "cn",
    "duration": 3000,
    "threshold": 0.2,
    "commands": [
      {
        "command": "xiao bu xiao bu",
        "text": "小布小布",
        "action": "wake"
      }
    ]
  }
}
```

The firmware MCP call is extended without adding a new tool name. `self.assets.set_download_url` receives `url`, `sha256`, `size`, `version`, and `word`. Old callers that only send `url` continue to use the legacy full-assets path; the dynamic path is selected only when all five values are present and firmware capability is true.

The firmware capability gate is exact: target `esp32s3`, assets partition size at least `0x800000`, layout version `2`, two slots of `0x300000`, and a valid base-assets image ending before the first slot. Existing devices with layout version `1` remain functional but report `supported=false`; they must receive a firmware plus factory-assets image built with layout version 2 before the console enables this feature.

### Task 1: Extract one shared assets packer and build deterministic wake packages

**Files:**
- Create: `server/main/shared/__init__.py`
- Create: `server/main/shared/wake_word_assets/__init__.py`
- Create: `server/main/shared/wake_word_assets/packer.py`
- Create: `server/main/shared/wake_word_assets/tests/test_packer.py`
- Modify: `firmware/scripts/build_default_assets.py`

- [ ] **Step 1: Write the failing shared-packer tests**

Create `server/main/shared/wake_word_assets/tests/test_packer.py` with deterministic mmap and model-pack assertions:

```python
import hashlib
import json
from pathlib import Path

from server.main.shared.wake_word_assets.packer import (
    parse_mmap_assets,
    pack_mmap_assets,
    pack_sr_models,
)


def test_pack_mmap_assets_is_deterministic_and_round_trips(tmp_path: Path):
    first = tmp_path / "first.bin"
    second = tmp_path / "second.bin"
    files = {
        "index.json": json.dumps({"version": 1}, separators=(",", ":")).encode(),
        "wake_word.json": b'{"word":"\xe5\xb0\x8f\xe5\xb8\x83\xe5\xb0\x8f\xe5\xb8\x83"}',
    }

    pack_mmap_assets(files, first)
    pack_mmap_assets(dict(reversed(list(files.items()))), second)

    assert first.read_bytes() == second.read_bytes()
    assert parse_mmap_assets(first.read_bytes()) == files
    assert hashlib.sha256(first.read_bytes()).hexdigest() == hashlib.sha256(second.read_bytes()).hexdigest()


def test_pack_sr_models_preserves_model_and_file_names(tmp_path: Path):
    model_dir = tmp_path / "mn7_cn"
    model_dir.mkdir()
    (model_dir / "mn7_data").write_bytes(b"data")
    (model_dir / "mn7_index").write_bytes(b"index")

    packed = pack_sr_models([model_dir])

    assert b"mn7_cn\x00" in packed
    assert b"mn7_data\x00" in packed
    assert b"mn7_index\x00" in packed
```

- [ ] **Step 2: Run the tests and confirm the module is missing**

Run from the repository root:

```bash
python -m pytest server/main/shared/wake_word_assets/tests/test_packer.py -v
```

Expected: collection fails with `ModuleNotFoundError: No module named 'server.main.shared.wake_word_assets'`.

- [ ] **Step 3: Move the existing binary-format code into the shared module**

Create `packer.py` with the existing `struct_pack_string`, model header construction, mmap table construction, checksum, `ZZ` prefix, and filename sort behavior taken from `firmware/scripts/build_default_assets.py`. Expose byte-oriented functions so both build-time firmware packaging and the HTTP generator call the same implementation:

```python
from __future__ import annotations

import io
import os
import struct
from pathlib import Path
from typing import Mapping, Sequence

MMAP_NAME_LENGTH = 32
MMAP_ENTRY_SIZE = 44


def _fixed_name(value: str, length: int = MMAP_NAME_LENGTH) -> bytes:
    encoded = value.encode("utf-8")
    if len(encoded) > length:
        raise ValueError(f"asset name exceeds {length} bytes: {value}")
    return encoded.ljust(length, b"\x00")


def _checksum(data: bytes) -> int:
    return sum(data) & 0xFFFF


def pack_sr_models(model_dirs: Sequence[Path]) -> bytes:
    models: dict[str, dict[str, bytes]] = {}
    for model_dir in sorted((Path(path) for path in model_dirs), key=lambda path: path.name):
        files = {
            path.name: path.read_bytes()
            for path in sorted(model_dir.iterdir(), key=lambda path: path.name)
            if path.is_file() and path.name != "srmodels.bin"
        }
        if not files:
            raise ValueError(f"model contains no files: {model_dir}")
        models[model_dir.name] = files

    file_count = sum(len(files) for files in models.values())
    header_length = 4 + len(models) * 36 + file_count * 40
    header = io.BytesIO()
    payload = io.BytesIO()
    header.write(struct.pack("<I", len(models)))
    for model_name, files in models.items():
        header.write(_fixed_name(model_name))
        header.write(struct.pack("<I", len(files)))
        for file_name, data in files.items():
            header.write(_fixed_name(file_name))
            header.write(struct.pack("<II", header_length + payload.tell(), len(data)))
            payload.write(data)
    return header.getvalue() + payload.getvalue()


def pack_mmap_assets(files: Mapping[str, bytes], output: Path) -> None:
    ordered = sorted(files.items(), key=lambda item: (Path(item[0]).suffix, Path(item[0]).stem))
    table = io.BytesIO()
    payload = io.BytesIO()
    for name, data in ordered:
        offset = payload.tell()
        payload.write(b"ZZ")
        payload.write(data)
        table.write(_fixed_name(name))
        table.write(struct.pack("<IIHH", len(data), offset, 0, 0))
    combined = table.getvalue() + payload.getvalue()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(struct.pack("<III", len(ordered), _checksum(combined), len(combined)) + combined)


def parse_mmap_assets(data: bytes) -> dict[str, bytes]:
    if len(data) < 12:
        raise ValueError("assets image is shorter than its header")
    file_count, expected_checksum, combined_length = struct.unpack_from("<III", data, 0)
    combined = data[12 : 12 + combined_length]
    if len(combined) != combined_length or _checksum(combined) != expected_checksum:
        raise ValueError("assets image checksum mismatch")
    payload_start = file_count * MMAP_ENTRY_SIZE
    result: dict[str, bytes] = {}
    for index in range(file_count):
        entry_offset = index * MMAP_ENTRY_SIZE
        raw_name, size, offset, _, _ = struct.unpack_from("<32sIIHH", combined, entry_offset)
        name = raw_name.split(b"\x00", 1)[0].decode("utf-8")
        start = payload_start + offset
        if combined[start : start + 2] != b"ZZ":
            raise ValueError(f"asset magic mismatch: {name}")
        result[name] = combined[start + 2 : start + 2 + size]
    return result
```

Change `build_default_assets.py` to import `pack_mmap_assets` and `pack_sr_models`, write their returned bytes, and delete the duplicated packer functions. Add this path bootstrap once near the imports:

```python
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

from server.main.shared.wake_word_assets.packer import pack_mmap_assets, pack_sr_models
```

- [ ] **Step 4: Run the packer and firmware-script tests**

```bash
python -m pytest server/main/shared/wake_word_assets/tests/test_packer.py -v
python -m py_compile firmware/scripts/build_default_assets.py server/main/shared/wake_word_assets/packer.py
```

Expected: both packer tests pass and both Python files compile without output.

- [ ] **Step 5: Commit the shared format boundary**

```bash
git add server/main/shared firmware/scripts/build_default_assets.py
git commit -m "refactor: share wake word asset packer"
```

### Task 2: Add the authenticated Python wake-word generator

**Files:**
- Create: `server/main/xiaozhi-server/core/wake_word/__init__.py`
- Create: `server/main/xiaozhi-server/core/wake_word/generator.py`
- Create: `server/main/xiaozhi-server/core/api/wake_word_assets_handler.py`
- Create: `server/main/xiaozhi-server/tests/test_wake_word_generator.py`
- Create: `server/main/xiaozhi-server/tests/test_wake_word_assets_handler.py`
- Modify: `server/main/xiaozhi-server/core/http_server.py`
- Modify: `server/main/xiaozhi-server/requirements.txt`
- Modify: `server/Dockerfile-server`

- [ ] **Step 1: Write failing tests for validation, pinyin, package metadata, hash, and authentication**

Create generator tests with the production model directory replaced by a tiny temporary model:

```python
import hashlib
import json
from pathlib import Path

import pytest

from core.wake_word.generator import WakeWordAssetGenerator, WakeWordRequest
from server.main.shared.wake_word_assets.packer import parse_mmap_assets


def model_dir(tmp_path: Path) -> Path:
    path = tmp_path / "mn7_cn"
    path.mkdir()
    (path / "mn7_data").write_bytes(b"model-data")
    (path / "mn7_index").write_bytes(b"model-index")
    (path / "_MODEL_INFO_").write_bytes(b"model-info")
    return path


def test_generator_converts_phrase_pinyin_and_emits_versioned_metadata(tmp_path: Path):
    result = WakeWordAssetGenerator(model_dir(tmp_path)).generate(
        WakeWordRequest(device_id="device-1", word=" 小布小布 ", version=7, chip="esp32s3", slot_size=0x300000)
    )
    files = parse_mmap_assets(result.content)
    index = json.loads(files["index.json"])

    assert index["multinet_model"]["commands"] == [
        {"command": "xiao bu xiao bu", "text": "小布小布", "action": "wake"}
    ]
    assert index["wake_word_bundle"] == {
        "schema": 1, "version": 7, "word": "小布小布", "chip": "esp32s3", "model": "mn7_cn"
    }
    assert json.loads(files["wake_word.json"])["version"] == 7
    assert result.sha256 == hashlib.sha256(result.content).hexdigest()
    assert result.size == len(result.content)


@pytest.mark.parametrize("word", ["小", "一二三四五六七八九", "hello", "小布 hello", "小布\n"])
def test_generator_rejects_unsupported_words(tmp_path: Path, word: str):
    with pytest.raises(ValueError, match="two to eight Chinese characters"):
        WakeWordAssetGenerator(model_dir(tmp_path)).generate(
            WakeWordRequest(device_id="device-1", word=word, version=1, chip="esp32s3", slot_size=0x300000)
        )


def test_generator_rejects_wrong_chip_and_oversized_package(tmp_path: Path):
    generator = WakeWordAssetGenerator(model_dir(tmp_path))
    with pytest.raises(ValueError, match="unsupported chip"):
        generator.generate(WakeWordRequest("device-1", "小布小布", 1, "esp32c3", 0x300000))
    with pytest.raises(ValueError, match="slot size"):
        generator.generate(WakeWordRequest("device-1", "小布小布", 1, "esp32s3", 64))
```

Create handler tests using `make_mocked_request` and a fake generator. Assert missing bearer auth is `401`, invalid JSON is `400`, and success returns bytes plus `X-Wake-Word-Sha256`, `X-Wake-Word-Size`, and `X-Wake-Word-Version`.

- [ ] **Step 2: Run the tests and confirm the generator modules are missing**

```bash
PYTHONPATH=server/main/xiaozhi-server:. python -m pytest \
  server/main/xiaozhi-server/tests/test_wake_word_generator.py \
  server/main/xiaozhi-server/tests/test_wake_word_assets_handler.py -v
```

Expected: test collection fails on imports from `core.wake_word` and `core.api.wake_word_assets_handler`.

- [ ] **Step 3: Implement deterministic phrase conversion and generation**

Use `pypinyin.lazy_pinyin` with phrase dictionaries enabled and no heteronym expansion. Validate before conversion, normalize surrounding whitespace only, and reject output containing anything outside lowercase ASCII syllables:

```python
from dataclasses import dataclass
from hashlib import sha256
import json
import re
import tempfile
from pathlib import Path

from pypinyin import Style, lazy_pinyin

from server.main.shared.wake_word_assets.packer import pack_mmap_assets, pack_sr_models

CHINESE_WORD = re.compile(r"^[\u3400-\u4dbf\u4e00-\u9fff]{2,8}$")


@dataclass(frozen=True)
class WakeWordRequest:
    device_id: str
    word: str
    version: int
    chip: str
    slot_size: int


@dataclass(frozen=True)
class WakeWordBuild:
    content: bytes
    sha256: str
    size: int
    word: str
    version: int


class WakeWordAssetGenerator:
    def __init__(self, model_dir: Path):
        self.model_dir = Path(model_dir)

    def generate(self, request: WakeWordRequest) -> WakeWordBuild:
        word = request.word.strip()
        if not CHINESE_WORD.fullmatch(word):
            raise ValueError("wake word must contain two to eight Chinese characters")
        if request.chip != "esp32s3":
            raise ValueError("unsupported chip")
        command = " ".join(lazy_pinyin(word, style=Style.NORMAL, heteronym=False, errors="strict"))
        if not re.fullmatch(r"[a-z]+(?: [a-z]+)*", command):
            raise ValueError("wake word pinyin is unsupported")
        metadata = {"schema": 1, "version": request.version, "word": word, "chip": request.chip, "model": "mn7_cn"}
        index = {
            "version": 1,
            "srmodels": "srmodels.bin",
            "wake_word_bundle": metadata,
            "multinet_model": {
                "language": "cn",
                "duration": 3000,
                "threshold": 0.2,
                "commands": [{"command": command, "text": word, "action": "wake"}],
            },
        }
        with tempfile.TemporaryDirectory(prefix="wake-word-") as directory:
            output = Path(directory) / "assets.bin"
            files = {
                "index.json": json.dumps(index, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
                "wake_word.json": json.dumps(metadata, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
                "srmodels.bin": pack_sr_models([self.model_dir]),
            }
            pack_mmap_assets(files, output)
            content = output.read_bytes()
        if len(content) > request.slot_size - 4096:
            raise ValueError("generated package exceeds slot size")
        return WakeWordBuild(content, sha256(content).hexdigest(), len(content), word, request.version)
```

Pin `pypinyin==0.54.0` in `requirements.txt`.

- [ ] **Step 4: Implement and register the internal binary endpoint**

`WakeWordAssetsHandler.handle_post` authenticates exactly like `DeviceControlHandler`, validates integer fields without accepting booleans, calls the generator through `asyncio.to_thread`, and returns `application/octet-stream`. Register `POST /internal/wake-word-assets` in `SimpleHttpServer.create_app`.

Use this production model path:

```python
model_dir = Path(__file__).resolve().parents[2] / "models" / "wake_word" / "mn7_cn"
```

Update `server/Dockerfile-server` so the server image contains both the shared packer and the existing Multinet files:

```dockerfile
COPY main/xiaozhi-server .
COPY main/shared ./server/main/shared
COPY main/manager-web/public/generator/static/multinet_model/mn7_cn ./models/wake_word/mn7_cn
```

- [ ] **Step 5: Run focused tests and dependency compilation**

```bash
PYTHONPATH=server/main/xiaozhi-server:. python -m pytest \
  server/main/shared/wake_word_assets/tests/test_packer.py \
  server/main/xiaozhi-server/tests/test_wake_word_generator.py \
  server/main/xiaozhi-server/tests/test_wake_word_assets_handler.py -v
python -m py_compile \
  server/main/xiaozhi-server/core/wake_word/generator.py \
  server/main/xiaozhi-server/core/api/wake_word_assets_handler.py \
  server/main/xiaozhi-server/core/http_server.py
```

Expected: all focused tests pass and compilation produces no output.

- [ ] **Step 6: Commit the generator service**

```bash
git add server/main/shared server/main/xiaozhi-server server/Dockerfile-server
git commit -m "feat: generate dynamic wake word assets"
```

### Task 3: Add the database state machine and immutable asset store

**Files:**
- Create: `server/main/manager-api/src/main/resources/db/changelog/202608132300.sql`
- Create: `server/main/manager-api/src/main/resources/db/changelog/202608132300-rollback.sql`
- Modify: `server/main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/wakeword/entity/DeviceWakeWordEntity.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/wakeword/dao/DeviceWakeWordDao.java`
- Create: `server/main/manager-api/src/main/resources/mapper/companion/DeviceWakeWordDao.xml`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/wakeword/service/WakeWordAssetStore.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/wakeword/service/impl/FileWakeWordAssetStore.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/wakeword/FileWakeWordAssetStoreTest.java`

- [ ] **Step 1: Write the failing asset-store test**

Test that the same `(deviceId, version, sha256)` returns the same path, different bytes cannot replace it, path traversal is rejected, and the final filename is `deviceId-version-sha256.bin` beneath a temporary root. Use `@TempDir` and instantiate `FileWakeWordAssetStore` with the temporary root.

- [ ] **Step 2: Run the focused Java test and confirm the class is missing**

```bash
cd server/main/manager-api
mvn -Dtest=FileWakeWordAssetStoreTest test
```

Expected: compilation fails because `FileWakeWordAssetStore` does not exist.

- [ ] **Step 3: Add the Liquibase table and rollback**

Create this table, including worker lease fields so multiple manager-api replicas cannot execute one device simultaneously:

```sql
-- liquibase formatted sql
-- changeset Codex:202608132300
CREATE TABLE `ai_device_wake_word` (
  `device_id` VARCHAR(32) NOT NULL,
  `desired_word` VARCHAR(32) DEFAULT NULL,
  `desired_version` BIGINT NOT NULL DEFAULT 0,
  `active_word` VARCHAR(32) DEFAULT NULL,
  `active_version` BIGINT NOT NULL DEFAULT 0,
  `candidate_path` VARCHAR(512) DEFAULT NULL,
  `candidate_token` VARCHAR(64) DEFAULT NULL,
  `candidate_sha256` CHAR(64) DEFAULT NULL,
  `candidate_size` BIGINT DEFAULT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'IDLE',
  `last_error_code` VARCHAR(64) DEFAULT NULL,
  `last_error_message` VARCHAR(512) DEFAULT NULL,
  `capable` TINYINT NOT NULL DEFAULT 0,
  `capability_reason` VARCHAR(128) DEFAULT NULL,
  `chip_model` VARCHAR(32) DEFAULT NULL,
  `assets_partition_size` BIGINT DEFAULT NULL,
  `layout_version` INT DEFAULT NULL,
  `slot_size` BIGINT DEFAULT NULL,
  `lock_token` VARCHAR(64) DEFAULT NULL,
  `lock_until` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`device_id`),
  UNIQUE KEY `uk_device_wake_word_candidate_token` (`candidate_token`),
  CONSTRAINT `fk_device_wake_word_device` FOREIGN KEY (`device_id`) REFERENCES `ai_device` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Rollback is exactly:

```sql
DROP TABLE IF EXISTS `ai_device_wake_word`;
```

Append one master changeSet with `id: 202608132300`, `author: Codex`, forward SQL, and rollback SQL.

- [ ] **Step 4: Implement the entity, conditional DAO operations, and immutable store**

The entity constants are uppercase public states. The DAO must expose these exact conditional operations:

```java
int updateIfVersion(@Param("deviceId") String deviceId,
                    @Param("desiredVersion") long desiredVersion,
                    @Param("status") String status,
                    @Param("values") Map<String, Object> values);

int claim(@Param("deviceId") String deviceId,
          @Param("desiredVersion") long desiredVersion,
          @Param("lockToken") String lockToken,
          @Param("lockUntil") Date lockUntil);

int release(@Param("deviceId") String deviceId,
            @Param("desiredVersion") long desiredVersion,
            @Param("lockToken") String lockToken);
```

Put the corresponding SQL in `DeviceWakeWordDao.xml`. `updateIfVersion` uses a fixed `<set>` whose columns are individually guarded by known map keys; never interpolate a map key into SQL. `claim` succeeds only when `lock_until IS NULL OR lock_until < NOW()`. `release` clears the lease only when all three identifiers match.

`FileWakeWordAssetStore.store` computes SHA-256 itself, compares it with the generator header, sanitizes `deviceId` to `[A-Za-z0-9._-]`, creates `uploadfile/wake-word`, writes with `CREATE_NEW`, and if the file already exists verifies identical bytes instead of overwriting it. `resolve` uses `toRealPath`, `NOFOLLOW_LINKS`, and the same managed-root containment rule as `OtaServiceImpl`.

- [ ] **Step 5: Run the store test and compile the Liquibase resources**

```bash
cd server/main/manager-api
mvn -Dtest=FileWakeWordAssetStoreTest test
mvn -DskipTests package
```

Expected: the focused test passes and the application package builds with the new changelog.

- [ ] **Step 6: Commit the persistence layer**

```bash
git add \
  server/main/manager-api/src/main/resources/db/changelog/202608132300.sql \
  server/main/manager-api/src/main/resources/db/changelog/202608132300-rollback.sql \
  server/main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/wakeword \
  server/main/manager-api/src/main/resources/mapper/companion/DeviceWakeWordDao.xml \
  server/main/manager-api/src/test/java/xiaozhi/modules/companion/wakeword/FileWakeWordAssetStoreTest.java
git commit -m "feat: persist per-device wake word state"
```

### Task 4: Add owner-scoped wake-word APIs, validation, idempotency, and retry

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/wakeword/dto/DeviceWakeWordUpdateDTO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/wakeword/vo/DeviceWakeWordVO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/wakeword/service/DeviceWakeWordService.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/wakeword/service/impl/DeviceWakeWordServiceImpl.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/controller/CompanionDeviceController.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/wakeword/DeviceWakeWordServiceImplTest.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/wakeword/DeviceWakeWordControllerTest.java`

- [ ] **Step 1: Write failing service tests for the public contract**

Cover these exact cases with Mockito: surrounding whitespace becomes `小布小布`; one or nine Chinese characters fail; ASCII and mixed input fail; a non-owner gets `NO_PERMISSION`; an incapable device gets `设备固件暂不支持动态唤醒词`; submitting the current `desired_word` returns the row unchanged; a new word increments `desired_version` once, clears candidate/error fields, sets `GENERATING`, and clears any lease; retry is accepted only from `FAILED`, keeps word and version, and chooses `GENERATING` when no valid candidate exists or `WAITING_DEVICE` when candidate path/hash/size exist.

- [ ] **Step 2: Run focused tests and confirm the service/controller methods are absent**

```bash
cd server/main/manager-api
mvn -Dtest=DeviceWakeWordServiceImplTest,DeviceWakeWordControllerTest test
```

Expected: test compilation fails on the new DTO, VO, service, and controller routes.

- [ ] **Step 3: Implement the transactional service**

Use one regex constant and one normalization method:

```java
private static final Pattern SUPPORTED_WORD = Pattern.compile("^[\\x{3400}-\\x{4DBF}\\x{4E00}-\\x{9FFF}]{2,8}$");

static String normalizeWord(String raw) {
    String value = raw == null ? "" : raw.strip();
    if (!SUPPORTED_WORD.matcher(value).matches()) {
        throw new RenException("唤醒词只支持二到八个中文汉字");
    }
    return value;
}
```

Lock the owned `ai_device` row with `DeviceDao.selectOwnedByIdForUpdate`, then lock or insert its wake-word row. Do not create a new version for identical desired text. Return `DeviceWakeWordVO` with these exact fields:

```java
public class DeviceWakeWordVO {
    private String desiredWord;
    private long desiredVersion;
    private String activeWord;
    private long activeVersion;
    private String status;
    private String lastErrorCode;
    private String lastErrorMessage;
    private boolean supported;
    private String unsupportedReason;
    private Date updatedAt;
}
```

- [ ] **Step 4: Add the three owner-scoped routes**

Add these methods to `CompanionDeviceController`, all with `sys:role:normal`:

```java
@GetMapping("/{id}/wake-word")
public Result<DeviceWakeWordVO> getWakeWord(@PathVariable String id) {
    return new Result<DeviceWakeWordVO>().ok(wakeWordService.get(SecurityUser.getUserId(), id));
}

@PutMapping("/{id}/wake-word")
public Result<DeviceWakeWordVO> updateWakeWord(@PathVariable String id,
        @RequestBody @Valid DeviceWakeWordUpdateDTO dto) {
    return new Result<DeviceWakeWordVO>().ok(wakeWordService.update(SecurityUser.getUserId(), id, dto.getWord()));
}

@PostMapping("/{id}/wake-word/retry")
public Result<DeviceWakeWordVO> retryWakeWord(@PathVariable String id) {
    return new Result<DeviceWakeWordVO>().ok(wakeWordService.retry(SecurityUser.getUserId(), id));
}
```

- [ ] **Step 5: Run service and controller tests**

```bash
cd server/main/manager-api
mvn -Dtest=DeviceWakeWordServiceImplTest,DeviceWakeWordControllerTest test
```

Expected: all validation, ownership, idempotency, version, and retry tests pass.

- [ ] **Step 6: Commit the API state transitions**

```bash
git add \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/controller/CompanionDeviceController.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/wakeword \
  server/main/manager-api/src/test/java/xiaozhi/modules/companion/wakeword
git commit -m "feat: expose per-device wake word settings"
```

### Task 5: Generate, store, download, and dispatch candidates from manager-api

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/wakeword/service/WakeWordGenerationClient.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/wakeword/service/impl/HttpWakeWordGenerationClient.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/wakeword/service/WakeWordUpdateWorker.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/wakeword/controller/WakeWordAssetDownloadController.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/wakeword/config/WakeWordTaskConfiguration.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/device/service/DeviceService.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/device/service/impl/DeviceServiceImpl.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/wakeword/HttpWakeWordGenerationClientTest.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/wakeword/WakeWordUpdateWorkerTest.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/wakeword/WakeWordAssetDownloadControllerTest.java`

- [ ] **Step 1: Write failing generator-client and worker tests**

Use `MockRestServiceServer` for a binary response. Assert bearer auth uses `server.secret`, the request body contains `device_id`, `word`, `version`, `chip`, and `slot_size`, a mismatched hash header fails, and valid bytes are returned with their verified hash.

Worker tests must cover: expired leases can be claimed; active leases cannot; generation success stores an immutable file and sets `WAITING_DEVICE`; offline devices remain `WAITING_DEVICE` without MCP calls; online devices receive `self.assets.set_download_url` with all five arguments, transition to `DOWNLOADING`, receive `self.reboot`, then transition to `WAITING_REBOOT`; generation or dispatch exceptions set `FAILED` only if the version and lease still match; a newer version makes the stale worker update count zero.

- [ ] **Step 2: Run focused tests and confirm the orchestration classes are missing**

```bash
cd server/main/manager-api
mvn -Dtest=HttpWakeWordGenerationClientTest,WakeWordUpdateWorkerTest,WakeWordAssetDownloadControllerTest test
```

Expected: compilation fails for the new client, worker, and download controller.

- [ ] **Step 3: Implement the authenticated generation client**

Read `server.http` and `server.secret` from `SysParamsService`, POST to `${server.http}/internal/wake-word-assets`, cap the accepted response at `0x300000 - 4096`, compute SHA-256 locally, and require exact agreement with all three response headers. Treat connection failures, non-2xx responses, missing headers, invalid version headers, and hash mismatches as generation failures.

- [ ] **Step 4: Expose immutable candidate downloads with an unguessable token**

The controller route is:

```java
@GetMapping("/wake-word-assets/{token}")
public ResponseEntity<Resource> download(@PathVariable String token)
```

Look up a row by `candidate_token`, accept only a regular managed file whose current SHA-256 equals `candidate_sha256`, and return `application/octet-stream`, `Content-Length`, and `Cache-Control: private, max-age=300, immutable`. Return `404` for unknown tokens, path escapes, or hash mismatches. Generate tokens with `UUID.randomUUID().toString().replace("-", "")` and never derive them from a device ID.

- [ ] **Step 5: Implement the leased worker and online check**

Expose `DeviceService.isOnline(String deviceId)` using the same last-connected 90-second window already used by `CompanionDeviceServiceImpl`; remove the duplicate private calculation by delegating to this method.

Create an `@Scheduled(fixedDelayString = "${xiaozhi.wake-word.worker-delay-ms:5000}")` worker. Each tick processes a bounded batch of ten rows. Lease for 60 seconds, generate only in `GENERATING`, dispatch only in `WAITING_DEVICE`, and release the lease in `finally` with device, version, and token conditions.

Build the download URL from `server.ota` by replacing `/ota/` with `/wake-word-assets/` and appending the candidate token. Dispatch arguments exactly as:

```java
Map.of(
    "url", downloadUrl,
    "sha256", row.getCandidateSha256(),
    "size", row.getCandidateSize(),
    "version", row.getDesiredVersion(),
    "word", row.getDesiredWord()
)
```

Treat `Boolean.FALSE`, a null response, or an exception from either MCP call as failure. The reboot call has an empty argument map.

- [ ] **Step 6: Run the orchestration tests**

```bash
cd server/main/manager-api
mvn -Dtest=HttpWakeWordGenerationClientTest,WakeWordUpdateWorkerTest,WakeWordAssetDownloadControllerTest test
```

Expected: generator verification, leases, offline wait, dispatch ordering, stale-version protection, and download containment all pass.

- [ ] **Step 7: Commit automatic generation and dispatch**

```bash
git add \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/wakeword \
  server/main/manager-api/src/main/java/xiaozhi/modules/device/service/DeviceService.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/device/service/impl/DeviceServiceImpl.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/service/impl/CompanionDeviceServiceImpl.java \
  server/main/manager-api/src/test/java/xiaozhi/modules/companion/wakeword
git commit -m "feat: dispatch wake word updates to devices"
```

### Task 6: Build firmware layout version 2 with base assets and two wake slots

**Files:**
- Modify: `firmware/scripts/build_default_assets.py`
- Modify: `firmware/main/CMakeLists.txt`
- Create: `firmware/tests/test_dynamic_wake_word_partition_image.py`

- [ ] **Step 1: Write a failing partition-image test**

Create a small base package and a small default wake package, invoke the new assembler, and assert: final image size equals the configured assets partition size; base starts at offset zero; slot A starts at `partition_size - 0x600000`; slot B starts at `partition_size - 0x300000`; slot A header contains magic `XZWK`, layout `2`, package size, version, and package SHA-256; slot B is erased `0xFF`; assembly rejects a base image crossing slot A and rejects a wake package larger than `0x300000 - 4096`.

- [ ] **Step 2: Run the firmware Python test and confirm the assembler is absent**

```bash
python -m pytest firmware/tests/test_dynamic_wake_word_partition_image.py -v
```

Expected: failure because `assemble_dynamic_wake_word_partition` is not defined.

- [ ] **Step 3: Add deterministic layout assembly to the existing build script**

Add these fixed constants and header format:

```python
DYNAMIC_WAKE_LAYOUT_VERSION = 2
WAKE_SLOT_SIZE = 0x300000
WAKE_SLOT_HEADER_SIZE = 0x1000
WAKE_SLOT_MAGIC = b"XZWK"
WAKE_SLOT_HEADER = struct.Struct("<4sIQQ32s")
```

`assemble_dynamic_wake_word_partition(base_image, wake_image, partition_size, version)` fills the output with `0xFF`, copies the base at zero, writes the header at slot A, writes the wake package at slot A plus `0x1000`, and leaves slot B erased. It raises `ValueError` before writing when any boundary is exceeded.

Add build arguments `--dynamic-wake-word-layout`, `--assets-partition-size`, and `--default-wake-word`. When enabled, build theme assets without `srmodels.bin`, generate an `mn7_cn` wake-only package using the same index contract from Task 2, and assemble the final partition image.

- [ ] **Step 4: Enable layout 2 only for eligible firmware builds**

In `firmware/main/CMakeLists.txt`, enable the new arguments only when all conditions are true:

```cmake
if(CONFIG_IDF_TARGET_ESP32S3 AND size GREATER_EQUAL 0x800000 AND CONFIG_USE_CUSTOM_WAKE_WORD)
    list(APPEND ASSETS_BUILD_ARGS
        --dynamic-wake-word-layout
        --assets-partition-size ${size}
        --default-wake-word "${CONFIG_CUSTOM_WAKE_WORD_DISPLAY}")
endif()
```

Continue generating the legacy single-image format for every other target and partition size.

- [ ] **Step 5: Run layout and existing firmware tests**

```bash
python -m pytest \
  firmware/tests/test_dynamic_wake_word_partition_image.py \
  firmware/tests/test_device_heartbeat.py -v
python -m py_compile firmware/scripts/build_default_assets.py
```

Expected: all tests pass and the script compiles.

- [ ] **Step 6: Commit the factory image layout**

```bash
git add firmware/scripts/build_default_assets.py firmware/main/CMakeLists.txt firmware/tests/test_dynamic_wake_word_partition_image.py
git commit -m "feat: reserve firmware wake word asset slots"
```

### Task 7: Implement firmware A/B download, validation, activation, and rollback

**Files:**
- Create: `firmware/main/wake_word_assets.h`
- Create: `firmware/main/wake_word_assets.cc`
- Modify: `firmware/main/assets.h`
- Modify: `firmware/main/assets.cc`
- Modify: `firmware/main/audio/wake_words/custom_wake_word.h`
- Modify: `firmware/main/audio/wake_words/custom_wake_word.cc`
- Modify: `firmware/main/audio/audio_service.h`
- Modify: `firmware/main/audio/audio_service.cc`
- Modify: `firmware/main/application.cc`
- Modify: `firmware/main/CMakeLists.txt`
- Create: `firmware/tests/test_dynamic_wake_word_state_machine.py`

- [ ] **Step 1: Write failing structural state-machine tests**

Following the repository's existing source-inspection test style, assert the new source defines two slots, maps only the chosen slot, writes only the inactive slot, erases sector-aligned ranges, checks HTTP 200 and content length, streams SHA-256 while writing, validates `XZWK`, layout `2`, chip `esp32s3`, `index.json`, one `wake` command, and `srmodels.bin`, and updates `active_slot` only after `CustomWakeWord::ValidateConfiguration` succeeds. Assert every failure leaves `active_slot` unchanged and records an error code. Assert legacy `Assets::Download` remains present for callers that only supply `url`.

- [ ] **Step 2: Run the test and confirm the new firmware class is absent**

```bash
python -m pytest firmware/tests/test_dynamic_wake_word_state_machine.py -v
```

Expected: failures for missing `wake_word_assets.h` and `wake_word_assets.cc`.

- [ ] **Step 3: Define the firmware state and NVS contract**

Create a singleton `WakeWordAssets` with these public methods:

```cpp
struct WakeWordCapability {
    bool supported;
    int layout_version;
    size_t slot_size;
    std::string reason;
};

class WakeWordAssets {
public:
    static WakeWordAssets& GetInstance();
    WakeWordCapability GetCapability() const;
    bool HasPendingDownload() const;
    bool DownloadPending(std::function<void(int, size_t)> progress_callback);
    bool ActivateCandidate();
    bool RollbackCandidate(const std::string& error_code, const std::string& error_message);
    bool GetAssetData(const std::string& name, void*& ptr, size_t& size);
    std::string GetStatusJson() const;
    void SetPending(const std::string& url, const std::string& sha256,
                    size_t size, int64_t version, const std::string& word);
};
```

Use NVS namespace `wake_word` and keys `active_slot`, `active_ver`, `active_word`, `pending_url`, `pending_sha`, `pending_size`, `pending_ver`, `pending_word`, `status`, `error_code`, and `error_msg`. Candidate metadata is never copied into active metadata until validation passes.

- [ ] **Step 4: Implement slot mapping and streamed download**

Compute slot A and B from the tail of the existing `assets` partition. Capability is false unless the target, partition size, base image boundary, and slot A header layout agree. Select the inactive slot, erase only its `0x300000` range, write a provisional header, stream the body at `slot_offset + 0x1000`, update `mbedtls_sha256_context`, require exact byte count, compare lowercase hex SHA-256, then write the final header. Do not unmap or modify the active slot or base assets during download.

- [ ] **Step 5: Validate the candidate through the actual CustomWakeWord configuration path**

Add a static validator which parses the candidate `index.json`, requires exactly one command with `action == "wake"`, requires its text and version to match NVS pending metadata, calls `srmodel_load`, finds an ESP Multinet model, creates its handle, applies threshold and duration, updates the command list, then destroys and deinitializes all temporary objects:

```cpp
static bool ValidateConfiguration(srmodel_list_t* models, const cJSON* index,
                                  std::string* error_code, std::string* error_message);
```

Only after this returns true should `ActivateCandidate` set active slot/version/word, clear pending fields, set status `active`, and remap the new slot. On validation failure, erase the candidate header, keep the previous mapping, set status `failed`, and retain the old active word.

- [ ] **Step 6: Make wake assets override only SR resources**

Keep normal theme, font, image, and emotion lookups in `Assets`. Change `LoadSrmodelsFromIndex` and `CustomWakeWord::ParseWakenetModelConfig` to ask `WakeWordAssets::GetAssetData` for dynamic `index.json` and `srmodels.bin` first, then fall back to the base-assets source. No other asset name uses the dynamic slots.

Reset `AudioService::wake_word_initialized_` when `SetModelsList` replaces models, and add `bool ValidateWakeWord()` that initializes the selected wake-word object once and returns its result. `Application::CheckAssetsVersion` calls `DownloadPending`, `ActivateCandidate`, `Assets::Apply`, and `ValidateWakeWord` in that order; a failure calls `WakeWordAssets::RollbackCandidate`, reapplies the previous models, and continues activation without rebooting again.

- [ ] **Step 7: Run firmware source tests and an ESP-IDF build for one eligible board**

```bash
python -m pytest \
  firmware/tests/test_dynamic_wake_word_state_machine.py \
  firmware/tests/test_dynamic_wake_word_partition_image.py \
  firmware/tests/test_device_heartbeat.py -v
cd firmware
idf.py build
```

Expected: Python tests pass; the configured ESP32-S3 target compiles; build logs show a layout-2 assets image and no partition overflow.

- [ ] **Step 8: Commit rollback-safe firmware activation**

```bash
git add \
  firmware/main/wake_word_assets.h \
  firmware/main/wake_word_assets.cc \
  firmware/main/assets.h \
  firmware/main/assets.cc \
  firmware/main/audio/wake_words/custom_wake_word.h \
  firmware/main/audio/wake_words/custom_wake_word.cc \
  firmware/main/audio/audio_service.h \
  firmware/main/audio/audio_service.cc \
  firmware/main/application.cc \
  firmware/main/CMakeLists.txt \
  firmware/tests/test_dynamic_wake_word_state_machine.py
git commit -m "feat: activate wake word assets with rollback"
```

### Task 8: Extend MCP arguments and device capability/version reporting

**Files:**
- Modify: `firmware/main/mcp_server.cc`
- Modify: `firmware/main/boards/common/board.cc`
- Modify: `firmware/main/device_heartbeat.cc`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/device/dto/DeviceReportReqDTO.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/device/service/DeviceService.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/device/service/impl/DeviceServiceImpl.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/device/controller/OTAController.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/wakeword/DeviceWakeWordReportTest.java`
- Create: `firmware/tests/test_dynamic_wake_word_reporting.py`

- [ ] **Step 1: Write failing report reconciliation tests**

Java tests cover these reports: supported layout updates capability fields; layout 1 sets incapable with reason; matching active version changes `WAITING_REBOOT` to `ACTIVE`, copies desired word/version to active fields, and clears errors; a lower version cannot mark a newer desired version active; `status=failed` for the matching pending version changes the row to `FAILED` without changing active word/version; a report for another authenticated device cannot update the row.

Firmware tests assert `Board::GetSystemInfoJson` emits a `wake_word` object and heartbeat sends the same JSON instead of `{}`.

- [ ] **Step 2: Run both test groups and confirm fields are absent**

```bash
python -m pytest firmware/tests/test_dynamic_wake_word_reporting.py -v
cd server/main/manager-api
mvn -Dtest=DeviceWakeWordReportTest test
```

Expected: failures for absent JSON and DTO fields.

- [ ] **Step 3: Extend the MCP tool while preserving legacy callers**

Declare `sha256`, `size`, `version`, and `word` as optional properties with safe defaults. In the callback, use the dynamic path only when all dynamic values are non-empty or positive and capability is supported:

```cpp
if (!sha256.empty() && size > 0 && version > 0 && !word.empty()) {
    WakeWordAssets::GetInstance().SetPending(url, sha256, size, version, word);
} else {
    Settings settings("assets", true);
    settings.SetString("download_url", url);
}
```

Reject a partially populated dynamic request instead of silently treating it as legacy.

- [ ] **Step 4: Add the report object and reconcile it by authenticated device ID**

Firmware emits:

```json
"wake_word": {
  "supported": true,
  "layout_version": 2,
  "slot_size": 3145728,
  "active_version": 7,
  "active_word": "小布小布",
  "pending_version": 0,
  "status": "active",
  "error_code": "",
  "error_message": ""
}
```

Add a nested `WakeWordInfo` DTO with matching `@JsonProperty` names. Reconcile inside `checkDeviceActive` after the device identity is resolved, in the same transaction used for connection info. Never use `mac_address` from the JSON body as the authority; use the validated `Device-Id` header and resolved device row. Change `OTAController.heartbeat` to accept an optional `DeviceReportReqDTO` body and call `deviceService.reportWakeWordState(deviceId, body.getWakeWord())` after `touchHeartbeat`; an empty body remains valid for legacy firmware and the response stays `204`.

Change `DeviceHeartbeat::Send` content to `board.GetSystemInfoJson()` so failures occurring after the initial OTA report are eventually reconciled too.

- [ ] **Step 5: Run report tests**

```bash
python -m pytest \
  firmware/tests/test_dynamic_wake_word_reporting.py \
  firmware/tests/test_device_heartbeat.py -v
cd server/main/manager-api
mvn -Dtest=DeviceWakeWordReportTest,DeviceServiceImplTest test
```

Expected: report authentication, version guards, active confirmation, failure preservation, and heartbeat payload tests pass.

- [ ] **Step 6: Commit reporting and reconciliation**

```bash
git add \
  firmware/main/mcp_server.cc \
  firmware/main/boards/common/board.cc \
  firmware/main/device_heartbeat.cc \
  firmware/tests/test_dynamic_wake_word_reporting.py \
  server/main/manager-api/src/main/java/xiaozhi/modules/device/dto/DeviceReportReqDTO.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/device/service/DeviceService.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/device/service/impl/DeviceServiceImpl.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/device/controller/OTAController.java \
  server/main/manager-api/src/test/java/xiaozhi/modules/companion/wakeword/DeviceWakeWordReportTest.java
git commit -m "feat: report active device wake word"
```

### Task 9: Synchronize the active word into each live server connection

**Files:**
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/config/service/ConfigService.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/config/service/impl/ConfigServiceImpl.java`
- Modify: `server/main/xiaozhi-server/core/connection.py`
- Modify: `server/main/xiaozhi-server/core/handle/textHandler/listenMessageHandler.py`
- Modify: `server/main/xiaozhi-server/core/handle/helloHandle.py`
- Create: `server/main/xiaozhi-server/tests/test_device_wake_word_config.py`
- Modify: `server/main/manager-api/src/test/java/xiaozhi/modules/device/service/impl/DeviceServiceImplTest.java`

- [ ] **Step 1: Write failing config and runtime tests**

Manager-api test: `getAgentModels` returns `device_wakeup_words` containing only the row's nonblank `active_word`; it never returns `desired_word`; a device without an active row gets an empty list.

Python tests: private config replaces only the connection-local wake-word list; active `小布小布` is recognized by both listen and hello handlers; another simultaneous connection keeps its own word; when the list is empty the global configured defaults remain available according to the existing global behavior.

- [ ] **Step 2: Run tests and confirm per-device words are not consumed**

```bash
cd server/main/manager-api
mvn -Dtest=DeviceServiceImplTest test
cd ../../..
PYTHONPATH=server/main/xiaozhi-server:. python -m pytest server/main/xiaozhi-server/tests/test_device_wake_word_config.py -v
```

Expected: assertions fail because `device_wakeup_words` is absent and connections still share only global `wakeup_words`.

- [ ] **Step 3: Return and apply active words only**

Inject the wake-word DAO/service into `ConfigServiceImpl`, expose `List<String> getDeviceWakeupWords(String macAddress)` from `ConfigService`, and add:

```java
result.put("device_wakeup_words", wakeWordService.activeWords(device.getId()));
```

In `_initialize_private_config_async`, copy the global list, then append distinct normalized device active words:

```python
device_words = private_config.get("device_wakeup_words")
if isinstance(device_words, list):
    global_words = list(self.common_config.get("wakeup_words", []))
    self.config["wakeup_words"] = list(dict.fromkeys(global_words + device_words))
```

Keep this on `self.config`; do not mutate `self.common_config` or another connection. Existing listen and hello handlers continue reading `conn.config.get("wakeup_words", [])`.

- [ ] **Step 4: Run manager and Python runtime tests**

```bash
cd server/main/manager-api
mvn -Dtest=DeviceServiceImplTest test
cd ../../..
PYTHONPATH=server/main/xiaozhi-server:. python -m pytest server/main/xiaozhi-server/tests/test_device_wake_word_config.py -v
```

Expected: active-only synchronization and connection isolation tests pass.

- [ ] **Step 5: Commit active-word synchronization**

```bash
git add \
  server/main/manager-api/src/main/java/xiaozhi/modules/config/service/ConfigService.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/config/service/impl/ConfigServiceImpl.java \
  server/main/manager-api/src/test/java/xiaozhi/modules/device/service/impl/DeviceServiceImplTest.java \
  server/main/xiaozhi-server/core/connection.py \
  server/main/xiaozhi-server/core/handle/textHandler/listenMessageHandler.py \
  server/main/xiaozhi-server/core/handle/helloHandle.py \
  server/main/xiaozhi-server/tests/test_device_wake_word_config.py
git commit -m "feat: use active wake word per device connection"
```

### Task 10: Add the device-detail wake-word UI

**Files:**
- Modify: `server/main/companion-console/src/api/devices.ts`
- Modify: `server/main/companion-console/src/api/devices.test.ts`
- Create: `server/main/companion-console/src/pages/devices/DeviceWakeWordCard.tsx`
- Create: `server/main/companion-console/src/pages/devices/DeviceWakeWordCard.test.tsx`
- Modify: `server/main/companion-console/src/pages/devices/DeviceDetailPage.tsx`
- Modify: `server/main/companion-console/src/pages/devices/DeviceDetailPage.test.tsx`

- [ ] **Step 1: Write failing API parser and card interaction tests**

Add API tests for GET, PUT, and retry paths with encoded device IDs. Reject malformed status values, versions, support flags, and non-string words through `ApiProtocolError`.

Card tests cover: unsupported devices show the backend reason and no input; supported devices show current active word and status; input accepts `小布小布`; submission trims whitespace and calls PUT; one, nine, ASCII, and mixed values show `只支持二到八个中文汉字` without a request; `GENERATING`, `WAITING_DEVICE`, `DOWNLOADING`, and `WAITING_REBOOT` disable duplicate submission and show their Chinese labels; `FAILED` shows the last error and retry button; retry calls the retry API; successful mutation replaces local state immediately.

- [ ] **Step 2: Run the focused frontend tests and confirm APIs/card are missing**

```bash
cd server/main/companion-console
npx vitest run \
  src/api/devices.test.ts \
  src/pages/devices/DeviceWakeWordCard.test.tsx \
  src/pages/devices/DeviceDetailPage.test.tsx
```

Expected: compilation fails for the new types, functions, and component.

- [ ] **Step 3: Add strict API types and parsers**

Use this exact public type:

```typescript
export type WakeWordStatus = 'IDLE' | 'GENERATING' | 'WAITING_DEVICE' | 'DOWNLOADING' | 'WAITING_REBOOT' | 'ACTIVE' | 'FAILED'

export interface DeviceWakeWordState {
  desiredWord: string | null
  desiredVersion: number
  activeWord: string | null
  activeVersion: number
  status: WakeWordStatus
  lastErrorCode: string | null
  lastErrorMessage: string | null
  supported: boolean
  unsupportedReason: string | null
  updatedAt: string | null
}
```

Export `getDeviceWakeWord`, `updateDeviceWakeWord`, and `retryDeviceWakeWord`; all use `encodedId(id)` and the existing `unwrap` protocol checks.

- [ ] **Step 4: Implement the card and mount it in the detail page**

`DeviceWakeWordCard` owns only wake-word state and mutation state. Fetch once on mount and after the device changes; do not tie it to the existing 30-second full-device poll. While a nonterminal status is visible, poll the wake-word GET every five seconds and stop on `ACTIVE`, `FAILED`, unmount, or device change.

Map statuses exactly:

```typescript
const statusText: Record<WakeWordStatus, string> = {
  IDLE: '未配置',
  GENERATING: '正在生成资源',
  WAITING_DEVICE: '等待设备上线',
  DOWNLOADING: '设备正在下载',
  WAITING_REBOOT: '等待设备重启确认',
  ACTIVE: '已生效',
  FAILED: '更新失败',
}
```

Render the card in `DeviceDetailPage` after basic device controls and before debug logs. Keep existing device-load error and polling behavior unchanged.

- [ ] **Step 5: Run frontend tests, lint, and build**

```bash
cd server/main/companion-console
npx vitest run \
  src/api/devices.test.ts \
  src/pages/devices/DeviceWakeWordCard.test.tsx \
  src/pages/devices/DeviceDetailPage.test.tsx
npm run lint
npm run build
```

Expected: focused tests pass, lint exits zero, and the production build completes.

- [ ] **Step 6: Commit the device UI**

```bash
git add \
  server/main/companion-console/src/api/devices.ts \
  server/main/companion-console/src/api/devices.test.ts \
  server/main/companion-console/src/pages/devices/DeviceWakeWordCard.tsx \
  server/main/companion-console/src/pages/devices/DeviceWakeWordCard.test.tsx \
  server/main/companion-console/src/pages/devices/DeviceDetailPage.tsx \
  server/main/companion-console/src/pages/devices/DeviceDetailPage.test.tsx
git commit -m "feat: configure wake word from device details"
```

### Task 11: Verify the whole workflow, deployment image, and hardware rollback cases

**Files:**
- Create: `docs/dynamic-device-wake-word.md`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/wakeword/DeviceWakeWordWorkflowTest.java`
- Modify: `server/docs/docker-build.md`

- [ ] **Step 1: Write a manager-api workflow test across the completed service boundaries**

The test uses fakes for generator, asset store, online status, MCP, and report reconciliation. It performs this exact sequence: capable report; PUT `小布小布`; worker generation; offline tick; online tick; MCP URL call; reboot call; matching active report; GET returns `ACTIVE`, active word `小布小布`, matching versions, and no error. A second scenario interrupts download and reports failure; GET returns `FAILED` while the prior active word and version remain unchanged; retry reuses the candidate and succeeds after a matching report.

- [ ] **Step 2: Run the workflow test**

```bash
cd server/main/manager-api
mvn -Dtest=DeviceWakeWordWorkflowTest test
```

Expected: both the successful update and failed-update-with-retry scenarios pass. If the test fails, keep its sequence unchanged and correct the production boundary responsible for the mismatched state or call order.

- [ ] **Step 3: Document exact eligibility, status, and recovery behavior**

`docs/dynamic-device-wake-word.md` must state: two-to-eight Chinese characters; one wake word per device; ESP32-S3 only; layout 2 and 8MB assets minimum; layout-1 devices remain unsupported; save triggers generation, dispatch, and one reboot; `ACTIVE` means device-confirmed, not merely generated; failed updates keep the old word; retry keeps the desired version; factory image generation reserves two 3 MiB slots; active word alone is sent to the Python runtime.

Update `server/docs/docker-build.md` to state that `Dockerfile-server` now copies the shared packer and `mn7_cn` resource already inside the `server` build context. Keep the existing command:

```bash
docker build -f Dockerfile-server -t 你的用户名/xiaozhi-esp32-server:新的版本号 .
```

- [ ] **Step 4: Run the complete automated verification set**

```bash
python -m pytest \
  server/main/shared/wake_word_assets/tests \
  server/main/xiaozhi-server/tests/test_wake_word_generator.py \
  server/main/xiaozhi-server/tests/test_wake_word_assets_handler.py \
  server/main/xiaozhi-server/tests/test_device_wake_word_config.py \
  firmware/tests/test_dynamic_wake_word_partition_image.py \
  firmware/tests/test_dynamic_wake_word_state_machine.py \
  firmware/tests/test_dynamic_wake_word_reporting.py \
  firmware/tests/test_device_heartbeat.py -v

cd server/main/manager-api
mvn test

cd ../companion-console
npm test
npm run lint
npm run build

cd ../../../firmware
idf.py build
```

Expected: every command exits zero. Record the built firmware target and assets partition size in the verification notes.

- [ ] **Step 5: Build the production server image**

```bash
cd server
docker build -f Dockerfile-server -t xiaozhi-server:wake-word-test .
```

Expected: the image builds, `pypinyin` installs, and both `/opt/xiaozhi-esp32-server/server/main/shared/wake_word_assets/packer.py` and `/opt/xiaozhi-esp32-server/models/wake_word/mn7_cn/mn7_data` exist in the image.

- [ ] **Step 6: Perform the hardware acceptance matrix on one eligible device**

Use a factory-assets layout-2 ESP32-S3 device. Verify these cases and capture manager-api state plus serial logs for each: online update from the default word to `小布小布`; device offline at save and later online; network interruption during slot download; wrong SHA-256; valid package with invalid index; valid package whose Multinet initialization fails; power loss after candidate write but before NVS active switch; reboot confirmation; old word stops waking after `ACTIVE`; new word wakes and reaches the server; each failed case retains the old active slot and old wake behavior.

- [ ] **Step 7: Commit documentation and final integration coverage**

```bash
git add \
  docs/dynamic-device-wake-word.md \
  server/docs/docker-build.md \
  server/main/manager-api/src/test/java/xiaozhi/modules/companion/wakeword/DeviceWakeWordWorkflowTest.java
git commit -m "test: verify dynamic wake word workflow"
```

## Final release gate

Do not enable the console control globally until the capability report from real hardware shows `supported=true`, `layout_version=2`, and `slot_size=3145728`, the end-to-end row reaches `ACTIVE` only after the matching device report, and all interrupted-download and invalid-package cases preserve the previous active word. Roll out behind the capability gate to a small device cohort first; layout-1 and 8MB-flash devices continue showing the existing fixed wake-word behavior without receiving dynamic update tasks.
