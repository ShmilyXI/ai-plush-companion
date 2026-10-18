# ADR 0011 Conversation runtime without a dsh production dependency

## Status

Accepted

## Date

2026-09-02

## Context

The beta baseline at `93891a8bd2bdbc45f2b5f02c08bb6c3edf881902` already has a working device path, public conversation API, model providers, and management control plane. Its main modernization problem is ownership: the Python connection object and transport handlers still combine profile resolution, capability selection, provider calls, tool execution, and lifecycle state.

dsh provides useful ideas for composing services, scoping resources, publishing immutable session events, and loading versioned manifests. It is not a drop-in replacement for the existing Java control plane, Python audio providers, MQTT/UDP gateway, or ESP32 protocol. Introducing its runtime as a production dependency before proving those boundaries would add another process and protocol surface without removing an existing responsibility.

## Decision

The product adopts dsh design concepts locally and does not add dsh as a production dependency during the first companion runtime modernization cycle. The transport-independent Python `ConversationRuntime` is the integration seam. It accepts an immutable request with the user, profile, conversation, source, input/output modes, and captured capability bundle, then returns a handle with an asynchronous event stream, input, cancellation, and close operations.

Ownership is split as follows:

| Owner | Responsibility |
| --- | --- |
| `manager-api` | Users, profile active versions, model and voice references, Memory policy, Skill publication, device ownership, permissions, and validated runtime bundles |
| `zixuan-server` `ConversationRuntime` | Per-connection and per-turn orchestration, prompt assembly, Memory namespace selection, capability isolation, provider invocation, tool lifecycle, TTS choice, event sequencing, and redacted errors |
| ASR, LLM, TTS, and Memory providers | Provider-specific network and media behavior behind explicit adapters and cancellation/disposal contracts |
| `mqtt-gateway` and device/public adapters | Authentication, framing, audio transport, device commands, WebSocket protocol compatibility, and translation to/from runtime requests and events |
| ESP32 firmware | Board-specific hardware, capture/playback, display, buttons, camera, and device capability reporting |

The existing public conversation OpenAPI/WebSocket contract, MQTT topics, device WebSocket headers, UDP audio framing, and firmware behavior are compatibility inputs. They are translated into the runtime vocabulary and are not replaced by a dsh protocol.

## Consequences

The first cycle can be implemented and tested within the current Java, Python, Node, and firmware deployments. A profile or capability change is captured for the next turn rather than mutating an active handle. Connection, turn, provider, tool, and transport resources have an explicit owner, which makes cancellation and cleanup testable.

The cost is maintaining local adapters and compatibility translation while the seam is introduced. Existing provider libraries and device behavior remain in place, so this decision does not by itself reduce the number of providers or services.

## Future dsh spike gate

A future dsh experiment must run in an isolated directory and demonstrate session ownership, streaming input/output, cancellation, capability isolation, profile-version capture, multi-tenant Memory persistence, failure recovery, and equivalent public/device protocol behavior. It must include latency, memory, and operational-complexity comparisons against the local runtime. dsh may replace an implementation only when the spike removes a complete existing responsibility without changing the device or public wire contracts. Until then, dsh remains a design reference and an explicitly prohibited runtime dependency in beta manifests.
