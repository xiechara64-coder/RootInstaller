@echo off
rem Entry point for check-env.ps1, for the same reason as build.cmd:
rem Windows blocks .ps1 files by default, but .cmd files are not blocked.
rem Pure ASCII on purpose - cmd.exe reads batch files with the OEM code page.
setlocal
set "SCRIPT_DIR=%~dp0"
where pwsh >nul 2>nul
if %ERRORLEVEL%==0 (set "PSEXE=pwsh") else (set "PSEXE=powershell")
"%PSEXE%" -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%check-env.ps1" %*
echo.
pause
