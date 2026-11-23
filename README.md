# Sony Ka Sender

Flick files from your Android phone to your Windows PC over your own Wi-Fi. Nothing goes through the internet. A small receiver runs on the PC and shows files landing on a "desk" window.

- **Phone app:** `android/` (plain Android, no Gradle). Share or flick a photo/video/file to the PC.
- **PC receiver:** `pc/server.js` (Node.js, no dependencies) plus a desk window opened in Edge.

## Download

On the [Releases page](../../releases/latest):
- `SonyKaSender.apk` for the phone (Android 10 or newer). It is signed with a debug key, so Android will ask you to allow installs from your browser or file manager.
- `Sony-Ka-Sender-PC-1.0.0.zip` for the PC. Unzip it, install [Node.js](https://nodejs.org) once, then double-click `Sony Ka Sender.vbs`.

Files received on the PC are saved to `Downloads\Sony Ka Sender` (change with the `SKS_DIR` environment variable). Phone and PC must be on the same Wi-Fi. The PC listens on port 7777; allow it in Windows Firewall when asked.

## Build the APK yourself

`tools/build-apk.sh` builds with the plain Android SDK tools. Edit the SDK and JDK paths at the top of the script, then run it in Git Bash. The first run creates its own debug keystore.

## Layout

```
android/   manifest, resources, Java sources, web UI (assets/index.html)
pc/        server.js receiver, desk/ window UI, launcher scripts
tools/     build-apk.sh, make_icon.py, shot.sh
```

License: MIT.
