# Priyanshu Launcher (version 1)

A minimal, Niagara-style home screen for Android (built for a Moto G64, Android 10+).

## What it does
- Big clock and date (tap clock = alarms, tap date = calendar)
- Up to 8 favourite apps in a vertical list, with icons
- A-Z bar on the right edge: tap a letter to jump to apps starting with it
- Swipe up anywhere: search all apps (press Go on the keyboard to open the first result)
- Swipe down: notification shade
- Long-press any app: Add to home / Remove / Move up / Move down / App info / Uninstall
- Your wallpaper shows behind everything

## Build the app file (APK) with GitHub - no software needed
1. Sign in at github.com, click "+" (top right) -> "New repository", name it `priyanshu-launcher`, keep it Private, click "Create repository".
2. On the empty repo page click "uploading an existing file".
3. Unzip this folder on your laptop and drag ALL its contents into the page,
   including the hidden `.github` folder (on Mac press Cmd+Shift+. in Finder to see it).
4. Click "Commit changes". GitHub starts building automatically.
5. Open the "Actions" tab and wait for the green tick (about 5-10 minutes).
6. On your phone, open the repo -> "Releases" -> "latest" -> download `PriyanshuLauncher.apk`.

## Install on the phone
1. Open the downloaded APK. Allow "Install unknown apps" for your browser/files app when asked.
2. Press the Home button -> choose "Priyanshu Launcher" -> "Always".
   (Or: Settings -> Apps -> Default apps -> Home app -> Priyanshu Launcher.)
3. Swipe up, find your favourite apps, long-press each -> "Add to home".

## Go back to the Moto launcher anytime
Settings -> Apps -> Default apps -> Home app -> Moto App Launcher.

## Quick settings in the code
At the top of `app/src/main/java/com/priyanshu/launcher/MainActivity.kt`:
- `maxFavourites` (default 8)
- `favouriteTextSp` (text size of favourites, default 30)
- `showFavouriteIcons` (true / false)
Edit on GitHub (pencil icon), commit, and a new APK is built automatically.
