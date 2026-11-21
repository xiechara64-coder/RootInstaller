@echo off
rem ===========================================================================
rem  RootInstaller build entry point (Windows)
rem
rem  WHY THIS FILE EXISTS
rem    Windows blocks PowerShell scripts by default (execution policy =
rem    Restricted), so running build.ps1 directly fails with
rem    "running scripts is disabled on this system".
rem
rem    A .cmd batch file is NOT subject to the execution policy. So this file
rem    is the entry point: it invokes PowerShell with -ExecutionPolicy Bypass,
rem    which applies ONLY to that one child process and does not change any
rem    system setting.
rem
rem  USAGE
rem    Double-click this file                  - build with defaults
rem    build.cmd -AndroidSdk "D:\android-sdk"  - point at your SDK
rem    build.cmd -Platform 19 -BuildTools 34.0.0
rem
rem  NOTE
rem    This file is intentionally pure ASCII. cmd.exe reads batch files using
rem    the OEM code page (GBK on Chinese Windows), which would corrupt any
rem    non-ASCII text. All Chinese output lives in build.ps1 instead, which is
rem    saved as UTF-8 with BOM.
rem ===========================================================================

setlocal
set "SCRIPT_DIR=%~dp0"
set "PS1=%SCRIPT_DIR%build.ps1"

if not exist "%PS1%" (
    echo [ERROR] Cannot find "%PS1%"
    pause
    exit /b 1
)

rem Prefer PowerShell 7+ (pwsh) when present, otherwise fall back to the
rem built-in Windows PowerShell.
where pwsh >nul 2>nul
if %ERRORLEVEL%==0 (
    set "PSEXE=pwsh"
) else (
    set "PSEXE=powershell"
)

echo Running build with %PSEXE% ...
echo.

"%PSEXE%" -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %*

set "RC=%ERRORLEVEL%"
echo.
if "%RC%"=="0" (
    echo Build finished successfully.
) else (
    echo Build FAILED with exit code %RC%.
)
pause
exit /b %RC%
