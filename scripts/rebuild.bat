@echo off
setlocal EnableExtensions EnableDelayedExpansion

for %%I in ("%~dp0..") do set "XMIC_DIR=%%~fI"

if not exist "%XMIC_DIR%\gradlew.bat" (
  echo [ERROR] gradlew.bat not found in "%XMIC_DIR%"
  exit /b 1
)

set "GRADLE_USER_HOME=%XMIC_DIR%\.gradle-local"
set "ANDROID_USER_HOME=%XMIC_DIR%\.android"
if not exist "%GRADLE_USER_HOME%" mkdir "%GRADLE_USER_HOME%"
if not exist "%ANDROID_USER_HOME%" mkdir "%ANDROID_USER_HOME%"

echo.
echo [1/1] Building XMicInject...
pushd "%XMIC_DIR%"
call gradlew.bat --stop >nul 2>&1
call gradlew.bat :app:assembleDebug
set "BUILD_EXIT=%ERRORLEVEL%"
popd

if not "%BUILD_EXIT%"=="0" (
  echo.
  echo [FAIL] XMicInject build failed.
  exit /b 1
)

echo.
echo [OK] Build completed.
echo APK: "%XMIC_DIR%\app\build\outputs\apk\debug\app-debug.apk"
exit /b 0
