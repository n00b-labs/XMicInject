@echo off
setlocal EnableExtensions EnableDelayedExpansion

for %%I in ("%~dp0..") do set "XMIC_DIR=%%~fI"
set "XMIC_APK=%XMIC_DIR%\app\build\outputs\apk\debug\app-debug.apk"
set "ANDROID_USER_HOME=%XMIC_DIR%\.android-adb"
set "ANDROID_SDK_HOME=%XMIC_DIR%\.android-adb"
if not exist "%ANDROID_USER_HOME%" mkdir "%ANDROID_USER_HOME%"

if not exist "%XMIC_APK%" (
  echo [ERROR] XMicInject APK not found: "%XMIC_APK%"
  echo [HINT] Run rebuild.bat first.
  exit /b 1
)

set "ADB_EXE=C:\Android\platform-tools\adb.exe"
if not exist "%ADB_EXE%" set "ADB_EXE=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
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
  echo [HINT] Install platform-tools or place adb at C:\Android\platform-tools\adb.exe
  exit /b 1
)

echo.
echo [1/2] Checking connected device...
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
echo [2/2] Installing XMicInject...
set "INSTALL_LOG=%TEMP%\xmicinject_install_%RANDOM%.log"
"%ADB_EXE%" install -r "%XMIC_APK%" >"%INSTALL_LOG%" 2>&1
set "INSTALL_EXIT=%ERRORLEVEL%"
type "%INSTALL_LOG%"

if not "%INSTALL_EXIT%"=="0" (
  findstr /i "INSTALL_FAILED_UPDATE_INCOMPATIBLE" "%INSTALL_LOG%" >nul
  if not errorlevel 1 (
    echo [WARN] XMicInject signature mismatch. Reinstalling clean package...
    "%ADB_EXE%" uninstall com.xmicinject >nul 2>&1
    "%ADB_EXE%" install "%XMIC_APK%"
    if errorlevel 1 (
      del /q "%INSTALL_LOG%" >nul 2>&1
      echo.
      echo [FAIL] XMicInject reinstall failed.
      exit /b 1
    )
  ) else (
    del /q "%INSTALL_LOG%" >nul 2>&1
    echo.
    echo [FAIL] XMicInject install failed.
    exit /b 1
  )
)

del /q "%INSTALL_LOG%" >nul 2>&1

echo.
echo [OK] Installed successfully.
echo APK: "%XMIC_APK%"
echo.
echo [NEXT] Enable XMicInject in LSPosed for Telegram and do a soft reboot if needed.
exit /b 0
