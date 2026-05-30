# Simple Video Player

A deliberately tiny, free, ad-free, open-source Android video player whose
**only** job is to play videos and reliably jump to the **next / previous**
video in the same folder — including folders that mix `.mp4` and `.mkv` files.

It is built on Android's recommended modern media stack
([AndroidX Media3 / ExoPlayer](https://developer.android.com/media/media3)) and
uses the Storage Access Framework, so it needs **no broad storage permissions**.

## Why another video player?

There are a million video players. Here is the specific, concrete reason this
one exists:

I keep videos in a single folder. Some are `.mp4`, some are `.mkv`. I tried
close to ten different players from the Play Store, and almost all of them share
the same problem:

- Many never show a **"next video"** button at all.
- Of the ones that do, most **do not actually advance** to the next file in the
  folder when you press it.
- Some advance **only if the next file is `.mp4`**, and get stuck the moment the
  next file is an `.mkv`.

The only player I found that reliably steps through a folder containing **both**
`.mp4` and `.mkv` is **MX Player**. But its "Pro" version was pulled from the
Play Store (so you can't buy it anymore), and the free version shows ads.

So the goal here is the opposite of feature creep:

- **Plays video only** — no audio-player mode, no image viewer.
- **Reliable next/previous** across a whole folder, regardless of `.mp4` vs
  `.mkv` (ExoPlayer handles both containers natively).
- **No fancy "supersonic" controls** — just play/pause, a seek bar, and
  next/previous.
- **As small as possible**, no ads, no tracking, no analytics.
- **Open source** (MIT), so anyone can audit it and build it themselves.

## How it works

ExoPlayer's playlist handles the hard part: every video in the chosen folder is
added to one playlist, so the built-in next/previous buttons "just work" across
mixed `.mp4` + `.mkv` content. A broken/unsupported file is skipped instead of
freezing playback.

You can start the player three ways:

1. **Pick a folder** inside the app (Storage Access Framework). The folder grant
   is remembered, so next time it resumes where you were.
2. **"Open with"** from any file manager (e.g. CX File Explorer, EX File
   Explorer). If you have already granted the folder, next/previous works across
   the whole folder; otherwise it plays just that file and offers to grant the
   folder.
3. From the launcher, which resumes the last folder you picked.

Because it uses the Storage Access Framework, it also works for "hidden"
folders (names starting with `.`) that the system media scanner ignores — which
is exactly where most gallery-style players fall down.

## Build

The app is compiled inside Docker (no Android toolchain is installed on the
host). The resulting APK is then installed/run on a real device.

```bash
# 1. Build the build image (installs JDK 17, Android SDK 35, Gradle 8.9).
./dockerbuild.sh

# 2. Compile the APK (output is written back to the host via the volume mount).
./indockerbuild.sh debug      # or: ./indockerbuild.sh release

# 3. Install on a connected device.
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Project facts

| | |
|---|---|
| Language | Kotlin |
| UI | View-based (no Compose, to keep the APK small) |
| Media engine | AndroidX Media3 / ExoPlayer |
| `minSdk` | 29 (Android 10) |
| `targetSdk` / `compileSdk` | 35 |
| Permissions | none dangerous; uses SAF + granted content URIs |

## Usage

1. Launch the app and tap **Pick a folder**, then choose your video folder.
2. The first video starts playing. Use the on-screen **⏮ / ⏭** buttons (or your
   headset's track controls) to move through the folder.
3. Alternatively, open any video from a file manager with **Open with → Simple
   Video Player**.

## Contributing

Issues and pull requests are welcome. Please keep the project's spirit: small,
single-purpose, no ads, no tracking. Discuss larger features in an issue first.

## License

[MIT](LICENSE).
