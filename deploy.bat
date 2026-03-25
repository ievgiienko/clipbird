@echo off
setlocal

set SSH_KEY=C:\prog\clipbird\private
set SERVER=root@204.168.204.134
set IMAGE_NAME=clipbird-app
set ARCHIVE_NAME=clipbird.tar.gz

del %ARCHIVE_NAME%

echo === Step 1: Building Docker image ===
call build-image.bat
if %ERRORLEVEL% neq 0 (
    echo ERROR: Build failed.
    exit /b 1
)

echo === Step 2: Uploading %ARCHIVE_NAME% to server ===
scp -i "%SSH_KEY%" %ARCHIVE_NAME% %SERVER%:/root/%ARCHIVE_NAME%
if %ERRORLEVEL% neq 0 (
    echo ERROR: Upload failed.
    exit /b 1
)

echo === Step 3: Loading image and restarting containers ===
ssh -i "%SSH_KEY%" %SERVER% "docker load -i /root/%ARCHIVE_NAME% && cd /root && docker compose down app && docker compose up -d && rm /root/%ARCHIVE_NAME%"
if %ERRORLEVEL% neq 0 (
    echo ERROR: Remote deploy failed.
    exit /b 1
)

echo === Deploy complete ===
