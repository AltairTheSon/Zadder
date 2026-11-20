# TV Browser (Google TV) with Brave's adblock-rust

Light Android TV browser: system WebView + Brave's `adblock-rust` engine (via JNI).

## Build the APK on GitHub (no Android Studio needed)
1. Create a new GitHub repo, upload everything in this folder (keep the `.github` folder).
2. Open the repo > Actions tab > "Build APK" runs automatically (or press "Run workflow").
3. When it finishes (about 8-12 min), open the run and download the `tvbrowser-apk` artifact (zip containing `app-debug.apk`).

## Install on the TV box
- Easiest: put the APK on a cloud link/USB, install the "Downloader" app on the TV, enable "Install unknown apps" for it, open the APK.
- Or with a computer on the same network: enable Developer options + network debugging on the TV, then
  `adb connect <tv-ip>:5555` and `adb install app-debug.apk`.

## Remote controls
- D-pad: move the pointer smoothly (it speeds up the longer you hold). Push against the top/bottom edge to scroll.
- OK: click. Hold OK for about a second: scroll mode (arrows scroll the page, OK returns to the pointer).
- Double-tap UP: jump the pointer to the toolbar. Double-tap DOWN (in the toolbar): jump back.
- Double-tap LEFT / RIGHT: jump the pointer to that screen edge; double-tap the other way to return.
- Full screen video: the pointer hides. Arrows go to the player, OK = play/pause, hold OK = show/hide the pointer.
- Back: close panel / go back / close tab. Press Back twice quickly for the Exit popup. Menu or Search key (if present): address bar.
- Home page = your favorites (add sites with + Fav in the toolbar).
- Toolbar: Back, Fwd, Reload, Home, address bar, Tabs, Favorites, +Fav, shield (Aggr / Std / OFF).
