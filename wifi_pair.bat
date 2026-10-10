@echo off
setlocal EnableExtensions EnableDelayedExpansion
title LOOM - pair the phone over Wi-Fi (Wireless debugging)
cd /d "%~dp0"
rem Android 11+ "Wireless debugging": pair ONCE with a code. Unlike "adb tcpip 5555" (wifi_connect.bat),
rem it survives Wi-Fi reconnects and the phone finds the laptop again by itself (mDNS), no USB needed.
rem
rem On the iQOO: Settings > System > Developer options > Wireless debugging > ON
rem   (tick "Always allow on this network" when asked), then tap "Pair device with pairing code".
rem Phone and laptop must be on the SAME Wi-Fi, and that Wi-Fi must allow devices to talk to each
rem other (guest/venue networks often block it - then use the laptop's Mobile hotspot).

if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if not exist "%ADB%" (
  echo [ERROR] adb not found at "%ADB%".
  goto :fail
)
set "ADB_MDNS_OPENSCREEN=1"
set "ADB_MDNS_AUTO_CONNECT=adb-tls-connect"
"%ADB%" kill-server >nul 2>&1
"%ADB%" start-server >nul 2>&1

echo On the phone: Wireless debugging ^> "Pair device with pairing code".
echo It shows an IP address:port and a 6-digit code.
echo.
set /p "PAIRADDR=IP address:port shown under the code (e.g. 192.168.1.23:37199): "
set /p "PAIRCODE=6-digit Wi-Fi pairing code: "
"%ADB%" pair %PAIRADDR% %PAIRCODE%
if errorlevel 1 (
  echo [ERROR] Pairing failed. Check the code ^(it changes each time^) and that both are on the same Wi-Fi.
  goto :fail
)
echo.
echo Now look at the MAIN Wireless debugging screen on the phone: "IP address ^& Port" ^(a different port^).
set /p "CONNADDR=IP address & Port from the main screen (e.g. 192.168.1.23:41235): "
"%ADB%" connect %CONNADDR%
timeout /t 2 /nobreak >nul
set "OK="
for /f "skip=1 tokens=1,2" %%A in ('""%ADB%" devices"') do (
  if "%%B"=="device" (
    echo %%A | findstr ":" >nul && set "OK=%%A"
    echo %%A | findstr "_adb-tls-connect" >nul && set "OK=%%A"
  )
)
if not defined OK (
  echo [ERROR] Paired, but could not connect. Re-check the address on the main Wireless debugging screen.
  goto :fail
)
> .phone_wifi echo %CONNADDR%
echo.
echo [ok] Connected over Wi-Fi as !OK!. Saved to .phone_wifi
echo      From now on live_dashboard.bat finds the phone by itself on this network ^(mDNS^),
echo      even after Wi-Fi reconnects. If the port changes, the dashboard rediscovers it.
pause
exit /b 0

:fail
echo.
pause
exit /b 1
