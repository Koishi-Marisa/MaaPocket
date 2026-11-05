@echo off
setlocal
chcp 936 >nul 2>&1
title Windows 7 All-in-One Optimizer
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -Command "if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { exit 1 }"
if not errorlevel 1 goto run

echo Administrator privileges are required. Requesting elevation...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
exit /b

:run
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Win7_AllInOne_Optimize.ps1"
if errorlevel 1 (
    echo.
    echo The script exited with an error. See the log on your Desktop.
    pause
)
