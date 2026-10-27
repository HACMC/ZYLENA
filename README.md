# ZYLENA Screen Reader

A free Android app that reads the screen aloud in Tagalog or English, made for Sabbath School and EGW Writings.

**Download:** [latest APK](https://github.com/HACMC/ZYLENA/releases/tag/screen-reader-latest)

- Tap the gold floating button to read; tap again to stop. Hold it for more: Back, Pause, Skip, Picture text, Save audio, speed.
- Whole-page reading: it goes through the page from the top, keeps the paragraph being read on screen with a gold outline, and turns to the next day.
- Reads words inside pictures too, without file names, times or sizes.
- Works offline and uses your phone's own voices. The Bible texts are public domain.

## Install

1. Download the APK from the link above and open it. Allow installing from your browser if Android asks.
2. Open ZYLENA and tap **Turn on in Accessibility**. The app shows each step.

## Building

`ScreenReader/` is the app. `.github/workflows/screenreader.yml` builds it, reads a practice lesson on a
virtual phone (`ScreenReader/testing/`), and publishes the APK when every check passes. Put
`[no phone test]` in a commit message to skip the virtual phone for small changes.

Release APKs are signed with a key kept in the `ZYLENA_KEYSTORE_B64` secret, never in this repository.
