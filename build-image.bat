@echo off
setlocal

set IMAGE_NAME=clipbird-app
set ARCHIVE_NAME=clipbird.tar.gz

echo Building Docker image "%IMAGE_NAME%" (no cache)...
docker build --no-cache -t %IMAGE_NAME% .
if %ERRORLEVEL% neq 0 (
    echo ERROR: Docker build failed.
    exit /b 1
)

echo Saving image to %ARCHIVE_NAME%...
docker save %IMAGE_NAME% | gzip > %ARCHIVE_NAME%
if %ERRORLEVEL% neq 0 (
    echo ERROR: Failed to save image.
    exit /b 1
)

echo Done: %ARCHIVE_NAME%
