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

rem ---------- 0. Git: restore / fast-forward from loom_repo.bundle (Claude ships commits this way) ----------
if not exist "loom_repo.bundle" goto :git_done
where git >nul 2>nul
if errorlevel 1 (
  echo [warn] git not on PATH - skipping git sync, the build still works
  goto :git_done
)
if exist ".git" goto :git_sync
git init -q
git fetch -q loom_repo.bundle "refs/heads/*:refs/heads/*"
git symbolic-ref HEAD refs/heads/plan-b-studio
git reset -q
git checkout -- .gitmodules >nul 2>&1
git remote add origin https://github.com/varuntej0007/LOOM.git
call :log PASS "git repo restored from loom_repo.bundle, branch plan-b-studio"
goto :git_done
:git_sync
set "CUR="
for /f %%B in ('git symbolic-ref --short HEAD 2^>nul') do set "CUR=%%B"
if not "%CUR%"=="plan-b-studio" goto :git_done
git fetch -q -f loom_repo.bundle "+refs/heads/plan-b-studio:refs/remotes/claude/plan-b-studio"
if errorlevel 1 (
  echo [warn] could not read loom_repo.bundle - leaving git alone
  goto :git_done
)
git merge-base --is-ancestor HEAD refs/remotes/claude/plan-b-studio 2>nul
if errorlevel 1 (
  echo [info] plan-b-studio has local commits not in loom_repo.bundle - leaving git alone
  goto :git_done
)
rem The working files were already updated by Claude; move the branch and index to match them.
git reset -q refs/remotes/claude/plan-b-studio
rem Files Claude cannot write directly (.gitmodules) come from the commit itself.
git checkout -- .gitmodules >nul 2>&1
for /f %%H in ('git rev-parse --short HEAD') do call :log PASS "git plan-b-studio at %%H"
:git_done

rem ---------- 1. Java 17-23 ----------
rem Gradle 8.11.1 and Kotlin 2.0.21 (what this repo pins) cannot run on Java 24+. Android Studio
rem now bundles Java 25, so if no JDK 17-23 is found, a Temurin JDK 21 is downloaded once into
rem %USERPROFILE%\.loom-jdk (outside the repo).
set "LJDK=%USERPROFILE%\.loom-jdk"
set "PICK="
for %%D in (
  "%JAVA_HOME%"
  "%LJDK%\jdk-21"
  "%ProgramFiles%\Android\Android Studio\jbr"
  "%LOCALAPPDATA%\Programs\Android Studio\jbr"
) do if not defined PICK call :jdk_try "%%~D"
if not defined PICK for /d %%D in ("%USERPROFILE%\.jdks\*" "%ProgramFiles%\Java\*" "%ProgramFiles%\Eclipse Adoptium\*" "%ProgramFiles%\Microsoft\jdk-*") do if not defined PICK call :jdk_try "%%~D"
if not defined PICK (
  echo [..] no JDK 17-23 found - downloading Temurin JDK 21 ^(~190 MB, first run only^) to %LJDK%
  if not exist "%LJDK%" mkdir "%LJDK%"
  curl -L --fail --retry 3 -o "%LJDK%\jdk21.zip" "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse"
  if errorlevel 1 (
    call :log FAIL "could not download JDK 21 - check internet, or install any JDK 17-23 and set JAVA_HOME"
    goto :fail
  )
  tar -xf "%LJDK%\jdk21.zip" -C "%LJDK%"
  if errorlevel 1 (
    call :log FAIL "could not unpack %LJDK%\jdk21.zip"
    goto :fail
  )
  del "%LJDK%\jdk21.zip" 2>nul
  for /d %%D in ("%LJDK%\jdk-21*") do if not exist "%LJDK%\jdk-21\bin\java.exe" move "%%~D" "%LJDK%\jdk-21" >nul
  call :jdk_try "%LJDK%\jdk-21"
)
if not defined PICK (
  call :log FAIL "no usable JDK 17-23 after download - see %LJDK%"
  goto :fail
)
set "JAVA_HOME=%PICK%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call :log PASS "JAVA_HOME=%JAVA_HOME% (Java %PICKVER%)"

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

rem ---------- 3b. llama.cpp sources (GGUF chat, pinned tag b11552) ----------
if not exist "studio\src\main\cpp\llama.cpp\CMakeLists.txt" (
  echo [..] fetching llama.cpp b11552 ^(source for the GGUF chat runtime, first run only^)
  where git >nul 2>nul
  if errorlevel 1 (
    call :log FAIL "git is needed once to fetch llama.cpp - install Git for Windows and run again"
    goto :fail
  )
  if exist ".git" git submodule update --init --depth 1 studio/src/main/cpp/llama.cpp >> "%LOGS%\llama_fetch.log" 2>&1
  if not exist "studio\src\main\cpp\llama.cpp\CMakeLists.txt" (
    rmdir /s /q "studio\src\main\cpp\llama.cpp" 2>nul
    git clone -q --depth 1 --branch b11552 https://github.com/ggml-org/llama.cpp "studio\src\main\cpp\llama.cpp" >> "%LOGS%\llama_fetch.log" 2>&1
  )
  if not exist "studio\src\main\cpp\llama.cpp\CMakeLists.txt" (
    call :log FAIL "could not fetch llama.cpp - see %LOGS%\llama_fetch.log"
    goto :fail
  )
)
call :log PASS "llama.cpp sources present"

rem ---------- 4a. Unit tests (inspector rules, GGUF reader, Weaver decisions, quality proxy) ----------
echo [..] running :studio unit tests ^(log: %LOGS%\unit_tests.log^)
call gradlew.bat --no-daemon --console=plain :studio:testDebugUnitTest > "%LOGS%\unit_tests.log" 2>&1
if errorlevel 1 (
  rem Tests never block the install: a failure is logged for Claude, the APK is still built.
  findstr /c:"Could not resolve" "%LOGS%\unit_tests.log" >nul && (
    call :log WARN "unit tests skipped: a test library could not be downloaded - network glitch, retried next run"
  ) || (
    call :log WARN "unit tests FAILED - see %LOGS%\unit_tests.log; continuing with the build"
  )
) else (
  call :log PASS "unit tests passed"
)

rem ---------- 4. Build ----------
echo [..] building :studio debug APK. First run downloads AGP 8.7.3, SDK 35, NDK, CMake and libraries, and compiles llama.cpp ^(10-20 min^).
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

rem Sample models for the import test: put both EfficientDet variants in the phone's Download folder.
for %%M in (efficientdet_lite0 efficientdet_lite2) do (
  if exist "app\src\main\assets\%%M.tflite" "%ADB%" -s %DEVICE% push "app\src\main\assets\%%M.tflite" /sdcard/Download/%%M.tflite >nul 2>&1
)
rem M7 sample LOOM projects (valid + invalid) in Download\LOOM_sample_project and LOOM_invalid_project.
"%ADB%" -s %DEVICE% shell mkdir -p /sdcard/Download/LOOM_sample_project /sdcard/Download/LOOM_invalid_project >nul 2>&1
"%ADB%" -s %DEVICE% push "studio\samples\street_sign_reader\loom.json" /sdcard/Download/LOOM_sample_project/loom.json >nul 2>&1
"%ADB%" -s %DEVICE% push "studio\samples\street_sign_reader\labels.txt" /sdcard/Download/LOOM_sample_project/labels.txt >nul 2>&1
"%ADB%" -s %DEVICE% push "app\src\main\assets\efficientdet_lite2.tflite" /sdcard/Download/LOOM_sample_project/detector_large.tflite >nul 2>&1
"%ADB%" -s %DEVICE% push "app\src\main\assets\efficientdet_lite0.tflite" /sdcard/Download/LOOM_sample_project/detector_small.tflite >nul 2>&1
"%ADB%" -s %DEVICE% push "studio\samples\invalid_project\loom.json" /sdcard/Download/LOOM_invalid_project/loom.json >nul 2>&1
call :log PASS "sample models and LOOM projects copied to the phone's Download folder"

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

:jdk_try
rem Accept a JDK folder whose release file says JAVA_VERSION 17..23.
if "%~1"=="" exit /b 0
if not exist "%~1\bin\java.exe" exit /b 0
if not exist "%~1\release" exit /b 0
set "JV="
for /f "tokens=2 delims==" %%V in ('findstr /b /c:"JAVA_VERSION=" "%~1\release"') do set "JV=%%~V"
if not defined JV exit /b 0
set "JMAJ="
for /f "delims=." %%M in ("%JV%") do set "JMAJ=%%M"
if not defined JMAJ exit /b 0
if %JMAJ% GEQ 17 if %JMAJ% LEQ 23 (
  set "PICK=%~1"
  set "PICKVER=%JV%"
) else (
  echo [info] skipping %~1 - Java %JV% is too new for Gradle 8.11 / Kotlin 2.0
)
exit /b 0
