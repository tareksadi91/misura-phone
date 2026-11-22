# Bring your own model key

Status: implementation of provider configuration, model-role routing and search is committed and reviewed through Task 5. Task 6 whole-branch review, repairs and source verification are complete. Clean export and standalone checks follow the final documentation commit.

## Intent and scope

Let a technical contributor configure Misura using one plain Gemini, OpenAI or Anthropic API key, without a Google Cloud project. Preserve existing Vertex service-account/token setup and its working phone configuration. Provider choice applies to the main agent, isolated memory authorization/target matching, and app-control model. Existing approval and storage boundaries remain in force.

Security/release work precedes this feature. Remaining limits must be explicit, not marked closed merely to start provider work. Landing and app visual work remain in the maintainer's separate thread. No publication, real sends/calendar writes, paid calls, phone installation or new Cloud provisioning is implied.

## Approaches

1. **Native adapters behind existing `Brain` interface (chosen proposal).** Gemini GenerateContent, OpenAI Responses and Anthropic Messages have separate authentication and tool/history encodings. Reuse existing HTTP/cancellation and encrypted credential storage. No new SDK dependency or hosted proxy.
2. One OpenAI-compatible protocol for every provider: smaller initial surface but loses provider-specific reasoning/signature semantics and makes compatibility depend on translation layers. Rejected for the requested native providers.
3. Arbitrary endpoints/gateway: useful later, but adds credential-destination validation, extra setup and unsupported protocol permutations. Defer custom hosts; keep adapters easy to extend through `Brain`.

## Setup and persistence

Extend existing secure settings form with provider selection, model identifier and a masked key field for the chosen API provider. Vertex retains service-account JSON/project inputs. Google Cloud credentials and Exa remain optional integrations for other providers. Do not require users to enter two keys.

Retain one saved key/model per provider. Default existing installations to Vertex without rewriting credentials. Blank secret inputs preserve saved values; switching providers never copies another provider's key. Store secrets through existing cipher, never prefill, save in instance state, log, include in agent prompts or screenshots. Persist selected provider/model/key atomically; invalid input or failed commit preserves working settings. Validate bounded single-line header values and model identifiers offline. No connection call on Save. Unknown providers/configuration fail clearly rather than silently choosing Vertex or a cheaper model.

Use one selected provider/model for all model roles in the initial version; retain existing Vertex main/hand configuration overrides for backward compatibility. Never silently downgrade model or thinking tier. Model defaults must come from current official documentation and remain editable. Mid-job settings changes apply next agent turn. Capture the entire route atomically with the same preference synchronization as every settings/debug writer: API key, Google service-account/fallback-token/project, both Vertex role models/thinking, and Exa key. Account-bound token refresh can renew captured credentials but cannot switch to new settings mid-turn. No silent failover to another provider or saved account. Fresh OpenAI plus Exa setup must save as one transaction without a Google project.

## Provider contracts and history

Keep existing persisted `Turn`/session format backward compatible. Adapters translate canonical text/function calls/results at the HTTP boundary. Preserve call IDs, multiple calls, ordered tool results, Unicode and raw arguments. Invalid JSON arguments, missing call IDs where required, truncated/refused answers and unsuccessful HTTP responses produce bounded `BrainError`s rather than partial execution.

Gemini keeps content parts and thought signatures unchanged, using `x-goog-api-key` against the fixed Google API origin. Vertex retains OAuth and billing-project headers only on Vertex requests. OpenAI uses Responses with bearer authentication and `store:false`; preserve raw reasoning/output items needed for stateless continuation, including encrypted reasoning where required. Anthropic uses Messages with `x-api-key`, pinned API version and explicit token budget; preserve signed thinking/tool-use blocks when enabled and return all tool results in valid user blocks.

Provider-specific continuation metadata, if stored alongside canonical content, must never be forwarded to a different provider or leak into rendered thread text. Existing Gemini history remains usable after provider switching through canonical conversion; do not pretend one provider's opaque state works elsewhere. Test old sessions and switching explicitly.

Interruption correctness precedes adapter repair: current multi-call processing can lose earlier completed results when a later call is interrupted. Preserve durable completed outcomes, close only unresolved calls, and ensure all canonical readers (thread rendering, place/item facts and mixed-turn normalization) support chosen representation. Distinguish not-started from unknown outcome; do not invite replay of completed effects. This is not a promise of generic exactly-once execution across an external effect/process-crash gap.

Builder review decisions: recursively convert Gemini's uppercase schema `type` values to JSON Schema lowercase without altering enum values; do not blindly enable OpenAI strict mode on schemas with optional fields. Give legacy calls without IDs deterministic translation IDs matched to their results. Preserve opaque continuation only for the matching provider/model. Snapshot routing and capabilities at `Agent.turn`, inherited by memory checks and nested tools through coroutine context; settings changes apply next turn. Remove unconditional startup Vertex token warm-up when API-key-only credentials are selected.

## Optional Google integration and app fallback

Google Cloud is optional for setup. When configured, direct Places/photo and grounded routes remain available independent of chosen brain provider. UI copy recommends direct Google integration for faster results, without promising measured latency or implying API-key-only mode is broken.

Without Google Cloud, do not advertise direct Places/photo tools as available. Give the selected model accurate capability context so it uses installed Google Maps or another suitable app through existing `use_app`/`open_app`. Do not fabricate Places IDs or render unsupported native place cards from model claims. Android contacts/calendar, notification reading/replies and memory continue independently.

Filter direct Places and related display/navigation tool specs together from the per-turn capability snapshot, including Agent-owned view tools; refresh after settings changes without process restart. Prompt instructions must match the same snapshot rather than merely removing `find_places` from a fixed Graph list.

### Search is independent of model choice

No additional search key is required. Default new installations to native web search using the selected model provider and its existing key (Gemini Google Search grounding, OpenAI Responses web search, Anthropic Messages web search), for supported models/accounts. Optional search selection: Automatic/native, Exa, Tavily or Context.dev. Existing saved Exa installations retain Exa as their selected search service; do not silently change their billing/data route. Google Cloud/Places access remains independent of plain Gemini web search.

Only the explicitly selected external search service is tried; on its operational failure, native search with the current model provider is the disclosed fallback, then installed browser app if native search is unsupported, disabled or unavailable. Do not try every saved search credential or switch model providers behind the user's back. Enforce one shared lookup deadline and bounded attempts; honor cancellation. A valid sourced answer ends lookup. Empty/unsourced, partial, truncated and failed responses have distinct outcomes, not invented success. Query text may be disclosed to selected services; never send keys, briefing or full conversation to a search service. Native lookup request is isolated to the question/date, not whole agent history.

Fix current Exa-before-Vertex coupling. Tavily uses its documented search/answer response, Context.dev its bounded fast Answers route; do not add crawling, monitors, MCP or a generic remote-tool framework. Normalize answers, trusted source URLs, native citation annotations and partial/failure status through one small internal lookup contract. Secrets/provider selection are snapshotted atomically with model route, with standalone save/rollback tests for each optional search key and no Google requirement.

Native search requires native metadata handling and UI attribution: preserve real returned citations/grounding and required search-suggestion metadata. Existing source-name-only step lines are insufficient for OpenAI's documented clickable inline attribution requirement. Carry verified citations to current and reopened answer rendering, with safe HTTP(S) links and accessibility. Do not manufacture citations from model prose or lose them in a later synthesis call. Check each provider's exact display obligations before implementation; if current renderer cannot satisfy them, implement the minimal compliant rendering before enabling that backend rather than quietly dropping sources.

Use a code-owned lookup reference for cited answers: model selects opaque `lookup_id` through existing answer tool, then code renders stored sourced text with its original annotations. Do not copy citation spans onto paraphrased text. Current and reopened answer models gain backward-compatible optional citation metadata; ordinary non-web answers retain existing plain-text behavior. Lookup IDs are session-scoped and never grant tool execution or approval.

With no usable direct search route, use an installed browser through existing app control, with honest source/result limits; existing prompt text currently forbids this and must change with capability context. Missing apps/accounts or app-control prerequisites return actionable errors. App-based routes keep existing approval limits and may be slower. Native search may add provider charges and can be disabled by account policy; describe it as requiring no extra key, not free or universally available.

## Verification and release

Add deterministic loopback HTTP contracts for each adapter: authentication isolation/no redirects, text and multi-call responses, tool-result follow-ups, opaque reasoning/signatures, malformed/truncated/refused responses, cancellation, 401/403/429 and timeouts. Use synthetic keys only. Setup tests cover key preservation, provider switching, atomic failure recovery, invalid headers/models, no saved secrets and backward-compatible Vertex startup. Integration tests cover memory authorization and app control using selected brain, no-Google capability routing and Exa without Vertex.

Run complete JVM checks, release assembly and Android-test compilation; run emulator setup/agent regressions and full suite once for final batch, with independent read-only review. Fresh paid-provider/live-model validation requires explicit authorization and suitable disposable credentials; report it as unverified otherwise. Never read existing private local keys to make tests pass.

Update SYSTEM, README, SECURITY and audit evidence with implementation. Regenerate local overview; regenerate a clean one-commit public candidate only after final reviewed code is verified. Publication stays explicit-go.

## Official contract references checked during design

- OpenAI function calling: https://developers.openai.com/api/docs/guides/function-calling
- Google function calling and API reference: https://ai.google.dev/gemini-api/docs/function-calling and https://ai.google.dev/api/generate-content
- Anthropic tool schemas: https://platform.claude.com/docs/en/agents-and-tools/tool-use/define-tools
- Native web search: https://ai.google.dev/gemini-api/docs/google-search ; https://developers.openai.com/api/docs/guides/tools-web-search ; https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-search-tool
- Optional search services: https://docs.tavily.com/documentation/api-reference/endpoint/search ; https://docs.context.dev/answers/overview

Exact endpoint, current model defaults and reasoning configuration still require adapter-level reference checks before implementation; these design references do not constitute live-provider verification.
