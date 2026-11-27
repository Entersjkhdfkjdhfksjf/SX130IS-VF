# SX130IS-VF

Android viewfinder for a Canon PowerShot SX130 IS running CHDK, over USB (OTG) using CHDK's PTP extension.

## Status
- Milestone 1 (done): USB PTP connect, `OpenSession`, CHDK `Version`, automatic `switch_mode_usb(1)` into shooting mode, and live view frame capture.
- Milestone 2 (done): continuous `GetDisplayData` render loop with `LV_FB_YUV8` (`UYVYYY`, full `720x240` viewport samples scaled to display aspect), toggleable CHDK OSD overlay (`LV_FB_PAL8` with live RAM `palette_type=3` lookup when CHDK reports `palette_type=0`), and luma focus peaking.

## Build
Push to GitHub; the Actions workflow builds a debug APK. Download it from the run's artifacts.

## Notes
CHDK PTP subcommand numbers verified against CHDK's `ptp.h`:
- `PTP_CHDK_Version` = 0
- `PTP_CHDK_GetMemory` = 1 (used on SX130 IS to read `palette_buffer[active_palette_buffer] + 8` when CHDK returns `palette_type = 0`)
- `PTP_CHDK_ExecuteScript` = 7 (used to run `switch_mode_usb(1)` if USB connect switched to playback)
- `PTP_CHDK_ScriptStatus` = 8, `PTP_CHDK_ReadScriptMsg` = 10
- `PTP_CHDK_GetDisplayData` = 12 (0x0C)
- `LV_TFR` flags: viewport=1, bitmap=4, palette=8 (from `live_view.h`).
- Live view resolution note: Canon SX130 IS DIGIC 4 hardware writes `360` chroma pairs (`720` Y samples) × `240` rows to the LCD live viewport buffer; CHDK PTP live view cannot stream 720p/full-sensor video.

