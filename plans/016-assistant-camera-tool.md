# Plan 016 — Model-driven Assistant camera tool

Status: SPECIFICATION

## Decision

The Assistant exposes one custom Responses API function tool named
`take_photo`. The model alone decides whether the current user request requires
that tool.

There is no local semantic detector, keyword list, phrase grammar, intent
classifier, or pre-model snapshot. Local code only:

1. advertises the tool;
2. receives and validates a model-emitted function call;
3. enforces device policy and the one-photo limit;
4. captures one visible snapshot;
5. sends the resulting image back as the function output.

The model may request the action, but it cannot bypass the local camera grant,
device state, visible UI, cancellation, or per-turn limit.

## Goal

Let the model inspect the wearer's current point of view only when it judges
that current visual information is necessary to answer.

This fixes both failure modes of a local phrase detector:

- unrelated speech cannot trigger a photo merely because it contains a broad
  substring such as `regarde` or `c'est quoi`;
- genuinely visual requests do not depend on an incomplete hand-written list
  of French and English phrasings.

The expected trade-off is one additional model round trip when the tool is
used. Text-only requests still require one model request.

## Non-goals

- No deterministic local decision about whether a sentence is visual.
- No continuous camera stream or video.
- No background, scheduled, speculative, or repeated capture.
- No generic arbitrary tool execution.
- No change to the Nexus snapshot protocol or signer-bound capability model.
- No gallery persistence.
- No automatic use of an old photo from a previous request.

## Required flow

```text
final STT transcript
  -> Responses request #1
       tools: [web_search, take_photo]
       tool_choice: auto
       parallel_tool_calls: false
       reasoning.effort: current setting, default none
  -> model result
       ├─ final text
       │    -> display answer
       │    -> no camera operation
       └─ function_call take_photo
            -> validate the call and local policy
            -> show "Photo…"
            -> capture at most one current POV image
            -> show "Thinking…"
            -> Responses request #2
                 input: original input
                        + request #1 output items
                        + matching function_call_output
                 take_photo: unavailable for the rest of this user turn
            -> display final answer
```

The model decision occurs before any snapshot API is touched.

## Tool contract

The first Responses request declares this strict, zero-argument custom
function:

```json
{
  "type": "function",
  "name": "take_photo",
  "description": "Capture one current point-of-view photo from the user's Rokid glasses. Call this only when answering the current request requires seeing the user's current physical scene. Do not call it for web images, questions about camera behavior or settings, discussion of a previous photo, or questions answerable from text or web information.",
  "parameters": {
    "type": "object",
    "properties": {},
    "required": [],
    "additionalProperties": false
  },
  "strict": true
}
```

Request settings:

```json
{
  "tool_choice": "auto",
  "parallel_tool_calls": false
}
```

The tool has no fields because the model must not choose camera identifiers,
resolution, storage paths, timing, or capture count. Those remain local policy.
The tool must never be forced from transcript text.

`web_search` remains a separate server-side tool. A web-image request is not a
reason to call `take_photo`.

## Model instruction

Replace the current phrase-oriented camera instructions with the following
semantic policy:

```text
You can call take_photo to inspect the wearer's current physical view.
Decide yourself whether current visual information is necessary to answer the
user's current request. Do not call it for discussion of a previous photo,
camera behavior or settings, web images, or questions answerable from the
conversation or the web. Call it at most once per user request. Never claim to
see the current scene before a successful tool result.
```

This instruction and the tool description guide the model. They do not create
a second local classifier.

## Receiving the model call

The Codex SSE parser must stop treating every non-message output item as an
ignored or completed answer. It must preserve structured response output,
including:

- `function_call.call_id`;
- `function_call.name`;
- the complete JSON arguments;
- all other output items needed for stateless continuation;
- the authoritative completed response.

The implementation may accumulate
`response.function_call_arguments.delta`, but it must use the completed
arguments/item as the source of truth. The call is executable only after a
complete output item has been received.

Text emitted during a turn that also requests `take_photo` is buffered and not
shown as the final answer. The user sees the answer produced after the tool
result, avoiding duplicated preambles such as “Let me look.”

## Local execution boundary

`take_photo` is an allowlisted tool name, not a direct command channel. Before
opening a snapshot session, the executor verifies all of the following:

- the Assistant request and generation are still current;
- the signer-bound snapshot capability is granted;
- the glasses link is available;
- no snapshot session is already active;
- this user turn has not already consumed its one capture;
- the visible `Photo…` state has been requested before the hardware call.

Unknown tool names, malformed arguments, stale calls, unavailable capabilities,
busy hardware, and second calls are never executed.

The per-turn photo budget is consumed when a valid capture attempt begins. A
failure does not permit an automatic retry. This makes “at most one” true even
when the model or transport retries.

## Tool results

### Successful capture

Return the JPEG directly as image content in the function output, correlated
with the model-provided `call_id`:

```json
{
  "type": "function_call_output",
  "call_id": "call_...",
  "output": [
    {
      "type": "input_image",
      "image_url": "data:image/jpeg;base64,...",
      "detail": "auto"
    }
  ]
}
```

The `call_id` must be copied exactly. The image is the current one-shot POV
capture and is not also attached as a new user message.

### Rejected or failed capture

Return a compact JSON string as the function output and continue to request #2:

```json
{
  "type": "function_call_output",
  "call_id": "call_...",
  "output": "{\"ok\":false,\"code\":\"camera_unavailable\"}"
}
```

Allowed stable error codes:

- `not_authorized`
- `glasses_disconnected`
- `camera_busy`
- `already_used`
- `cancelled`
- `capture_failed`
- `invalid_call`

The second model turn can then explain briefly that it could not inspect the
scene. It must not claim that it saw an image on an error result.

## Stateless continuation

The ChatGPT Codex backend is currently called with `store: false`. Request #2
therefore explicitly replays:

1. the original Responses input;
2. every output item from response #1, including the `function_call` and any
   reasoning continuity item;
3. the matching `function_call_output`.

If the backend accepts it, request #1 includes
`reasoning.encrypted_content` so encrypted reasoning items can be preserved
without server-side storage. This must be compatibility-tested against the
private ChatGPT Codex endpoint.

Do not depend on `previous_response_id` for this flow. Explicit item replay is
the deterministic path while `store` remains false.

`take_photo` is removed from request #2, or equivalently excluded through an
allowed-tools constraint. This structurally prevents a second capture in the
same user turn. `web_search` may remain available.

## Provider architecture

The HTTP provider owns the Responses tool loop; the Android service owns the
camera operation.

Introduce an Assistant-specific execution boundary similar to:

```kotlin
data class AssistantToolCall(
    val callId: String,
    val name: String,
    val argumentsJson: String,
)

sealed interface AssistantToolResult {
    data class Image(
        val mimeType: String,
        val base64: String,
    ) : AssistantToolResult

    data class Error(
        val code: String,
    ) : AssistantToolResult
}

fun interface AssistantToolExecutor {
    suspend fun execute(call: AssistantToolCall): AssistantToolResult
}
```

`AssistantPluginService` injects this executor into
`ChatGptCodexProvider`. The executor allowlists `take_photo` and delegates a
successful call to the existing one-shot snapshot session.

`ChatGptCodexProvider`:

- declares the tool on request #1;
- parses structured function calls;
- invokes the injected executor;
- builds the correlated tool output;
- performs request #2;
- exposes the final response through the existing public
  `AiProviderEvent` stream.

The generic `AiProvider` API does not need to expose raw model tool calls to UI
code. The OpenAI API-key provider remains unchanged in this plan unless the
same tool contract is deliberately added later.

## UI and cancellation

Visible states are:

```text
Listening… -> Thinking… -> Photo… -> Thinking… -> answer
```

`Photo…` replaces the vague `Looking…` wording and is shown before snapshot
execution.

BACK, surface close, service teardown, or a new Assistant generation cancels:

- the active HTTP request;
- a pending or active snapshot session;
- any pending second model request.

A cancelled or stale generation cannot later display text or start a camera.
No confirmation dialog is required for v1: the visible status, BACK
cancellation, signer-bound grant, and hard one-photo limit form the execution
boundary.

## Privacy and logs

- Do not write the snapshot to the gallery or app storage.
- Keep the image only long enough to construct the current tool output.
- Do not log transcripts, base64 data, image bytes, or response bodies.
- Log only request ID, tool name, outcome code, byte count, dimensions, and
  stage latencies.
- Redact OAuth tokens and request authorization headers as today.
- If device testing reveals that the underlying capture path persists files,
  stop rollout until that behavior is removed or explicitly disclosed.

Useful stage timings:

- first model decision;
- local policy validation;
- camera capture;
- second model response;
- total end-to-end.

The expected camera path is slower than a text-only answer because function
calling requires a second model turn. Reasoning effort `none` remains the
default and does not alter the tool contract.

## Compatibility gate

The production Assistant uses the private
`chatgpt.com/backend-api/codex/responses` surface, while the public Responses
API documentation is the contract reference. Before Android implementation,
run a non-camera probe against the configured backend using:

- the exact `take_photo` function declaration;
- `store: false`;
- a static, non-sensitive fixture image as `function_call_output`;
- explicit replay of response #1 output items;
- the configured model and reasoning effort `none`.

The probe must establish that the backend accepts:

1. custom function tools alongside `web_search`;
2. streamed function-call output items and arguments;
3. image-array `function_call_output`;
4. stateless second-turn continuation;
5. encrypted reasoning continuity when requested, or a documented safe
   omission when effort is `none`.

Do not enable the feature on real glasses if any required payload is rejected.
Record the accepted request/response shapes as sanitized test fixtures.

## Tests

### Unit tests

- Request #1 contains `web_search`, `take_photo`, `tool_choice: auto`, and
  `parallel_tool_calls: false`.
- The tool schema is strict and has no arguments.
- A text-only completed response produces no executor call and no request #2.
- Function argument deltas assemble into one complete `take_photo` call.
- The completed `call_id` is copied exactly to `function_call_output`.
- A successful executor result serializes an `input_image` array.
- Each local error serializes a matching textual tool output.
- Unknown tools and malformed arguments are rejected without hardware access.
- Request #2 replays the first response output and removes `take_photo`.
- A repeated call returns `already_used` without a second capture.
- Cancellation prevents the executor or continuation from resuming.
- Tool-turn preamble text is not emitted as the final answer.

### Provider integration tests

Use scripted SSE fixtures for:

1. text answer in one request;
2. `take_photo` followed by image-aware final text in two requests;
3. camera denial followed by a graceful final answer;
4. malformed arguments;
5. unknown tool name;
6. stream failure before a complete tool call;
7. cancellation during capture;
8. cancellation during request #2;
9. a model attempting a second camera call;
10. `web_search` without camera use.

Assert the number of HTTP requests and snapshot calls in every case.

### Device tests

- A visual request shows `Photo…` before one real capture, then answers from
  that image.
- A non-visual request never opens a snapshot session.
- BACK during `Photo…` cancels without a late answer or late capture.
- Revoking the snapshot grant makes a model request fail closed.
- Disconnecting the glasses fails closed.
- No new file appears in `DCIM`, gallery, or Assistant storage.

### Model decision evals

The semantic choice now belongs to the model, so it is evaluated rather than
reimplemented locally. Use the production prompt, model, effort, and exact tool
description with a mocked executor.

Examples expected to call `take_photo`:

- “Qu'est-ce que tu vois devant moi ?”
- “Lis ce panneau.”
- “Est-ce que cette plante a l'air malade ?”
- “Which cable should I unplug?” when no image is already present.

Examples expected not to call it:

- “Oh, none, c'est la version instant ?”
- “Pourquoi il a pris une photo, Codex ?”
- “Comment désactiver la caméra ?”
- “Quel temps fera-t-il demain ?”
- “Trouve-moi une photo de Paris sur le web.”
- “Explique-moi ce qu'est un diaphragme en photographie.”

Maintain a multilingual regression corpus containing the original incident
phrasing once known. Run repeated samples for critical negative cases and track
the tool-call rate by model version. A model or prompt update cannot roll out
if it regresses unintended camera calls.

## Acceptance criteria

- No local transcript matcher decides whether to capture.
- `VisibleSceneRequestDetector` and equivalent phrase gates are removed from
  the Assistant path.
- A snapshot is possible only after a completed model-emitted
  `take_photo` function call.
- Text-only responses perform one model request and zero snapshot calls.
- A successful visual response performs exactly two model requests and one
  snapshot call.
- The wearer sees `Photo…` before hardware access and can cancel it.
- Local capability denial, disconnection, busy state, and cancellation all
  fail closed.
- The model receives either the current image or an explicit tool error and
  never receives a fabricated success.
- The exact private-backend payload passes the compatibility probe.
- No captured image is persisted or logged.

## Implementation sequence

1. Add and record the private-backend compatibility probe.
2. Add structured Responses output parsing and sanitized SSE fixtures.
3. Add `AssistantToolExecutor` and the `take_photo` request/result serializers.
4. Implement the two-request stateless provider loop.
5. Connect the service executor to the existing snapshot session and visible
   `Photo…` state.
6. Remove the local phrase detector from the request path.
7. Add unit, integration, model-decision, and on-device privacy tests.
8. Roll out behind an Assistant camera-tool flag, then remove the old detector
   rather than keeping it as a fallback.

## Reference

The payload and continuation design follows the OpenAI Responses API
function-calling flow: the application declares tools, the model emits a
function call, the application executes it, and a second Responses request
returns the correlated function output.
