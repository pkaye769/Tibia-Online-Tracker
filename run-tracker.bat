@echo off
setlocal EnableExtensions
set "SCRIPT_DIR=%~dp0"
call "%SCRIPT_DIR%start.bat" tracker
set "EXITCODE=%ERRORLEVEL%"
if not "%EXITCODE%"=="0" (
  echo.
  echo Tracker exited with error code %EXITCODE%.
  echo Press any key to close this window...
  pause >nul
)
endlocal
exit /b %EXITCODE%
