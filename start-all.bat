@echo off
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"

if exist "%SCRIPT_DIR%.env" (
  for /f "usebackq delims=" %%L in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%load-env.ps1" "%SCRIPT_DIR%.env"`) do call set "%%L"
)

where sbt >nul 2>&1
if errorlevel 1 (
  echo ERROR: sbt not found. Install sbt and ensure it is on PATH.
  exit /b 1
)

set "MISSING="
if "%DB_HOST%"=="" set "MISSING=%MISSING% DB_HOST"
if "%DB_PORT%"=="" set "MISSING=%MISSING% DB_PORT"
if "%DB_USER%"=="" set "MISSING=%MISSING% DB_USER"
if "%DB_NAME%"=="" set "MISSING=%MISSING% DB_NAME"
if "%DB_PASSWORD%"=="" set "MISSING=%MISSING% DB_PASSWORD"
if not "%MISSING%"=="" (
  echo ERROR: Missing required env vars:%MISSING%
  exit /b 1
)

if "%ALTFINDER_TOKEN%"=="" (
  echo ERROR: Missing ALTFINDER_TOKEN in .env.
  exit /b 1
)

cd /d "%SCRIPT_DIR%"

start "tibia-tracker" cmd /k ""%SCRIPT_DIR%run-tracker.bat""
timeout /t 5 /nobreak >nul
start "tibia-altfinder" cmd /k ""%SCRIPT_DIR%run-altfinder.bat""

echo Started tracker and altfinder in separate windows.
