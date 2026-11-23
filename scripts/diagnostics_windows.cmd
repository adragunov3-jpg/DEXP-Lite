@echo off
REM Read-only diagnostics for ADB-connected TV (does not change anything).
set OUT=%~dp0tv_diagnostics.txt
(
 echo ==== MODEL ====
 adb shell getprop ro.product.model
 echo ==== ANDROID ====
 adb shell getprop ro.build.version.release
 echo ==== BUILD ====
 adb shell getprop ro.build.display.id
 echo ==== MEMORY ====
 adb shell cat /proc/meminfo
 echo ==== STORAGE ====
 adb shell df -h /data
 echo ==== TOP MEMORY USAGE ====
 adb shell dumpsys meminfo
) > "%OUT%"
echo Diagnostics saved to %OUT%
pause
