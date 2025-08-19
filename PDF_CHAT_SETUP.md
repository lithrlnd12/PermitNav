# PDF-Powered Chat Setup Guide

This guide explains how to set up and run the PDF-powered compliance chat system that uses your uploaded state regulation PDFs for accurate responses.

## 🎯 **What This Achieves**

Your new chat system implements the **Information Hierarchy** approach:

1. **PDF Content** (Official state regulations) - **Highest Priority**
2. **OpenAI Knowledge** (General trucking knowledge) - **Secondary**  
3. **Web Knowledge** (Recent updates) - **Tertiary**
4. **Contact Info** (Official state contacts when uncertain) - **Fallback**

## 📋 **Prerequisites**

- Node.js 18+ installed
- OpenAI API key in `local.properties`
- State PDF files in `permitnav_backend/state_rules/` (✅ Already uploaded)
- Firebase project configured (✅ Already done)

## 🚀 **Setup Steps**

### 1. Install Backend Dependencies

```bash
cd permitnav_backend
npm install
```

This installs:
- `express` - Web server
- `cors` - Cross-origin requests
- `pdf-parse` - PDF text extraction  
- `firebase` - Client SDK
- `openai` - AI integration

### 2. Process State PDFs 

Extract text from your 51 state PDFs and cache for fast access:

```bash
# Process all states (recommended)
npm run process-pdfs

# Or process specific states only
npm run process-pdf IN IL OH

# Test single state extraction
npm run test-pdf IN
```

This creates cached text files in `pdf_cache/` directory:
```
pdf_cache/
├── IN_content.txt
├── IL_content.txt  
├── OH_content.txt
└── ... (all 51 states)
```

### 3. Configure Environment

Ensure your `.env` file has:

```bash
OPENAI_API_KEY=sk-proj-your-key-here
NODE_ENV=development
PORT=3000
```

### 4. Start PDF Chat Server

```bash
# Production mode
npm start

# Development mode (auto-restart)
npm run dev
```

Server runs on `http://localhost:3000` with endpoints:
- `GET /health` - Health check
- `GET /api/states` - Available states
- `POST /api/chat` - PDF-powered chat
- `GET /api/test-pdf/:state` - Test PDF content

## 🧪 **Testing the System**

### Backend Health Check

```bash
curl http://localhost:3000/health
```

Expected response:
```json
{
  "status": "healthy",
  "service": "PDF Chat API",
  "timestamp": "2025-08-18T...",
  "version": "1.0.0"
}
```

### Test PDF Content

```bash
curl http://localhost:3000/api/test-pdf/IN
```

Expected response:
```json
{
  "state": "IN",
  "contentLength": 45678,
  "preview": "STATE: IN\nFILENAME: Permit_Nav_Indiana_OSOW_Binder.pdf...",
  "available": true
}
```

### Test Chat API

```bash
curl -X POST http://localhost:3000/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "stateKey": "IN",
    "permitText": "Width: 12ft, Height: 14ft, Weight: 85000lbs",
    "userQuestion": "Do I need escorts for this load?"
  }'
```

Expected response:
```json
{
  "is_compliant": true,
  "violations": [],
  "escorts": "Escorts required for loads over 14' wide in Indiana...",
  "travel_restrictions": "Daylight hours only, no weekends...",
  "notes": "Based on Indiana DOT regulations...",
  "confidence": 0.95,
  "sources": ["PDF Section 3.2", "Indiana regulation 135-IAC-2"],
  "state": "IN"
}
```

## 📱 **Android App Integration**

The Android app (`ChatService.kt`) is already configured to:

1. **Connect to Backend**: Uses `http://10.0.2.2:3000` (emulator localhost)
2. **Permit Selection**: Choose permit → auto-detects state → loads regulations
3. **PDF-Powered Responses**: All chat answers use official state PDFs
4. **Confidence Scoring**: Shows contact info when AI confidence is low
5. **Source Attribution**: Displays which PDF sections were referenced

## 🔄 **Workflow Overview**

```
1. User selects permit in Android app
   ↓
2. ChatService gets permit state (e.g., "IN")
   ↓  
3. Backend loads Indiana PDF content
   ↓
4. OpenAI analyzes question + PDF content + permit details
   ↓
5. Response includes compliance analysis + confidence + sources
   ↓
6. Android shows formatted response with official citations
```

## 📊 **Information Hierarchy in Action**

When user asks: *"Do I need escorts for a 14-foot wide load?"*

**OpenAI receives:**
```
PRIORITY 1: Indiana PDF content - "Loads exceeding 12' width require escort vehicles..."
PRIORITY 2: AI knowledge - General escort requirements  
PRIORITY 3: Web knowledge - Recent regulation updates
FALLBACK: Indiana DOT contact: (317) 232-5533
```

**Response includes:**
- ✅ **PDF-based answer**: "According to Indiana DOT regulations section 3.2..."
- 📊 **High confidence**: 0.95 (PDF source)
- 📚 **Sources**: ["Indiana regulation 135-IAC-2", "PDF Section 3.2"]
- 📞 **No contact info** (high confidence)

vs. Low confidence response:
- ⚠️ **Uncertain answer**: "Requirements may vary..."  
- 📊 **Low confidence**: 0.3
- 📞 **Contact provided**: Indiana DOT (317) 232-5533

## 🎯 **Benefits Over Previous System**

| Feature | Old System | New PDF System |
|---------|------------|----------------|
| **Information Source** | AI general knowledge | Official state PDFs |
| **Accuracy** | Variable | High (official docs) |
| **Citations** | None | PDF sections referenced |
| **Contact Info** | Manual lookup | Auto-extracted from PDFs |
| **Confidence** | Unknown | Scored 0.0-1.0 |
| **State Coverage** | Limited | All 51 jurisdictions |
| **Compliance** | Generic advice | Official regulations |

## 🐛 **Troubleshooting**

### Backend Won't Start
```bash
# Check dependencies
npm install

# Check environment
cat .env | grep OPENAI_API_KEY

# Check port availability  
lsof -i :3000
```

### PDF Processing Fails
```bash
# Check PDF files exist
ls -la state_rules/*.pdf | wc -l  # Should show 51

# Test single PDF
npm run test-pdf IN

# Check cache directory
ls -la pdf_cache/
```

### Android Connection Issues
```bash
# Backend running?
curl http://localhost:3000/health

# Emulator can reach host?  
adb shell ping 10.0.2.2

# Check ChatService logs in Android Studio
```

### Low Quality Responses
1. **Verify PDF content**: Use `/api/test-pdf/:state` endpoint
2. **Check OpenAI key**: Ensure valid and has credits
3. **Review confidence**: Low scores trigger contact info fallback
4. **Test with specific questions**: Generic questions get generic answers

## 🎉 **Success Indicators**

✅ **Backend healthy**: `/health` returns 200  
✅ **PDFs processed**: 51 cache files created  
✅ **Android connects**: Chat UI shows permit selector  
✅ **Responses cite PDFs**: "According to [State] regulations..."  
✅ **Contact info available**: Shown when confidence < 0.8  
✅ **State coverage**: All 51 jurisdictions supported  

Your chat system now provides **official, state-specific compliance guidance** instead of generic AI responses! 🚛📋