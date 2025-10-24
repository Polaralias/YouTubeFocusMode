# YT Focus

YT Focus blocks distracting YouTube video playback by masking the screen while leaving audio controls accessible.

## First run checklist

1. Open the app and tap **Enable and start overlay**.
2. Grant the overlay drawing permission when prompted.
3. Enable the YT Focus notification listener in system settings.
4. Grant usage access for YT Focus.
5. Activate the YT Focus accessibility service.

## Usage notes

- The black overlay appears only when YouTube or YouTube Music is in the foreground and actively playing video content; background playback continues without interruption.
- YouTube Music video mode exposes a clear rounded hole above the audio or video toggle, with a fallback top right hole if the toggle cannot be located.
- Previous, Play or Pause, Next, and the SeekBar remain available; scrubbing uses the media session seekTo transport control for accurate positioning.
- The overlay is fully opaque (#FF000000) elsewhere to preserve focus and keep screen colour uniformity.
