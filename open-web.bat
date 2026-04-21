@echo off
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
set "UI_URL=%WEB_UI_URL%"

rem Load .env vars if present (same approach as start.bat)
if exist "%SCRIPT_DIR%.env" (
  for /f "usebackq delims=" %%L in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%load-env.ps1" "%SCRIPT_DIR%.env"`) do call set "%%L"
)

if "%UI_URL%"=="" set "UI_URL=https://tibia-scout-board-b0n7.onrender.com"

set "BACKEND_PARAM="
if not "%ALTFINDER_API_BASE%"=="" set "BACKEND_PARAM=%ALTFINDER_API_BASE%"
if "%BACKEND_PARAM%"=="" if not "%BACKEND_URL%"=="" set "BACKEND_PARAM=%BACKEND_URL%"

if not "%BACKEND_PARAM%"=="" (
  rem Open the backend-hosted board directly to avoid HTTPS/HTTP mixed-content issues
  set "UI_URL=%BACKEND_PARAM%/altfinder"
)

echo Opening %UI_URL%
start "" "%UI_URL%"

endlocal
