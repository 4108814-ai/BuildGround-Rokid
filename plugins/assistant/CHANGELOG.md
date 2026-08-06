# Changelog

## 1.2.0

- **Every provider can look now.** Photo questions were a ChatGPT-only power;
  API providers were told they could look and could only pretend. Now a
  vision-capable model on MiniMax, OpenRouter, GLM, DeepSeek, or your own
  server takes a real photo through the glasses camera — same one-photo-per-
  question rule, same privacy: never saved to the glasses' gallery.
- A model that cannot see images is now told so and answers honestly, instead
  of inventing tool-call syntax in the middle of its reply. Set the "can see
  photos" switch for your model in the provider's settings to enable the
  camera.
- A server that does not understand tools is retried once without them, so
  plain custom endpoints keep answering as before.

## 1.1.1

- **No more thinking out loud.** Models that reason inline — MiniMax, GLM,
  DeepSeek and anything similar behind OpenRouter or a custom server — were
  showing their raw `<think>` traces in every answer. Those blocks are now
  stripped from the stream before the glasses display them, the voice reads
  them, or the conversation keeps them: only the answer itself gets through.

## 1.1.0

- **Choose who answers.** Settings now opens on a provider list: your ChatGPT
  plan, an OpenAI key, OpenRouter, MiniMax (a Coding Plan key works as-is),
  DeepSeek, GLM (Z.ai), or any OpenAI-compatible server of your own. Each
  provider keeps its own key — encrypted on the phone — its own model choice,
  and its own endpoint, so switching is one tap, not a re-setup.
- **Any model id.** Pick from the suggestions or type the exact model your
  provider serves. For a model the app does not know, say whether it can see
  photos; photo questions are handled gracefully either way.
- **Give it a personality.** A new Personality box holds standing instructions
  — a persona, a tone, house rules — layered under the HUD formatting rules.
- Your notes and synced ChatGPT memories keep riding along with every question,
  whichever provider answers; the Memory toggle remains the single off switch.
- Signing out of ChatGPT no longer forgets the keys you saved for other
  providers.

## 1.0.1

- **Answers at full length.** The model is no longer told to stay under two
  short sentences: ask for detail and it answers as fully as the question
  deserves, in real paragraphs that the glasses render with hard line breaks
  instead of one flattened run of text. The notice band on the glasses grows
  and paginates to hold it — this needs Nexus 1.2.0 or later on both the
  phone and the glasses.
- While you dictate, the band keeps showing the tail of what it heard rather
  than paginating your own words away mid-sentence.

## 1.0.0

- First release: press the assist gesture, speak, and the answer streams onto
  the glasses. The stock Rokid assistant window is closed for you the moment it
  appears; your words transcribe live in a band on the HUD and the reply
  arrives in the same band, then is spoken aloud.
- **Sign in with ChatGPT.** Answers come from your own ChatGPT plan — nothing
  to paste, no per-token bill. An OpenAI API key works as the alternative
  route.
- **It can look.** Ask about what is in front of you and the model takes one
  photo through the glasses camera — one per question, only when the question
  needs eyes, and never saved to the glasses' own gallery.
- **Conversations continue.** A follow-up question keeps its context instead of
  starting over; a thread ends on its own after a quiet delay you choose. The
  phone keeps the transcripts — with the photos, if you want them kept — and
  both can be deleted at any time.
- **It starts out knowing you.** Memories and custom instructions from the
  ChatGPT account you signed in with are carried into the assistant, so you do
  not introduce yourself twice. Optional, synced automatically, and it can be
  turned off.
- **Web search built in.** The model decides when a question needs the web, so
  the weather and the news are answerable from your face.
- Pick the model (fastest to deepest) and how long it may think, in Settings.
- Requires Rokid Nexus 1.1.6 or newer, with *Show on your glasses*, *Glasses
  microphone*, *Speech to text*, *Text to speech*, *Replace the glasses
  assistant* and *Glasses camera* approved in Nexus → Settings → Plugin access.
