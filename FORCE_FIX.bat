@echo off
echo ============================================================
echo   HOMEKIDS NUCLEAR NETWORK FIX
echo ============================================================
echo.
echo 1. Killing any stuck Python processes...
taskkill /f /im python.exe >nul 2>&1

echo 2. Opening Firewall Port 8000 (TCP)...
netsh advfirewall firewall delete rule name="HomeKids API" >nul 2>&1
netsh advfirewall firewall add rule name="HomeKids API" dir=in action=allow protocol=TCP localport=8000

echo 3. Setting Network to PRIVATE (Required for local servers)...
powershell -Command "Get-NetConnectionProfile | Set-NetConnectionProfile -NetworkCategory Private"

echo 4. DETECTING YOUR IP ADDRESS...
ipconfig | findstr "IPv4"

echo.
echo ============================================================
echo   NEXT STEPS:
echo   1. Run 'python backend/app.py'
echo   2. On your phone browser, try the IP listed above.
echo      Example: http://192.168.18.4:8000/ping
echo ============================================================
pause
