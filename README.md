# Phone Mic Bridge

Use your Android phone's microphone as your Windows PC's microphone, over the
phone's hotspot or any shared Wi-Fi.

```
Phone mic -> Android app -> (Wi-Fi / hotspot, TCP 5555) -> Windows app -> VB-CABLE -> apps see "CABLE Output" as a mic
```

Nothing is sent to the internet. Audio stays on your local network.

## 1. Windows receiver

1. Install **Python 3.10+** from python.org (tick "Add Python to PATH").
2. Install **VB-CABLE** (free) from https://vb-audio.com/Cable/ and reboot if asked.
3. In `windows/`, double-click `build_exe.bat`. It produces `windows\dist\PhoneMicBridge.exe`.
4. Run `PhoneMicBridge.exe`. The first time, allow it through Windows Firewall on **Private** networks.
5. The window shows a **pairing code** (6 digits) and the **phone address** (e.g. `192.168.43.1:5555`). The code is saved in `config.json` next to the exe, so it stays the same each run.
6. In the app you want to use the mic (Discord, Zoom, OBS, Windows Settings), choose **CABLE Output** as the microphone.

To use a different output device, edit `config.json` and set `"device"` to part of its name.

## 2. Android app

1. Install **Android Studio** (https://developer.android.com/studio).
2. Open the `android/` folder as a project. Let Gradle sync. Android Studio creates the Gradle wrapper automatically.
3. Build an APK: **Build > Build Bundle(s) / APK(s) > Build APK(s)**. The file appears at `android/app/build/outputs/apk/debug/app-debug.apk`.
4. Copy the APK to the phone and install it. Allow "Install unknown apps" for your file manager when asked.

## 3. Connect

1. Turn on your phone's hotspot. Connect the PC to it (or put both on the same Wi-Fi).
2. Open **Phone Mic** on the phone. Enter the **phone address** and **pairing code** from the PC window, then tap **Start** and allow microphone access.
3. The status should read "Streaming to ...". Speak; the audio should show up as the CABLE Output mic.

The phone app remembers the address, port, and code. Next time, just tap Start.
A notification stays in the status bar while streaming. Tap **Stop** in it, or in the app, to stop.

## If the address changes

Hotspots often give the PC a new address. Read the new address from the PC window and update the phone's address field. The app can't find it automatically yet.

## Troubleshooting

- **"Can't reach computer"**: Check that both devices are on the same hotspot or Wi-Fi, and that Windows Firewall allows PhoneMicBridge on Private networks. Some phone hotspots block device-to-device traffic. If so, use a home Wi-Fi network instead.
- **"Computer rejected the pairing code"**: Re-enter the code shown on the PC.
- **No sound in the other app**: Make sure that app's microphone is set to **CABLE Output**, not your real mic.
- **Delay of about a quarter second**: Normal for this design. The receiver keeps latency low, but Wi-Fi adds some delay.
- **Phone address not shown on PC**: Run `ipconfig` in a terminal and use the IPv4 address of the hotspot or Wi-Fi adapter.

## Limits in this first version

- Mono audio at 16 kHz (speech quality, not music quality).
- One phone at a time.
- You must type the address when it changes.
- Not tested on every phone model or Windows setup. Please report what you see.
