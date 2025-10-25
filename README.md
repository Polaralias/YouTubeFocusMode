# AudioFocus

AudioFocus blocks distracting video playback on YouTube, YouTube Music, and Spotify video podcasts by masking the screen while leaving audio controls accessible.

## First run checklist

1. Open the app and tap **Enable and start AudioFocus overlay**.
2. Grant the overlay drawing permission when prompted.
3. Enable the AudioFocus notification listener in system settings.
4. Grant usage access for AudioFocus.
5. Activate the AudioFocus accessibility service.

## Usage notes

- The black overlay appears when YouTube, YouTube Music, or a Spotify video podcast is in the foreground and actively playing video content; background playback continues without interruption. Spotify audio-only sessions keep the screen clear while leaving the toggle punch-out accessible.
- YouTube Music video mode exposes a clear rounded hole above the audio or video toggle, with a fallback top right hole if the toggle cannot be located.
- Spotify keeps a persistent hole over the audio or video toggle so you can switch modes even when the mask is hidden for audio playback.
- Previous, Play or Pause, Next, and the SeekBar remain available; scrubbing uses the media session seekTo transport control for accurate positioning.
- The overlay is fully opaque (#FF000000) elsewhere to preserve focus and keep screen colour uniformity.
