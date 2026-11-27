# SX130IS-VF

Android viewfinder for a Canon PowerShot SX130 IS running CHDK, over USB (OTG) using CHDK's PTP extension.

## Status
Milestone 1: connect, open a PTP session, query CHDK `Version`, request one live view frame, log its
header (`fb_type`, dimensions, offsets) and save the raw blob to `lv.bin`.

Next: colour decode, CHDK overlay, focus peaking, render loop.

## Build
Push to GitHub; the Actions workflow builds a debug APK. Download it from the run's artifacts.

## Notes
CHDK PTP subcommand numbers verified against CHDK's `ptp.h`:
- `PTP_CHDK_Version` = 0
- `PTP_CHDK_ExecuteScript` = 7 (used to run `switch_mode_usb(1)` if USB connect switched to playback)
- `PTP_CHDK_ScriptStatus` = 8, `PTP_CHDK_ReadScriptMsg` = 10
- `PTP_CHDK_GetDisplayData` = 12 (0x0C)
- `LV_TFR` flags: viewport=1, bitmap=4, palette=8 (from `live_view.h`).

