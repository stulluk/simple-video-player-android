# drejo player

A deliberately tiny, free, ad-free, open-source Android video player whose
**only** job is to play videos and reliably jump to the **next / previous**
video in the same folder — including folders that mix `.mp4` and `.mkv` files.

It is built on Android's recommended modern media stack
([AndroidX Media3 / ExoPlayer](https://developer.android.com/media/media3))
and ships in two flavors:

| Flavor | Channel | Storage access |
|---|---|---|
| **Play** | Google Play | Storage Access Framework only — no dangerous permissions |
| **F-Droid** | F-Droid + GitHub Releases | Optional `MANAGE_EXTERNAL_STORAGE` for one-toggle, MX-style folder access |

Both flavors share one codebase; only the F-Droid build's manifest declares
the `MANAGE_EXTERNAL_STORAGE` permission, and they install side by side
(different applicationId) so you can compare them.

## Why another video player?

There are a million video players. Here is the specific, concrete reason this
one exists:

I keep videos in a single folder. Some are `.mp4`, some are `.mkv`. I tried
close to ten different players from the Play Store, and almost all of them
share the same problem:

- Many never show a **"next video"** button at all.
- Of the ones that do, most **do not actually advance** to the next file in
  the folder when you press it.
- Some advance **only if the next file is `.mp4`**, and get stuck the moment
  the next file is an `.mkv`.

The only player I found that reliably steps through a folder containing
**both** `.mp4` and `.mkv` is **MX Player**. But its "Pro" version was pulled
from the Play Store (so you can't buy it anymore), and the free version shows
ads.

So the goal here is the opposite of feature creep:

- **Plays video only** — no audio-player mode, no image viewer.
- **Reliable next/previous** across a whole folder, regardless of `.mp4` vs
  `.mkv` (ExoPlayer handles both containers natively).
- **No fancy "supersonic" controls** — just play/pause, a seek bar, and
  next/previous, plus a small rotation-mode button.
- **As small as possible**, no ads, no tracking, no analytics.
- **Open source** (MIT), so anyone can audit it and build it themselves.

## Why two flavors?

Google Play's policy explicitly **does not allow** generic video players to
request `MANAGE_EXTERNAL_STORAGE` ("All files access"). MX Player can request
it because it was grandfathered in; a brand-new submission almost certainly
gets rejected for it.

To stay honest with both audiences, drejo player ships in two builds:

- The **Play** flavor uses only the Storage Access Framework (a system folder
  picker the user grants once per folder). No permission is asked at install
  or runtime. This is what you'll find on Google Play.
- The **F-Droid** flavor optionally requests `MANAGE_EXTERNAL_STORAGE`. On
  first launch it shows a non-blocking dialog ("Open settings" / "Not now").
  If you grant it, the app no longer needs SAF and behaves like MX Player:
  open any video from any file manager and next/previous just works,
  including for hidden folders (names starting with `.`) that the system
  media scanner ignores.

If you decline the permission on the F-Droid build, it falls back to the same
SAF flow as the Play build.

## How it works

ExoPlayer's playlist handles the hard part: every video in the chosen folder
is added to one playlist, so the built-in next/previous buttons "just work"
across mixed `.mp4` + `.mkv` content. A broken/unsupported file is skipped
instead of freezing playback.

You can start the player three ways:

1. **Pick a folder** inside the app (Storage Access Framework). The folder
   grant is remembered, so next time it resumes where you were.
2. **"Open with"** from any file manager (e.g. CX File Explorer, EX File
   Explorer, Samsung My Files). On the F-Droid build with all-files access
   granted this is the entire interaction; on the Play build a small banner
   prompts you to grant the folder once.
3. From the launcher, which resumes the last folder you used.

A bottom-centre **rotation button** under the play / next / previous row
cycles through three modes: AUTO (full sensor — overrides the system rotation
lock for this app, like YouTube and VLC), forced landscape, and forced
portrait. The choice is remembered.

## Build

The app is compiled inside Docker (no Android toolchain is installed on the
host). The resulting APK is then installed and run on a real device.

```bash
# 1. Build the build image (installs JDK 17, Android SDK 35, Gradle 8.9).
./dockerbuild.sh

# 2. Compile the APK (output is written back to the host via the volume mount).
./indockerbuild.sh debug play     # Play flavor (SAF only)
./indockerbuild.sh debug fdroid   # F-Droid flavor (with MANAGE_EXTERNAL_STORAGE)
./indockerbuild.sh release play   # Release Play APK
./indockerbuild.sh release fdroid # Release F-Droid APK

# 3. Install on a connected device.
adb install -r app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk
```

### Project facts

| | |
|---|---|
| Language | Kotlin |
| UI | View-based (no Compose, to keep the APK small) |
| Media engine | AndroidX Media3 / ExoPlayer |
| `minSdk` | 29 (Android 10) |
| `targetSdk` / `compileSdk` | 35 |
| Permissions (Play) | none dangerous; uses SAF + granted content URIs |
| Permissions (F-Droid) | `MANAGE_EXTERNAL_STORAGE` (optional, user-toggled) |

## Usage

1. **F-Droid build:** install, accept "Open settings" on the first-run
   dialog, flip the "Allow access to manage all files" toggle. From then on,
   tap any video in any file manager → drejo player opens, plays, and
   next/previous works through the whole folder.
2. **Play build:** launch the app, tap **Pick a folder**, choose your video
   folder. The first video starts playing; use **⏮ / ⏭** to navigate.
   Alternatively, open any video from a file manager via **Open with → drejo
   player**, then tap the bottom banner once to grant that folder.

## Contributing

Issues and pull requests are welcome. Please keep the project's spirit:
small, single-purpose, no ads, no tracking. Discuss larger features in an
issue first.

## License

[MIT](LICENSE).
