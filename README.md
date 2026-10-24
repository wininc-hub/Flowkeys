# FlowKeys

A Wispr Flow–style voice dictation bubble for Android. A small floating mic appears whenever you tap into a text field, in any app, next to Gboard or whatever keyboard you use. Tap it, talk, tap again, and polished text is typed at your cursor.

**How it works:** your recording goes to OpenAI for speech-to-text, then Claude cleans it up: removes "um/uh", keeps only your self-corrections ("3, no wait, 4" → "4"), fixes punctuation, formats spoken lists, and adjusts tone to the app you're typing in.

## Using the bubble

| Gesture | Does |
| --- | --- |
| Tap | Start recording; tap again to finish and insert |
| Hold (while recording or working) | Cancel |
| Hold (idle) | Open FlowKeys settings |
| Drag | Move it; it snaps to the nearest screen edge and remembers its spot |

It only appears while a text field is focused (or always, if you turn that on). It never types into password fields. If you move to a different field while it's still working, the text is copied to the clipboard instead of typed into the wrong place.

## Install

Download the latest APK from the repo's **Releases** page (`FlowKeys.apk`) and open it on your phone. Every push to `main` builds a new one automatically.

## Set up

Open **FlowKeys** and work through the screen:

1. **Allow microphone.**
2. **App info → ⋮ menu → Allow restricted settings.** Android 13+ blocks accessibility for apps installed outside the Play Store until you do this.
3. **Turn on the floating bubble:** in Accessibility, find FlowKeys and switch it on.
4. Paste your API keys:
   - OpenAI (required): platform.openai.com → API keys
   - Anthropic (optional): console.anthropic.com → API keys. Without it you get the raw transcript.
5. Add names and jargon to the **Personal dictionary** so they're spelled right.
6. Try it in the box at the bottom of the screen.

## Why Accessibility?

Android only lets an accessibility service draw above other apps *and* type into the focused field of another app. FlowKeys uses it for exactly two things: knowing whether a text field is focused, and inserting your dictated text there. It doesn't read or store screen content.

## Settings

- **Transcription model:** defaults to `gpt-4o-mini-transcribe`. Use `gpt-4o-transcribe` for higher accuracy, or `whisper-1` if your account doesn't have the others.
- **Cleanup model:** defaults to `claude-haiku-5-5`. Any Claude model ID works.
- **AI cleanup** switch: turn off to insert the raw transcript.

## Notes and known limits

- Keys are stored in app-private storage, excluded from backups. Audio goes to OpenAI and text to Anthropic under their API terms; nothing else leaves the phone. Recordings are capped at 10 minutes and deleted right after transcription.
- Some Android versions restrict microphone use by background apps. If the bubble says "No audio captured", that's what happened; please report your phone model and Android version.
- A few apps (some web views and custom editors) reject direct text insertion. FlowKeys then falls back to pasting, which replaces your clipboard contents.

## Build it yourself

Open the folder in Android Studio and press Run, or run `./gradlew assembleDebug`.

## Code map

- `FlowAccessibilityService.kt`: the bubble, recording, and text insertion
- `FlowApi.kt`: OpenAI transcription + Claude cleanup prompt
- `MainActivity.kt`: setup and settings screen
