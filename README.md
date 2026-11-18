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
- D-pad: move the mouse pointer. Hold at the screen edge to scroll the page.
- OK: click. Back: go back / close keyboard / exit.
- Menu or Search key (if your remote has it): jump to the address bar.
- Hold UP for ~1 second near the top while the page is scrolled: jump pointer to the toolbar.
- Toolbar: Back, Fwd, Reload, Home, address bar, Ads ON/OFF (toggles blocking, shows blocked count).
