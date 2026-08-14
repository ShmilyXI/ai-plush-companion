# TencentDB Agent Memory upstream boundary

This integration supports `TencentCloud/TencentDB-Agent-Memory` release `v2.0.0` at tag commit `0aff21a`.

The deployment image is `agentmemory/memory-core:1.0.0` pinned to manifest digest `sha256:f9b286246d0e5020a7f0cb011b7074703d10b76b424a834a117482392f7bd424`.

The platform digests are:

- AMD64: `sha256:e96a0d20ab14388b3961c5ecc1dd26d48277894c050d40c2c0b9180fb937a4cc`
- ARM64: `sha256:dfc1cfc517f169f3474c1672f27de466d11c4c7933947b908537d2a3a11655ad`

MemoryCore v2.0.0 scopes L2 and L3 by `team_id + agent_id`; `user_id` is not part of that boundary. The companion integration therefore namespaces `team_id` by user.

Before changing the release, tag commit, image tag, or digest, rerun the frozen v3 contract test and the Docker smoke test against the replacement image.
