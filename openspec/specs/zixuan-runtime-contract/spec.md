## Purpose

TBD: Define the service, route, MQTT, and release-version contracts for the Zixuan runtime.

## Requirements

### Requirement: Runtime services use Zixuan identities
Java, Python, MQTT, web, proxy, container, and service-manager configuration SHALL use the Zixuan package, artifact, directory, image, and service names.

#### Scenario: Start the complete service stack
- **WHEN** the Zixuan release is started from its deployment manifest
- **THEN** all required services become healthy without reading an obsolete product-owned service path or environment variable

### Requirement: Product routes use the Zixuan namespace
The system SHALL expose product-owned manager, device WebSocket, OTA, and internal playground routes under `/zixuan` while preserving identity-neutral `/api/v1`, `/internal`, and `/mcp` contracts.

#### Scenario: Connect through a new route
- **WHEN** an authorized client calls a Zixuan route with the correct protocol credentials
- **THEN** the request reaches the same owning control-plane or runtime behavior defined before the rename

#### Scenario: Call an obsolete route
- **WHEN** any client calls a retired product-owned `/xiaozhi` route after cutover
- **THEN** the proxy and application reject the request without silently forwarding it

### Requirement: MQTT uses the Zixuan topic namespace
The gateway and firmware SHALL publish and subscribe only to configured `zixuan/` topics after cutover.

#### Scenario: Device opens an MQTT session
- **WHEN** a released device authenticates to the released gateway
- **THEN** control and audio-session messages use Zixuan topics and preserve per-device isolation

#### Scenario: Client uses an obsolete topic
- **WHEN** a client publishes to or subscribes to a retired unprefixed product topic
- **THEN** the released gateway does not route that message into a device or Python session

### Requirement: Cross-layer release versions remain coherent
The release SHALL bind Java, Python, MQTT, clients, proxy configuration, and firmware to one version manifest.

#### Scenario: Validate a deployment candidate
- **WHEN** any artifact reports a different rename contract version or an obsolete endpoint
- **THEN** deployment is blocked before services are switched
