@echo off
setlocal EnableExtensions EnableDelayedExpansion
title LOOM - pull run logs from the phone
cd /d "%~dp0"
if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if not exist "%ADB%" (
  echo [ERROR] adb not found at "%ADB%".
  goto :end
)

rem --- pick the phone: USB first, then Wi-Fi (an "offline" Wi-Fi entry is ignored) ---
set "DEV="
for /f "skip=1 tokens=1,2" %%A in ('""%ADB%" devices"') do (
  if "%%B"=="device" (
    echo %%A | findstr ":" >nul || if not defined DEV set "DEV=%%A"
  )
)
if not defined DEV if exist .phone_wifi (
  set /p WIFI=<.phone_wifi
  "%ADB%" disconnect !WIFI! >nul 2>&1
  "%ADB%" connect !WIFI! >nul 2>&1
  for /f "skip=1 tokens=1,2" %%A in ('""%ADB%" devices"') do (
    if "%%A"=="!WIFI!" if "%%B"=="device" set "DEV=!WIFI!"
  )
)
if not defined DEV (
  echo [ERROR] No phone found. Plug in the iQOO by USB ^(USB debugging on^) and run this again.
  goto :end
)
echo [ok] phone: %DEV%

if not exist csv mkdir csv
"%ADB%" -s %DEV% pull /sdcard/Android/data/com.loom.runtime/files/. csv
echo.
echo Run logs on this laptop (csv folder):
dir /b /o:d csv\*.csv 2>nul || echo   (none found)
echo.
echo The dashboard also saved every live session in the live_logs folder.
:end
pause
