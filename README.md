# FlowKeys

A Wispr Flow–style voice keyboard for Android. Tap the mic in any app, talk, tap again, and polished text is typed where your cursor is.

**How it works:** your recording goes to OpenAI for speech-to-text, then Claude cleans it up: removes "um/uh", keeps only your self-corrections ("3, no wait, 4" → "4"), fixes punctuation, formats spoken lists, and adjusts tone to the app you're typing in (casual for chat, full sentences for email).

## Keyboard

| Key | Does |
| --- | --- |
| 🎤 | Tap to start, tap to finish. Pulses with your voice. |
| ✕ | Cancel the current recording or request |
| 🌐 | Back to your normal keyboard (long-press: pick any keyboard) |
| ↶ | Undo the last dictation |
| space / ⌫ / ⏎ | As usual (⌫ repeats when held) |

## Build the APK

**From your phone (no computer):**
1. Create a new GitHub repo and upload everything in this folder, keeping the folder structure (including the hidden `.github` folder). Phone browsers often flatten folders on upload, so the easiest route is to let Claude push it for you.
2. Open the repo's **Actions** tab. The "Build APK" workflow runs on every push; you can also start it with **Run workflow**.
3. When it finishes (~4 min), open the run and download **FlowKeys-apk**. Unzip it to get `app-debug.apk`.

**With a computer:** open the folder in Android Studio and press Run, or run `./gradlew assembleDebug`.

## Install and set up

1. Open `app-debug.apk` on your phone and allow installing from that source when asked.
2. Open **FlowKeys** and work through the screen:
   - **Allow microphone**
   - **Turn on FlowKeys keyboard** → enable "FlowKeys Voice"
   - **Switch to FlowKeys**
3. Paste your API keys:
   - OpenAI: platform.openai.com → API keys (required)
   - Anthropic: console.anthropic.com → API keys (optional; without it you get the raw transcript)
4. Add names and jargon to the **Personal dictionary** so they're spelled right.
5. Try it in the box at the bottom.

## Settings

- **Transcription model:** defaults to `gpt-4o-mini-transcribe`. Use `gpt-4o-transcribe` for higher accuracy, or `whisper-1` if your account doesn't have the others.
- **Cleanup model:** defaults to `claude-haiku-5-5` (fast and cheap). Any Claude model ID works.
- **AI cleanup** switch: turn off to insert the raw transcript.

## Notes

- Keys are stored privately on the device (app-private storage, excluded from backups). Audio is sent to OpenAI and text to Anthropic under their API terms; nothing else leaves the phone.
- Recordings are capped at 10 minutes and deleted right after transcription.
- If you switch to another text field while it's still working, the result is copied to the clipboard instead of typed into the wrong place.

## Code map

- `FlowKeyboardService.kt`: the keyboard (recording, UI, inserting text)
- `FlowApi.kt`: OpenAI transcription + Claude cleanup prompt
- `MainActivity.kt`: setup and settings screen
- `res/layout/keyboard_view.xml`: keyboard layout
