# kuilt-primitives — warp

Routes for spreading work across peers: where a task may run, moving code and blobs between them.
Each `Jump to` names a section of `docs/agent-cookbook/<file>`; fetch it by the ladder in this skill's `SKILL.md`.
Rows pair `;`-separated groups in order.

|You're about to write…|Use instead|Jump to|
|---|---|---|
|"only run on a GPU / in-region peer", a placement predicate|`Affinity` + `TaskDescriptor.where` + `CapSet`|`warp.md#where-a-task-may-run-location-eligibility`|
|a content-hash blob cache; running code another peer sent you, an `eval`|`Creel` + `BobbinExchange`; `WasmRuntime` + `WasmSandboxConfig` + `WarpLazyFetch`|`warp.md#code-mobility`|
