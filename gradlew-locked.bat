@echo off
setlocal EnableExtensions

set "HERE=%~dp0"
set "LOCKDIR=%HERE%.gradle-lock"
set "HOLDER=%LOCKDIR%\holder.txt"
if "%OCTANT_LOCK_WAIT%"=="" set "OCTANT_LOCK_WAIT=900"

set /a WAITED=0
if /i not "%OCTANT_LOCK_BREAK%"=="1" goto trylock
if not exist "%LOCKDIR%" goto trylock
echo [lock] !!! OCTANT_LOCK_BREAK=1 -- force-taking an existing lock.
echo [lock] !!! If the holder is still running, you are creating exactly the
echo [lock] !!! collision this lock exists to prevent.
if exist "%HOLDER%" type "%HOLDER%"
2>nul rmdir /s /q "%LOCKDIR%"

:trylock
2>nul mkdir "%LOCKDIR%"
if not errorlevel 1 goto locked
if %WAITED%==0 goto announce
goto checktimeout

:announce
echo [lock] Another build is running. Holder info:
if exist "%HOLDER%" goto showholder
echo    holder file missing -- just acquired, or the previous run was interrupted
goto checktimeout

:showholder
type "%HOLDER%"

:checktimeout
if %WAITED% GEQ %OCTANT_LOCK_WAIT% goto timeout
ping -n 6 127.0.0.1 >nul 2>&1
set /a WAITED+=5
goto trylock

:timeout
echo [lock] Waited %OCTANT_LOCK_WAIT%s and still no lock. NOT proceeding.
echo [lock] If the holder definitely crashed, force-take the stale lock with:
echo [lock]     set OCTANT_LOCK_BREAK=1
echo [lock]     .\gradlew-locked.bat ^<tasks^>
endlocal
exit /b 2

:locked
>  "%HOLDER%" echo holder=%USERNAME%
>> "%HOLDER%" echo host=%COMPUTERNAME%
>> "%HOLDER%" echo started=%DATE% %TIME%
>> "%HOLDER%" echo cwd=%CD%
>> "%HOLDER%" echo cmd=gradlew %*
>> "%HOLDER%" echo wrapper=%OCTANT_WRAPPER%

if "%OCTANT_WRAPPER%"=="" set "OCTANT_WRAPPER=%HERE%gradlew.bat"
call "%OCTANT_WRAPPER%" %*
set "RC=%ERRORLEVEL%"

2>nul rmdir /s /q "%LOCKDIR%"

endlocal & exit /b %RC%
