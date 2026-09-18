@echo off
set PORT=%1
if "%PORT%"=="" set PORT=8080
adb devices
adb reverse tcp:%PORT% tcp:%PORT%
echo.
echo Android can now reach the PC server as http://127.0.0.1:%PORT%
pause
