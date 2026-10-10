@echo off
rem LOOM live dashboard: shows the chiplets and the Weaver working, live, from the phone over USB.
rem Usage: double-click (phone plugged in, USB debugging on, LOOM open).
rem        live_dashboard.bat --demo   (simulated data for rehearsal, no phone)
cd /d "%~dp0"
if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
where python >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Python not found. Install Python 3 from python.org and tick "Add to PATH".
  pause
  exit /b 1
)
python tools\live_dashboard.py %*
pause
