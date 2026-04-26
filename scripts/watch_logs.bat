@echo off
setlocal EnableExtensions EnableDelayedExpansion

for %%I in ("%~dp0..") do set "XMIC_DIR=%%~fI"

set "RAW_MODE=0"
set "CLEAR_LOGS=0"

for %%A in (%*) do (
  if /i "%%~A"=="raw" set "RAW_MODE=1"
  if /i "%%~A"=="clear" set "CLEAR_LOGS=1"
  if /i "%%~A"=="-h" goto :usage
  if /i "%%~A"=="--help" goto :usage
  if /i "%%~A"=="help" goto :usage
)

set "ANDROID_USER_HOME=%XMIC_DIR%\.android-adb"
set "ANDROID_SDK_HOME=%XMIC_DIR%\.android-adb"
if not exist "%ANDROID_USER_HOME%" mkdir "%ANDROID_USER_HOME%"

set "ADB_EXE=adb.exe"
if not exist "%ADB_EXE%" set "ADB_EXE=adb.exe"
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
  exit /b 1
)

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
  echo [ERROR] Device is offline. Reconnect cable and retry.
  exit /b 1
)

if !DEVICE_COUNT! EQU 0 (
  echo [ERROR] No connected device in adb.
  exit /b 1
)

if !DEVICE_COUNT! GTR 1 (
  echo [ERROR] Multiple devices detected.
  echo [HINT] Set ANDROID_SERIAL and rerun.
  exit /b 1
)

if "%CLEAR_LOGS%"=="1" (
  echo [INFO] Clearing existing logcat buffer...
  "%ADB_EXE%" logcat -c
)

echo.
echo [INFO] Watching XMicInject logs. Press Ctrl+C to stop.
if "%RAW_MODE%"=="1" (
  echo [MODE] RAW logcat
  "%ADB_EXE%" logcat -v time
  exit /b %errorlevel%
)

echo [MODE] Filtered (XMicInject only)
"%ADB_EXE%" logcat -v time | findstr /i ^
  /c:"XMicHook" ^
  /c:"XMicRing" ^
  /c:"XMicIpc" ^
  /c:"XMicUplink" ^
  /c:"XMicCaptureFile"
exit /b %errorlevel%

:usage
echo Usage:
echo   watch_logs.bat [raw] [clear]
echo.
echo Examples:
echo   watch_logs.bat
echo   watch_logs.bat clear
echo   watch_logs.bat raw
exit /b 0
