# MULTIVERSE X — progress

Status is only marked **Done** after it builds on GitHub Actions; items marked **Needs phone test** have not yet been checked on the Moto G64.

## Milestone 1 — 3D home screen (this build)

| Feature | Status | Notes |
| --- | --- | --- |
| GPU 3D environment "Reactor Core" (ray-marched plasma core, 3 counter-rotating segmented rings, gyroscope cage, orbiting shards, holographic hex floor with pulse waves, light pillars, dust) | Built, shader verified | Shader compiled and rendered in WebGL2 (same GLSL ES 3.00 language) at 3 quality levels; needs phone test for speed |
| Gyroscope parallax (camera tilts with the phone) | Needs phone test | Game rotation vector sensor, accelerometer fallback |
| Touch shockwave through the environment | Needs phone test | Every tap on the home screen |
| Charging surge (shader glow + burst when the charger is plugged in) | Needs phone test | |
| Graphics modes: Cinematic High (video, 60 fps), Cinematic Balanced (video, 30 fps, default), Battery saver (video, 24 fps, no gyro), Real-time 3D (live ray-marching, heavy) | Needs phone test | Rendering and video stop when the launcher is hidden |
| Cinematic video engine: the Reactor Core scene pre-rendered at full quality as a seamless 12-second loop (720 x 1600, H.264), played by the hardware decoder; a light shader adds live parallax, touch shockwave, charging glow and universe recolouring | Needs phone test | Fix for the lag reported on the Moto G64 in build 5 |
| Holographic HUD: clock with glow and scan beam, 60-tick seconds track, date, status line, battery gauge | Needs phone test | Tap clock = alarms, date = calendar, gauge = battery usage |
| 3D orbit of up to 8 favourite apps around the reactor (depth scaling, drag to spin with momentum, tap to open, long-press menu) | Needs phone test | |
| Command dock with 5 slots | Needs phone test | Long-press any app, "Add to dock" |
| Launch burst + return implosion; app opens with scale-up from its icon at the same moment | Needs phone test | Effect never delays the launch |
| Portal drawer opening (circular reveal) | Needs phone test | |
| Universe colours drive the 3D scene (all 10 earlier themes + new Holo Core) | Needs phone test | |
| Switch between 3D environment and the earlier 2D effects | Needs phone test | |
| Preserved: app list, search, A to Z bar, swipe up/down, app info, uninstall, Classic wallpaper theme, animations toggle | Needs phone test | |

## Not built yet (honest list)

- Themed icon engine (metallic, glass, crystal, mechanical, cosmic, alien styles); icons are the apps' real icons inside holographic emitters
- More environments (target: 30 distinct ones) and the Wallpaper Studio; live wallpaper service; lock-screen wallpaper
- Folders, widgets (AppWidgetHost), app shortcuts, notification effects (needs Notification Access), weather (needs a weather source and location)
- Jarvis assistant, schedules, accessibility pass, backup and restore

## Technical limits found

- Build 5 lagged on the Moto G64: live ray-marching of the full machine is too heavy for its mid-range graphics chip, and the busy GPU also slowed the touch layer. Fix: the scene is pre-rendered into a looping video (decoded by dedicated hardware), live effects are a single cheap shader pass, and icon glows are cached instead of rebuilt every frame. Real-time mode is kept as an option for stronger phones.

- A normal launcher cannot animate inside another app's window (system permission only), so launch effects play on the launcher layer while the app opens.
- Google's Android SDK servers are blocked from the build sandbox, so Android builds run on GitHub Actions; shaders are checked locally in WebGL2.

## Next task

Phone test of Milestone 1 on the Moto G64 (frame rate in Balanced and High), then Milestone 2: themed icon engine.
