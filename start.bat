@echo off
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
set "SCRIPT_DIR_SHORT=%SCRIPT_DIR%"
for %%I in ("%SCRIPT_DIR%") do set "SCRIPT_DIR_SHORT=%%~sI"
set "APP=%~1"
if "%APP%"=="" set "APP=tracker"

rem Prefer Java 17 for sbt/Scala 2.12 compatibility
if exist "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe" (
  set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
  set "PATH=%JAVA_HOME%\bin;%PATH%"
)

set "CACHE_ROOT=%USERPROFILE%\.tibia-online-tracker-cache\%APP%"
set "SBT_USER_HOME=%CACHE_ROOT%\sbt-home"
set "SBT_GLOBAL_BASE=%CACHE_ROOT%\sbt"
set "SBT_BOOT_DIR=%CACHE_ROOT%\sbt\boot"
set "SBT_IVY_HOME=%CACHE_ROOT%\ivy2"
set "COURSIER_CACHE=%CACHE_ROOT%\coursier"
set "COURSIER_HOME=%CACHE_ROOT%\coursier-home"
set "HOME=%CACHE_ROOT%\home"
set "SBT_OPTS=-Dsbt.global.base=%SBT_GLOBAL_BASE% -Dsbt.boot.directory=%SBT_BOOT_DIR% -Dsbt.ivy.home=%SBT_IVY_HOME% -Dsbt.user.home=%SBT_USER_HOME% -Dsbt.server.autostart=false -Dsbt.server.forcestop=true -Dsbt.server.autoconnect=false -Dsbt.server.connection=disabled -Dsbt.io.pipe=false -Dsbt.supershell=false -Dsbt.log.noformat=true"
set "SBT_SERVER=false"
if not exist "%SBT_USER_HOME%" mkdir "%SBT_USER_HOME%" >nul 2>&1
if not exist "%SBT_GLOBAL_BASE%" mkdir "%SBT_GLOBAL_BASE%" >nul 2>&1
if not exist "%SBT_BOOT_DIR%" mkdir "%SBT_BOOT_DIR%" >nul 2>&1
if not exist "%SBT_IVY_HOME%" mkdir "%SBT_IVY_HOME%" >nul 2>&1
if not exist "%SBT_GLOBAL_BASE%\java9-rt-ext-eclipse_adoptium_17_0_18" mkdir "%SBT_GLOBAL_BASE%\java9-rt-ext-eclipse_adoptium_17_0_18" >nul 2>&1
if not exist "%SBT_GLOBAL_BASE%\java9-rt-ext-eclipse_adoptium_25_0_2" mkdir "%SBT_GLOBAL_BASE%\java9-rt-ext-eclipse_adoptium_25_0_2" >nul 2>&1
if not exist "%COURSIER_CACHE%" mkdir "%COURSIER_CACHE%" >nul 2>&1
if not exist "%COURSIER_HOME%" mkdir "%COURSIER_HOME%" >nul 2>&1
if not exist "%HOME%" mkdir "%HOME%" >nul 2>&1

if /I "%APP%"=="tracker" goto run_tracker
if /I "%APP%"=="altfinder" goto run_altfinder

if /I "%APP%"=="all" goto run_all


echo Usage: %~nx0 [tracker^|altfinder^|all]
exit /b 1

:load_env
if exist "%SCRIPT_DIR%.env" (
  for /f "usebackq delims=" %%L in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%load-env.ps1" "%SCRIPT_DIR%.env"`) do call set "%%L"
)
exit /b 0

:check_sbt
where sbt >nul 2>&1
if errorlevel 1 (
  echo ERROR: sbt not found. Install sbt and ensure it is on PATH.
  exit /b 1
)
exit /b 0

:check_db_env
set "MISSING="
if "%DB_HOST%"=="" set "MISSING=%MISSING% DB_HOST"
if "%DB_PORT%"=="" set "MISSING=%MISSING% DB_PORT"
if "%DB_USER%"=="" set "MISSING=%MISSING% DB_USER"
if "%DB_NAME%"=="" set "MISSING=%MISSING% DB_NAME"
if "%DB_PASSWORD%"=="" set "MISSING=%MISSING% DB_PASSWORD"
if not "%MISSING%"=="" (
  echo ERROR: Missing required env vars:%MISSING%
  echo Example in PowerShell:
  echo   $env:DB_HOST="..." ; $env:DB_PORT="5432" ; $env:DB_USER="..." ; $env:DB_NAME="..." ; $env:DB_PASSWORD="..."
  exit /b 1
)
exit /b 0

:check_token
if "%ALTFINDER_TOKEN%"=="" (
  echo WARNING: ALTFINDER_TOKEN is not set. Discord bot and watch runner will be disabled.
  echo The REST API and web board will still be available.
  echo To enable Discord: set ALTFINDER_TOKEN in .env
)
set "TOKEN=%ALTFINDER_TOKEN%"
exit /b 0

:run_tracker
call :load_env
call :check_sbt
if errorlevel 1 exit /b 1
call :check_db_env
if errorlevel 1 exit /b 1
cd /d "%SCRIPT_DIR%"
call sbt -batch "tracker/run"
exit /b %errorlevel%

:run_altfinder
call :load_env
call :check_sbt
if errorlevel 1 exit /b 1
call :check_db_env
if errorlevel 1 exit /b 1
call :check_token
if errorlevel 1 exit /b 1
cd /d "%SCRIPT_DIR%"
call sbt -batch "altfinder/run"
exit /b %errorlevel%

:run_all
call "%SCRIPT_DIR%start-all.bat"
exit /b 0






