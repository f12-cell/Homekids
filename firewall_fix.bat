@echo off
echo ============================================================
echo HomeKids Firewall Fix - Opening Port 8000
echo ============================================================
echo.
netsh advfirewall firewall add rule name="HomeKids Backend" dir=in action=allow protocol=TCP localport=8000
echo.
echo DONE! Port 8000 is now open.
echo.
pause
