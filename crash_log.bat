@echo off
rem Saves the phone's crash log for LOOM into crash_log.txt (phone plugged in, USB debugging on).
cd /d "%~dp0"
if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
"%ADB%" logcat -c
echo Launching LOOM so the crash happens again...
"%ADB%" shell am start -n com.loom.runtime/.MainActivity >nul
timeout /t 6 /nobreak >nul
> crash_log.txt echo ===== CRASH BUFFER =====
"%ADB%" logcat -d -b crash >> crash_log.txt
>> crash_log.txt echo ===== LOOM / ERRORS =====
"%ADB%" logcat -d -v time AndroidRuntime:E LOOM:V LOOM_EVT:V LOOM_TRX:V CameraX:W Camera2CameraImpl:W tflite:W libc:F DEBUG:F *:S >> crash_log.txt
>> crash_log.txt echo ===== APP VERSION =====
"%ADB%" shell dumpsys package com.loom.runtime | findstr /i "versionName lastUpdateTime" >> crash_log.txt
echo.
echo Saved crash_log.txt - tell Claude it is ready.
pause
