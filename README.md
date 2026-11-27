# SX130IS-VF

Android viewfinder for a Canon PowerShot SX130 IS running CHDK, over USB (OTG) using CHDK's PTP extension.

## Status
Milestone 1: connect, open a PTP session, query CHDK `Version`, request one live view frame, log its
header (`fb_type`, dimensions, offsets) and save the raw blob to `lv.bin`.

Next: colour decode, CHDK overlay, focus peaking, render loop.

## Build
Push to GitHub; the Actions workflow builds a debug APK. Download it from the run's artifacts.

## Notes
CHDK PTP subcommand numbers have been verified against CHDK's `ptp.h`:
- `PTP_CHDK_Version` = 0, `PTP_CHDK_GetDisplayData` = 10 (0x0A).
- `LV_TFR` flags: viewport=1, bitmap=4, palette=8 (from `liveview.h`).
Camera must be in shooting mode with the lens out.

