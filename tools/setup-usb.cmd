@echo off
chcp 65001 >nul
where py >nul 2>nul
if %errorlevel% equ 0 (
  py -3 "%~dp0vehicle_usb.py" %*
) else (
  python "%~dp0vehicle_usb.py" %*
)
set result=%errorlevel%
pause
exit /b %result%
