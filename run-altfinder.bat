@echo off
setlocal EnableExtensions
set "SCRIPT_DIR=%~dp0"
call "%SCRIPT_DIR%start.bat" altfinder
set "EXITCODE=%ERRORLEVEL%"
if not "%EXITCODE%"=="0" (
  echo.
  echo Altfinder exited with error code %EXITCODE%.
  echo Press any key to close this window...
  pause >nul
)
endlocal
exit /b %EXITCODE%
