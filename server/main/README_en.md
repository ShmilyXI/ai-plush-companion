# Server Components

The server side has four explicit boundaries. `manager-api` is the Java control plane for users, agents, models, voices, devices, capability versions, public conversation sessions, and API keys. `xiaozhi-server` is the Python runtime for device WebSocket sessions, VAD, ASR, LLM, TTS, memory, and isolated tools. `companion-console` is the current React administrator console. `manager-mobile` is the mobile management application and is not part of the runtime path.

Physical devices still connect through the MQTT gateway. The gateway owns device identity, MQTT/UDP audio bridging, and device commands. APP, mini-program, and web clients do not pretend to be devices: they create a public session through Java and then connect to the Python WebSocket with the short-lived runtime token. Raw API keys are resolved only at the Java boundary and never enter Python, device traffic, or logs.

## Local development

Run `manager-api` with JDK21 and Maven. MySQL and Redis are required for the development profile, and Liquibase owns schema changes. In `companion-console`, run `npm ci` and `npm run dev`; the default development port is 8001 and the Vite proxy points to the Java service. Python private configuration and downloaded models belong to `xiaozhi-server`; local credentials must remain outside version control.

## Configuration boundaries

Long-lived board configuration comes from `firmware/main/boards/<board>/config.json`. Python local configuration provides defaults only; explicit device or agent settings from Java take precedence. Runtime model bundles may contain provider credentials required by the trusted Python runtime, but those values are never returned to the browser, public session creation response, or device protocol.

## Public conversation

Java exposes `POST /api/v1/conversations` plus role, model, voice, and device resource endpoints. First-party clients use a user Bearer token. Third-party servers use a scoped, revocable `ApiKey` with an optional Agent allowlist. The session response contains only a short-lived runtime token, conversation identity, allowed modes, and the stream URL.

The Python WebSocket accepts text or complete audio turns and emits events such as `asr.final`, `llm.delta`, `tts.audio`, and `turn.completed`. Runtime bundles are persisted in Redis with a bounded TTL. Python trusts only the signed runtime token and the internal Java bundle; clients cannot submit provider URLs, prompts, plugins, or credentials.

## Verification

Silent verification covers manager-api conversation tests, Python `tests/test_public_conversation_*.py`, MQTT gateway tests, and companion-console lint, build, and Vitest. Hardware acceptance additionally correlates the serial MAC, gateway identity, ASR, LLM, TTS, and UDP downlink in one turn. At night, run only static checks and tests that do not emit sound.
