# 🚛 Clearway Cargo

**Mobile-first platform for truck drivers and dispatchers to simplify oversize/overweight permit compliance, routing, and fleet management.**

[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-orange.svg)](https://developer.android.com/jetpack/compose)
[![Firebase](https://img.shields.io/badge/Backend-Firebase-yellow.svg)](https://firebase.google.com)
[![HERE Maps](https://img.shields.io/badge/Maps-HERE%20API-purple.svg)](https://developer.here.com)

Clearway Cargo (formerly PermitNav) is a comprehensive platform that revolutionizes how trucking companies handle OSOW permits, combining AI-powered scanning, truck-compliant routing, and dispatcher automation for reduced violations and streamlined operations.

## 🎯 Features

### 👨‍💼 **For Dispatchers (NEW)**
- **🔄 Role-Based Access**: Switch between driver/dispatcher views for testing
- **📋 Load Management**: Create and assign loads with automated task generation  
- **⚡ Task Automation**: Route planning, permit validation, driver notifications
- **🔗 N8N Integration**: Ready for load board scraping and workflow automation
- **☁️ Cloud Functions**: `setUserRole`, `createLoad`, `planRoute`, `validatePermit`

### 🚛 **For Drivers**
- **📸 OCR Permit Scanning**: Google ML Kit with intelligent field detection
- **✅ AI Compliance Chat**: OpenAI-powered analysis with state regulation PDFs
- **Interactive compliance checking** via AI chat assistant
- **Real-time violation detection** and escort requirements
- **State-specific guidance** with DOT contact information

### 🗺️ **Truck-Optimized Routing**
- **HERE Maps integration** with truck-specific parameters
- **Restriction-aware navigation** avoiding prohibited routes
- **Turn-by-turn directions** optimized for commercial vehicles
- **Real-time route monitoring** and recalculation

### 📱 **Driver-Friendly Interface**
- **Modern Material 3 design** with truck driver workflow
- **AI Chat Assistant** for instant permit compliance answers
- **Complete US coverage** with all 50 states + DC (51 jurisdictions)
- **Offline permit storage** with secure local database
- **Quick actions** for scan, route, chat, and vault access

## 🚀 Quick Start

### Prerequisites
- **Android Studio** Hedgehog (2023.1.1) or later
- **JDK 17** (Temurin recommended)
- **Android SDK** with API 34 (Android 14)
- **Firebase Account** with Storage and Firestore enabled
- **OpenAI API Key** for AI chat functionality
- **HERE Developer Account** with Base Plan ([Get yours here](https://developer.here.com))
- **HERE SDK for Android** (Explore Edition) 4.23.3.0

### Installation

1. **Clone the repository**
   ```bash
   git clone https://github.com/lithrlnd12/PermitNav.git
   cd PermitNav
   ```

2. **Configure HERE Credentials**
   
   Create `credentials.properties` in project root:
   ```properties
   accessKeyId=YOUR_HERE_ACCESS_KEY_ID
   accessKeySecret=YOUR_HERE_ACCESS_KEY_SECRET
   ```
   
   Update `local.properties`:
   ```properties
   HERE_API_KEY=YOUR_HERE_REST_API_KEY
   HERE_APP_ID=YOUR_HERE_APP_ID
   ```

3. **Add HERE SDK**
   - Download HERE SDK for Android (Explore Edition) from your HERE account
   - Place the `.aar` file in `app/libs/` directory
   - File should be named: `heresdk-explore-android-4.23.3.0.213462.aar`

4. **Build and Run**
   ```bash
   ./gradlew assembleDebug
   # or use the ▶️ Run button in Android Studio
   ```

## 🏗️ Project Structure

```
app/
├── src/main/java/com/permitnav/
│   ├── ai/                     # AI chat services
│   ├── data/                   # Data layer
│   │   ├── database/           # Room database
│   │   ├── models/             # Data models & chat models
│   │   └── repository/         # Repository pattern
│   ├── firebase/               # Firebase integration
│   ├── network/                # API integration
│   ├── ocr/                    # ML Kit OCR + parsing
│   ├── rules/                  # Compliance engine
│   ├── services/               # Background services
│   └── ui/                     # Compose UI
│       ├── screens/            # Screen composables (incl. ChatScreen)
│       ├── theme/              # Material theming
│       └── viewmodels/         # MVVM ViewModels
├── src/main/assets/
│   └── state_rules/            # State regulation JSONs
└── src/main/res/               # Android resources

permitnav_backend/              # Node.js PDF processing backend
├── state_rules/                # State permit rule PDFs (50 states + DC)
├── firebase_bootstrap.mjs      # Firebase SDK setup
├── upload_state_rules.mjs      # PDF uploader to Firebase Storage
├── chat_worker.mjs             # AI compliance checker
└── health_check.mjs            # Connection testing
```

## 🛠️ Technology Stack

| Component | Technology | Purpose |
|-----------|------------|---------|
| **UI Framework** | Jetpack Compose + Material 3 | Modern declarative UI |
| **Architecture** | MVVM + Repository | Clean separation of concerns |
| **Database** | Room + SQLite | Local permit storage |
| **AI Backend** | OpenAI + Firebase | PDF analysis & chat |
| **Cloud Storage** | Firebase Storage/Firestore | State rules & data sync |
| **Networking** | Ktor HTTP Client | HERE API integration |
| **OCR** | Google ML Kit | Text recognition |
| **Navigation** | Navigation Compose | Screen routing |
| **Background Work** | WorkManager | Route monitoring |
| **Image Handling** | CameraX + FileProvider | Photo capture |

## 📋 Core Workflows

### 1. Permit Scanning Flow
```
📷 Camera Capture → 🔍 ML Kit OCR → 📝 Text Parsing → ✅ Validation → 💾 Storage
```

### 2. AI Chat Compliance Flow
```
💬 User Question → 📄 Permit Context → 🔍 State PDF Lookup → 🤖 OpenAI Analysis → 📋 Compliance Report
```

### 3. Route Planning Flow
```
📍 Destination Input → 🗺️ HERE Geocoding → 🚛 Truck Routing → 📱 Navigation UI
```

### 4. Backend PDF Processing
```
📁 State PDFs → ☁️ Firebase Storage → 🗃️ Firestore Index → 🔍 Chat Queries → 📖 PDF Text Extraction
```

## ⚙️ Configuration

### State Rules
Add new state regulations in `assets/state_rules/{state_code}.json`:

```json
{
  "state": "Indiana",
  "stateCode": "IN",
  "maxDimensions": {
    "maxWeight": 120000,
    "maxHeight": 13.5,
    "maxWidth": 14.0,
    "maxLength": 110.0
  },
  "permitTypes": [...],
  "restrictions": [...],
  "timeRestrictions": [...]
}
```

### Build Variants
- **Debug**: Development build with logging
- **Release**: Production build with ProGuard optimization

## 🧪 Testing

### Run Unit Tests
```bash
./gradlew test
```

### Run Instrumented Tests
```bash
./gradlew connectedAndroidTest
```

### Test Coverage
- **OCR Parsing**: Regex pattern validation
- **Compliance Engine**: State rule validation logic
- **ViewModels**: Business logic and state management

## 📱 Supported Devices

- **Minimum SDK**: API 26 (Android 8.0)
- **Target SDK**: API 34 (Android 14)
- **Architecture**: ARM64, x86_64
- **Screen Sizes**: Phone and tablet layouts
- **Camera**: Required for permit scanning

## 🚀 Roadmap

### Phase 1 - MVP ✅
- [x] Android app with permit scanning and validation
- [x] HERE Maps truck routing integration
- [x] AI-powered chat compliance system
- [x] Firebase backend with complete US coverage (50 states + DC)
- [x] Multi-state permit rule processing

### Phase 2 - Enhanced Features 🚧
- [ ] Deploy chat backend as Cloud Function/API
- [ ] Real-time road closures (INDOT 511 API)
- [ ] Weather integration (NOAA API)
- [ ] Truck parking availability

### Phase 3 - Advanced Features 📋
- [ ] Fleet management dashboard
- [ ] Driver performance analytics
- [ ] Voice-guided navigation
- [ ] Offline map support

### Phase 4 - National Expansion 🌎
- [ ] All 50 states support
- [ ] International routing (Canada/Mexico)
- [ ] Commercial data partnerships
- [ ] Crowdsourced driver feedback

## 🤝 Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for details.

### Development Setup
1. Fork the repository
2. Create a feature branch: `git checkout -b feature/new-feature`
3. Follow [Android coding standards](https://developer.android.com/kotlin/style-guide)
4. Add tests for new functionality
5. Submit a pull request

### Code Style
- **Kotlin**: Official Kotlin conventions
- **Compose**: Prefer stateless composables
- **Architecture**: Follow MVVM patterns
- **Testing**: Unit tests for business logic

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## 📞 Support

### Documentation
- [Developer Docs](DOCUMENTATION.md) - Complete technical documentation
- [API Reference](docs/api/README.md) - HERE Maps integration guide
- [State Rules Guide](docs/state-rules/README.md) - Adding new state regulations

### Issues
- [Bug Reports](https://github.com/your-org/permitnav/issues) - Report bugs or request features
- [Discussions](https://github.com/your-org/permitnav/discussions) - Community support

### Contact
- **Email**: dev@permitnav.com
- **Slack**: [PermitNav Community](https://permitnav.slack.com)

## 🙏 Acknowledgments

- **HERE Technologies** - Routing and geocoding services
- **Google ML Kit** - Text recognition capabilities
- **Android Team** - Jetpack Compose and modern Android development
- **Truck Driver Community** - Feedback and requirements validation

---

<div align="center">

**Built with ❤️ for the trucking industry**

[📱 Download APK](releases/latest) • [📖 Documentation](DOCUMENTATION.md) • [🐛 Report Bug](issues/new)

</div>