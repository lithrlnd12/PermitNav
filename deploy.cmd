@echo off
echo 🚀 Deploying PermitNav PDF Chat to Firebase Cloud Functions...
echo.

cd permitnav_backend

echo 📦 Installing dependencies...
call npm install
if %ERRORLEVEL% neq 0 (
    echo ❌ npm install failed
    pause
    exit /b 1
)

echo.
echo 🔑 Setting OpenAI API key...
call firebase functions:config:set openai.key=%OPENAI_API_KEY%
if %ERRORLEVEL% neq 0 (
    echo ❌ Failed to set OpenAI key. Make sure OPENAI_API_KEY environment variable is set.
    echo.
    echo You can set it manually with:
    echo firebase functions:config:set openai.key=your-key-here
    echo.
    pause
    exit /b 1
)

echo.
echo 🚀 Deploying to Firebase...
call firebase deploy --only functions
if %ERRORLEVEL% neq 0 (
    echo ❌ Deployment failed
    pause
    exit /b 1
)

echo.
echo ✅ Deployment successful!
echo.
echo 🔗 Your PDF chat function is now available at:
echo https://us-central1-permit-nav.cloudfunctions.net/pdfchat/health
echo.
echo 📱 Build your Android app to test with the cloud backend!
echo.
pause