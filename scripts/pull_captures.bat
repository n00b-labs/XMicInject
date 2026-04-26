@echo off
setlocal EnableExtensions EnableDelayedExpansion

for %%I in ("%~dp0..") do set "XMIC_DIR=%%~fI"
set "ANDROID_USER_HOME=%XMIC_DIR%\.android-adb"
set "ANDROID_SDK_HOME=%XMIC_DIR%\.android-adb"
if not exist "%ANDROID_USER_HOME%" mkdir "%ANDROID_USER_HOME%"

set "OUTPUT_DIR=%CD%\output"
set "REMOTE_CAPTURE_DIR=/data/user/0/org.telegram.messenger/files/xmicinject-captures"
set "REMOTE_EXPORT_DIR=/sdcard/Download/xmicinject-captures"

set "ADB_EXE=adb.exe"
if not exist "%ADB_EXE%" set "ADB_EXE="

if not defined ADB_EXE (
  for /f "delims=" %%I in ('where adb 2^>nul') do (
    set "ADB_EXE=%%I"
    goto :adb_found
  )
)

:adb_found
if not defined ADB_EXE (
  echo [ERROR] adb.exe not found.
  echo [HINT] Install platform-tools and ensure adb.exe is in PATH
  exit /b 1
)

echo.
echo [1/4] Checking connected device...
"%ADB_EXE%" start-server >nul 2>&1

set /a DEVICE_COUNT=0
set "HAS_UNAUTHORIZED=0"
set "HAS_OFFLINE=0"
for /f "skip=1 tokens=1,2" %%A in ('"%ADB_EXE%" devices') do (
  if "%%B"=="device" set /a DEVICE_COUNT+=1
  if "%%B"=="unauthorized" set "HAS_UNAUTHORIZED=1"
  if "%%B"=="offline" set "HAS_OFFLINE=1"
)

if "%HAS_UNAUTHORIZED%"=="1" (
  echo [ERROR] Device is unauthorized. Confirm USB debugging prompt on phone.
  exit /b 1
)

if "%HAS_OFFLINE%"=="1" (
  echo [ERROR] Device is offline. Reconnect USB and restart adb server.
  exit /b 1
)

if !DEVICE_COUNT! EQU 0 (
  echo [ERROR] No connected device in adb.
  exit /b 1
)

if !DEVICE_COUNT! GTR 1 (
  echo [ERROR] Multiple devices connected.
  echo [HINT] Set ANDROID_SERIAL and rerun, for example:
  echo        set ANDROID_SERIAL=YOUR_DEVICE_ID
  exit /b 1
)

echo.
echo [2/4] Checking root access and capture files...
"%ADB_EXE%" shell su -c "id" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] su is unavailable from adb shell.
  exit /b 1
)

"%ADB_EXE%" shell su -c "ls -1 %REMOTE_CAPTURE_DIR%/*.wav" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] No capture files found in %REMOTE_CAPTURE_DIR%
  exit /b 1
)

echo.
echo [3/4] Copying captures to a pullable folder on the phone...
"%ADB_EXE%" shell su -c "rm -rf %REMOTE_EXPORT_DIR%" >nul 2>&1
"%ADB_EXE%" shell su -c "mkdir -p %REMOTE_EXPORT_DIR%"
if errorlevel 1 (
  echo [FAIL] Failed to create remote export folder.
  exit /b 1
)
"%ADB_EXE%" shell su -c "cp %REMOTE_CAPTURE_DIR%/*.wav %REMOTE_EXPORT_DIR%/"
if errorlevel 1 (
  echo [FAIL] Failed to copy captures to export folder.
  exit /b 1
)

if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

echo.
echo [4/4] Pulling captures to "%OUTPUT_DIR%"...
set "PULLED_ANY=0"
for /f "usebackq delims=" %%F in (`"%ADB_EXE%" shell ls -1 %REMOTE_EXPORT_DIR%`) do (
  "%ADB_EXE%" pull "%REMOTE_EXPORT_DIR%/%%F" "%OUTPUT_DIR%\%%F" >nul
  if errorlevel 1 (
    echo [WARN] Failed to pull %%F
  ) else (
    set "PULLED_ANY=1"
    echo [OK] Pulled %%F
  )
)

if "%PULLED_ANY%"=="0" (
  echo [FAIL] Nothing was pulled.
  exit /b 1
)

echo.
echo [OK] Files downloaded to: "%OUTPUT_DIR%"
exit /b 0
