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
echo   4. Emergency Instant Block (Lock for 60 Minutes)
echo   5. Emergency Instant Unblock
echo   6. Exit
echo ==================================================
set /p choice="Enter your choice (1-6): "

if "%choice%"=="1" goto start_focus
if "%choice%"=="2" goto stop_focus
if "%choice%"=="3" goto check_focus
if "%choice%"=="4" goto emergency_block
if "%choice%"=="5" goto emergency_unblock
if "%choice%"=="6" goto exit

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

:emergency_block
echo.
echo Executing 60-minute Emergency Block...
cd windows-agent
npm run emergency -- block
cd ..
pause
goto menu

:emergency_unblock
echo.
echo Executing Emergency Unblock...
cd windows-agent
npm run emergency -- unblock
cd ..
pause
goto menu

:exit
exit
