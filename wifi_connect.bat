@echo off
setlocal EnableExtensions EnableDelayedExpansion
title LOOM - connect the phone over Wi-Fi
cd /d "%~dp0"
rem One-time setup so the live dashboard works with the phone UNPLUGGED (honest heat/battery).
rem 1. Plug the phone in by USB (USB debugging on).
rem 2. Phone Wi-Fi ON, on the SAME network as this laptop (airplane mode can keep Wi-Fi on).
rem 3. Run this. Then unplug the cable and run live_dashboard.bat.
rem Repeat after the phone restarts (Android turns network debugging off on reboot).

if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if not exist "%ADB%" (
  echo [ERROR] adb not found at "%ADB%".
  goto :fail
)

rem --- find the phone on USB (serials with ":" are network devices) ---
set "USB="
for /f "skip=1 tokens=1,2" %%A in ('""%ADB%" devices"') do (
  if "%%B"=="device" (
    echo %%A | findstr ":" >nul || set "USB=%%A"
  )
)
if not defined USB (
  echo [ERROR] No phone on USB. Plug in the iQOO with USB debugging on and try again.
  goto :fail
)
echo [ok] phone on USB: %USB%

rem --- phone's Wi-Fi address ---
rem (no pipes: everything after "adb shell" runs on the phone, so the laptop parses the output)
set "IP="
set "CIDR="
for /f "tokens=1,2" %%A in ('""%ADB%" -s %USB% shell ip -f inet addr show wlan0"') do (
  if "%%A"=="inet" set "CIDR=%%B"
)
rem fallback: any non-loopback IPv4 address on any interface
if not defined CIDR (
  for /f "tokens=1,2" %%A in ('""%ADB%" -s %USB% shell ip -f inet addr show"') do (
    if "%%A"=="inet" (
      echo %%B | findstr /b "127." >nul || set "CIDR=%%B"
    )
  )
)
if defined CIDR for /f "delims=/" %%B in ("%CIDR%") do set "IP=%%B"
if not defined IP (
  echo [ERROR] The phone has no Wi-Fi address.
  echo         Turn Wi-Fi ON on the phone ^(it can stay in airplane mode^) and join the same network as this laptop.
  goto :fail
)
echo [ok] phone Wi-Fi address: %IP%

rem --- switch adb to network mode and connect (drop any stale entry first) ---
"%ADB%" disconnect %IP%:5555 >nul 2>&1
"%ADB%" -s %USB% tcpip 5555 >nul
timeout /t 3 /nobreak >nul
"%ADB%" connect %IP%:5555
timeout /t 2 /nobreak >nul
rem Only trust it if adb now lists the Wi-Fi address as a working "device" (not "offline").
set "OK="
for /f "skip=1 tokens=1,2" %%A in ('""%ADB%" devices"') do (
  if "%%A"=="%IP%:5555" if "%%B"=="device" set "OK=1"
)
if not defined OK (
  echo [ERROR] Could not connect to %IP%:5555.
  echo         Is the laptop on the same Wi-Fi? Some venue networks block device-to-device traffic -
  echo         then use the laptop's Mobile hotspot and join the phone to it.
  goto :fail
)
> .phone_wifi echo %IP%:5555
echo.
echo [ok] Connected over Wi-Fi. Address saved to .phone_wifi
echo      You can UNPLUG the cable now, then run live_dashboard.bat.
pause
exit /b 0

:fail
echo.
pause
exit /b 1
