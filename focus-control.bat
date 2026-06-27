@echo off
title FocusLock Controller
:menu
cls
echo ==================================================
echo              FocusLock Session Controller
echo ==================================================
echo   1. Start 25-minute Focus Session (Lock All Devices)
echo   2. Stop Active Focus Session (Unlock All Devices)
echo   3. Check Current Focus Session Status
echo   4. Exit
echo ==================================================
set /p choice="Enter your choice (1-4): "

if "%choice%"=="1" goto start_focus
if "%choice%"=="2" goto stop_focus
if "%choice%"=="3" goto check_focus
if "%choice%"=="4" goto exit

echo Invalid choice, try again.
pause
goto menu

:start_focus
echo.
echo Starting 25-minute Focus Session...
cd windows-agent
npm run focus:start -- 25
cd ..
pause
goto menu

:stop_focus
echo.
echo Stopping active Focus Session...
cd windows-agent
npm run focus:stop
cd ..
pause
goto menu

:check_focus
echo.
echo Checking Focus Session status...
cd windows-agent
npm run focus:status
cd ..
pause
goto menu

:exit
exit
