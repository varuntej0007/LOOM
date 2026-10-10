@echo off
setlocal EnableExtensions EnableDelayedExpansion
title LOOM build
cd /d "%~dp0"

echo ============================================================
echo  LOOM - build signed release APK (installs if a phone is connected)
echo ============================================================

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
  echo [ERROR] No JDK found. Install Android Studio, or set JAVA_HOME to a JDK 17+.
  goto :fail
)
:java_ok
echo [ok] JAVA_HOME=%JAVA_HOME%

rem ---------- 2. Android SDK ----------
if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not exist "%ANDROID_HOME%\platform-tools" (
  echo [ERROR] Android SDK not found at "%ANDROID_HOME%".
  echo         Open Android Studio once ^(SDK Manager^) or set ANDROID_HOME.
  goto :fail
)
echo [ok] ANDROID_HOME=%ANDROID_HOME%
set "SDKDIR=%ANDROID_HOME:\=/%"
> local.properties echo sdk.dir=%SDKDIR%

rem ---------- 3. Models ----------
set "ASSETS=app\src\main\assets"
if not exist "%ASSETS%" mkdir "%ASSETS%"
set "MP=https://storage.googleapis.com/mediapipe-models/object_detector"
for %%M in (efficientdet_lite0 efficientdet_lite2) do (
  if not exist "%ASSETS%\%%M.tflite" (
    echo [..] downloading %%M.tflite
    curl -L --fail --retry 3 -o "%ASSETS%\%%M.tflite" "%MP%/%%M/float16/latest/%%M.tflite"
    if errorlevel 1 (
      echo [ERROR] Could not download %%M.tflite - copy it into %ASSETS% manually.
      goto :fail
    )
  ) else (
    echo [ok] %%M.tflite present
  )
)

rem ---------- 4. Gradle distribution (curl is more reliable here than Java) ----------
set "GZIP=gradle\wrapper\gradle-8.11.1-bin.zip"
if not exist "%GZIP%" (
  echo [..] downloading Gradle 8.11.1 ^(~130 MB^)
  curl -L --fail --retry 3 -o "%GZIP%.part" "https://services.gradle.org/distributions/gradle-8.11.1-bin.zip"
  if errorlevel 1 (
    del "%GZIP%.part" 2>nul
    echo [ERROR] Could not download Gradle. Check the internet connection and run again.
    goto :fail
  )
  move /y "%GZIP%.part" "%GZIP%" >nul
) else (
  echo [ok] Gradle zip present
)

rem ---------- 5. Release signing key (created once, keep it safe) ----------
if not exist "loom-release.jks" (
  echo [..] creating release signing key loom-release.jks
  set "KSPW=loom!RANDOM!!RANDOM!!RANDOM!!RANDOM!"
  "%JAVA_HOME%\bin\keytool.exe" -genkeypair -keystore loom-release.jks -storetype PKCS12 ^
    -alias loom -keyalg RSA -keysize 2048 -validity 10000 ^
    -storepass !KSPW! -keypass !KSPW! -dname "CN=LOOM, O=LOOM Team, C=IN"
  if errorlevel 1 (
    echo [ERROR] keytool failed.
    goto :fail
  )
  > keystore.properties echo storeFile=loom-release.jks
  >> keystore.properties echo storePassword=!KSPW!
  >> keystore.properties echo keyAlias=loom
  >> keystore.properties echo keyPassword=!KSPW!
  echo [ok] key created. BACK UP loom-release.jks and keystore.properties - updates need the same key.
) else (
  echo [ok] signing key present
)
if not exist "keystore.properties" (
  echo [ERROR] loom-release.jks exists but keystore.properties is missing.
  goto :fail
)

rem ---------- 6. Build ----------
echo [..] building release ^(first run downloads dependencies, takes a few minutes^)
set "GRADLE_OPTS=-Djava.net.preferIPv4Stack=true"
call gradlew.bat --no-daemon assembleRelease
if errorlevel 1 (
  echo [ERROR] Gradle build failed. Copy the error lines above and send them to Claude.
  goto :fail
)

set "APK=app\build\outputs\apk\release\app-release.apk"
if not exist "%APK%" (
  echo [ERROR] Build said OK but %APK% is missing.
  goto :fail
)
copy /y "%APK%" "LOOM.apk" >nul
echo [ok] APK: %CD%\LOOM.apk

rem ---------- 7. Install if a phone is connected ----------
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
set "DEVICE="
for /f "skip=1 tokens=1,2" %%A in ('""%ADB%" devices"') do (
  if "%%B"=="device" set "DEVICE=%%A"
)
if not defined DEVICE (
  echo [info] No phone detected. Plug in the iQOO with USB debugging on, or copy LOOM.apk to it.
  goto :done
)
echo [..] installing on %DEVICE%
"%ADB%" -s %DEVICE% install -r "LOOM.apk"
if errorlevel 1 (
  echo [..] install failed - removing any older LOOM build with a different signature and retrying
  "%ADB%" -s %DEVICE% uninstall com.loom.runtime >nul 2>&1
  "%ADB%" -s %DEVICE% install "LOOM.apk"
  if errorlevel 1 (
    echo [warn] install failed - on the iQOO enable "Install via USB" in Developer options.
    goto :done
  )
)
"%ADB%" -s %DEVICE% shell am start -n com.loom.runtime/.MainActivity >nul
echo [ok] LOOM installed and launched.

:done
echo.
echo DONE.
pause
exit /b 0

:fail
echo.
echo BUILD FAILED.
pause
exit /b 1
