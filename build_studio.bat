@echo off
setlocal EnableExtensions EnableDelayedExpansion
title LOOM Studio build
cd /d "%~dp0"

rem LOOM Studio (Plan B): one click = build debug APK, install on the iQOO, launch, check telemetry.
rem Everything is logged to loom_studio_logs\ so Claude can read the result.
rem Usage: double-click (phone on USB, USB debugging on).   build_studio.bat --nodash  skips the dashboard prompt.

set "LOGS=loom_studio_logs"
if not exist "%LOGS%" mkdir "%LOGS%"
set "SUMMARY=%LOGS%\last_run.txt"
> "%SUMMARY%" echo LOOM Studio build run %DATE% %TIME%

echo ============================================================
echo  LOOM Studio - build debug APK, install, launch, check LOOM_LIVE
echo ============================================================

rem ---------- 0. Git history (only if this folder came without .git; Claude ships it as loom_repo.bundle) ----------
if not exist ".git" if exist "loom_repo.bundle" (
  where git >nul 2>nul
  if errorlevel 1 (
    echo [warn] git not on PATH - skipping git setup, the build still works
  ) else (
    git init -q
    git fetch -q loom_repo.bundle "refs/heads/*:refs/heads/*"
    git symbolic-ref HEAD refs/heads/plan-b-studio
    git reset -q
    git remote add origin https://github.com/varuntej0007/LOOM.git
    call :log PASS "git repo restored from loom_repo.bundle, branch plan-b-studio"
  )
)

rem ---------- 1. Java: prefer Android Studio's bundled JDK ----------
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" goto :java_ok
for %%D in (
  "%ProgramFiles%\Android\Android Studio\jbr"
  "%ProgramFiles%\Android\Android Studio\jre"
  "%LOCALAPPDATA%\Programs\Android Studio\jbr"
  "%LOCALAPPDATA%\Programs\Android Studio\jre"
) do (
  if exist "%%~D\bin\java.exe" (
    set "JAVA_HOME=%%~D"
    goto :java_ok
  )
)
for /d %%D in ("%USERPROFILE%\.jdks\*17*" "%USERPROFILE%\.jdks\*21*") do (
  if exist "%%~D\bin\java.exe" set "JAVA_HOME=%%~D"
)
if not defined JAVA_HOME (
  call :log FAIL "no JDK found - install Android Studio or set JAVA_HOME to a JDK 17+"
  goto :fail
)
:java_ok
call :log PASS "JAVA_HOME=%JAVA_HOME%"

rem ---------- 2. Android SDK ----------
if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not exist "%ANDROID_HOME%\platform-tools\adb.exe" (
  call :log FAIL "Android SDK / adb not found at %ANDROID_HOME%"
  goto :fail
)
set "SDKDIR=%ANDROID_HOME:\=/%"
> local.properties echo sdk.dir=%SDKDIR%
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
call :log PASS "ANDROID_HOME=%ANDROID_HOME%"

rem ---------- 3. Gradle distribution (the wrapper expects the zip next to it) ----------
set "GZIP=gradle\wrapper\gradle-8.11.1-bin.zip"
if not exist "%GZIP%" (
  echo [..] downloading Gradle 8.11.1 ^(~130 MB, first run only^)
  curl -L --fail --retry 3 -o "%GZIP%.part" "https://services.gradle.org/distributions/gradle-8.11.1-bin.zip"
  if errorlevel 1 (
    del "%GZIP%.part" 2>nul
    call :log FAIL "could not download Gradle 8.11.1 - check internet and run again"
    goto :fail
  )
  move /y "%GZIP%.part" "%GZIP%" >nul
)
call :log PASS "Gradle 8.11.1 zip present"

rem ---------- 4. Build ----------
echo [..] building :studio debug APK. First run downloads AGP 8.7.3, SDK 35 and libraries ^(several minutes^).
echo      Full log: %LOGS%\gradle_build.log
set "GRADLE_OPTS=-Djava.net.preferIPv4Stack=true"
call gradlew.bat --no-daemon --console=plain :studio:assembleDebug > "%LOGS%\gradle_build.log" 2>&1
if errorlevel 1 (
  call :log FAIL "gradle :studio:assembleDebug failed - see %LOGS%\gradle_build.log"
  echo ---------------- last 40 lines ----------------
  powershell -NoProfile -Command "Get-Content '%LOGS%\gradle_build.log' -Tail 40"
  goto :fail
)
set "APK=studio\build\outputs\apk\debug\studio-debug.apk"
if not exist "%APK%" (
  call :log FAIL "build OK but %APK% missing"
  goto :fail
)
copy /y "%APK%" "LOOM_Studio-debug.apk" >nul
call :log PASS "APK built: LOOM_Studio-debug.apk"

rem ---------- 5. Phone ----------
set "DEVICE="
set /a TRIES=0
:wait_device
for /f "skip=1 tokens=1,2" %%A in ('""%ADB%" devices"') do (
  if "%%B"=="device" set "DEVICE=%%A"
  if "%%B"=="unauthorized" set "UNAUTH=1"
)
if not defined DEVICE (
  if !TRIES! EQU 0 echo [..] waiting for the iQOO: plug in USB, enable USB debugging, tap Allow on the phone ^(up to 5 min^)
  if defined UNAUTH if !TRIES! EQU 0 echo      the phone is connected but not authorised - tap Allow on the phone
  set /a TRIES+=1
  if !TRIES! GTR 60 (
    call :log FAIL "no phone detected - APK is at LOOM_Studio-debug.apk, install it manually"
    goto :fail
  )
  timeout /t 5 /nobreak >nul
  goto :wait_device
)
call :log PASS "phone connected: %DEVICE%"

echo [..] installing on %DEVICE%
"%ADB%" -s %DEVICE% install -r "LOOM_Studio-debug.apk" > "%LOGS%\install.log" 2>&1
if errorlevel 1 (
  echo [..] install failed - removing an older com.loom.studio build and retrying
  "%ADB%" -s %DEVICE% uninstall com.loom.studio >nul 2>&1
  "%ADB%" -s %DEVICE% install "LOOM_Studio-debug.apk" >> "%LOGS%\install.log" 2>&1
  if errorlevel 1 (
    call :log FAIL "adb install failed - on the iQOO enable 'Install via USB' in Developer options; see %LOGS%\install.log"
    type "%LOGS%\install.log"
    goto :fail
  )
)
call :log PASS "installed com.loom.studio"

rem Notification permission up front (Android 13+), so the run-service notification is visible.
"%ADB%" -s %DEVICE% shell pm grant com.loom.studio android.permission.POST_NOTIFICATIONS >nul 2>&1

"%ADB%" -s %DEVICE% logcat -c >nul 2>&1
"%ADB%" -s %DEVICE% shell am start -n com.loom.studio/.MainActivity >nul
call :log PASS "launched LOOM Studio"

rem ---------- 6. Telemetry check: expect ~1 LOOM_LIVE line per second ----------
echo [..] checking telemetry for 12 s
timeout /t 12 /nobreak >nul
"%ADB%" -s %DEVICE% logcat -d -v tag LOOM_LIVE:I LOOM_EVT:I LOOM_TRX:I AndroidRuntime:E *:S > "%LOGS%\logcat_check.txt" 2>&1
set /a LIVE=0
for /f %%N in ('findstr /c:"LOOM_LIVE" "%LOGS%\logcat_check.txt" ^| find /c /v ""') do set /a LIVE=%%N
findstr /c:"FATAL EXCEPTION" "%LOGS%\logcat_check.txt" >nul && (
  call :log FAIL "app crashed - see %LOGS%\logcat_check.txt"
  powershell -NoProfile -Command "Get-Content '%LOGS%\logcat_check.txt' -Tail 30"
  goto :fail
)
if !LIVE! LSS 5 (
  call :log FAIL "only !LIVE! LOOM_LIVE lines in 12 s - see %LOGS%\logcat_check.txt"
  goto :fail
)
call :log PASS "telemetry OK: !LIVE! LOOM_LIVE lines in 12 s"
echo ---------------- last LOOM_LIVE line ----------------
powershell -NoProfile -Command "(Select-String -Path '%LOGS%\logcat_check.txt' -Pattern 'LOOM_LIVE' | Select-Object -Last 1).Line"

echo.
echo ALL CHECKS PASSED. Summary: %SUMMARY%
if /i "%~1"=="--nodash" goto :done
echo.
choice /c YN /m "Start the live dashboard now (Ctrl+C to stop it)"
if errorlevel 2 goto :done
where python >nul 2>nul
if errorlevel 1 (
  call :log FAIL "python not on PATH - cannot start the dashboard"
  goto :done
)
python tools\live_dashboard.py

:done
echo.
pause
exit /b 0

:fail
echo.
echo STOPPED. Tell Claude: "build_studio failed" - the logs are in %LOGS%\
pause
exit /b 1

:log
echo [%~1] %~2
>> "%SUMMARY%" echo [%~1] %~2
exit /b 0
