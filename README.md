# AI Plush Companion

Zixuan is an open-source emotional companion product built on ESP32 hardware. This monorepo contains the management console, backend services, voice runtime, device firmware, and MQTT gateway used by the product.

## Components

- `server` contains the React management console, Java manager API, and Python Zixuan voice runtime. It is based on [xinnan-tech/xiaozhi-esp32-server](https://github.com/xinnan-tech/xiaozhi-esp32-server).
- `server/main/companion-web` contains the standalone Next.js companion chat application. The console playground embeds this app and the app uses the public conversation API for text and realtime voice.
- `firmware` contains the ESP32 firmware and the project-specific board adaptations. It is based on [78/xiaozhi-esp32](https://github.com/78/xiaozhi-esp32).
- `mqtt-gateway` contains the MQTT and UDP gateway. It is based on [xinnan-tech/xiaozhi-mqtt-gateway](https://github.com/xinnan-tech/xiaozhi-mqtt-gateway).

Each component keeps its original license and attribution files. Review the license inside that component before redistributing a modified build.

## Device Capability and Skill Center

The management backend is the source of truth for conversational and device capabilities. Administrators use `companion-console` to create versioned Skills, configure mixed rule and semantic triggers, select existing tools, publish a version, and bind it to individual devices. A Skill is configuration only. It can combine registered Plugins, approved MCP tools, and tools reported by device firmware, but it cannot contain or execute arbitrary code.

Weather, news, and search are managed as Skills. The same Skill can be shared by several devices while each binding keeps its own enabled state, version policy, trigger priority, and allowed parameter overrides, such as a default weather location. Skill bindings follow the device rather than its current character or agent. Volume and brightness controls remain on the device page, while AI requests use the same device MCP tools and therefore report real offline or unsupported states.

Plugin code is still deployed with `zixuan-server`; the console manages only its registered metadata, schema, configuration, and secret references. External MCP connections and tool allowlists are managed in the backend. The former `.mcp_server_settings.json` file is accepted only as a one-time import source and is not merged into an active backend configuration. MCP secrets are resolved in server memory and are not returned to the browser or device.

`zixuan-server` loads an effective capability bundle for each device at the start of a conversation turn. Published changes and device unbinding are visible on the next turn without altering a turn already in progress. If the management API is temporarily unavailable, the last successful bundle can be used for up to five minutes. After that, the service fails closed to normal conversation and required system tools instead of exposing stale Skills.

## Development

Start with the component README files for environment setup and build commands. Local credentials, generated firmware, device backups, dependency directories, virtual environments, downloaded models, and build outputs are intentionally excluded from this repository.
