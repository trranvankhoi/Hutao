@echo off
title PCRemoteServer Compiler Tool
echo ======================================================================
echo          PCRemoteServer Single EXE Packer (Windows 10/11)
echo ======================================================================
echo.
echo [*] Step 1: Installing all required libraries and dependencies...
python -m pip install --upgrade pip
pip install -r requirements.txt
echo.
echo [*] Step 2: Running PyInstaller to compile single-file PCRemoteServer.exe...
echo [!] Packaging with --windowed (runs in background with tray, no console window popup)...
echo [!] Please wait, this may take a minute...
echo.

pyinstaller --noconfirm --onefile --windowed --name PCRemoteServer --hidden-import=pystray --hidden-import=PIL --hidden-import=psutil --hidden-import=pynput --hidden-import=pyautogui --hidden-import=cv2 --clean server.py

echo.
echo ======================================================================
echo [INFO] COMPILATION PROCESS COMPLETE!
echo.
echo [✓] Your distribution file has been built successfully:
echo     Location: dist\PCRemoteServer.exe
echo.
echo You can copy PCRemoteServer.exe to any folder or set it to run on startup.
echo ======================================================================
pause
