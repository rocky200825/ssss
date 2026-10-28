@echo off
rem Builds dist\PhoneMicBridge.exe. Run from this folder.
python -m pip install -r requirements.txt pyinstaller || goto :error
python -m PyInstaller --onefile --console --name PhoneMicBridge server.py || goto :error
echo.
echo Done: dist\PhoneMicBridge.exe
pause
exit /b 0

:error
echo Build failed.
pause
exit /b 1
