# Clearway Cargo - Dispatcher Automation Setup Guide

## Overview
This guide walks you through deploying the dispatcher automation infrastructure for Clearway Cargo. You now have Firebase Cloud Functions, Firestore rules, and a role-based system ready for deployment.

## Prerequisites
- Firebase CLI installed and logged in: `firebase login`
- Firebase project set to: `permit-nav`
- HERE API key for route planning
- Android Studio for app testing

## 1. Deploy Firebase Infrastructure

### Deploy Firestore Rules & Indexes
```bash
# Deploy security rules
firebase deploy --only firestore:rules

# Deploy indexes (may take several minutes)
firebase deploy --only firestore:indexes

# Deploy storage rules
firebase deploy --only storage
```

### Set Environment Variables for Functions
```bash
# Set HERE API key for Cloud Functions
firebase functions:config:set here.api_key="YOUR_HERE_API_KEY_HERE"

# Set OpenAI API key (for existing chat functions)
firebase functions:config:set openai.key="YOUR_OPENAI_API_KEY_HERE"
```

### Install Dependencies and Deploy Functions
```bash
cd permitnav_backend
npm install
npm run deploy
cd ..
```

Your Cloud Functions will be available at:
- `setUserRole`: Assign driver/dispatcher roles
- `createLoad`: Create loads and spawn route planning tasks  
- `planRoute`: Generate HERE truck routes for loads
- `validatePermit`: Validate permits (stub implementation)
- `pdfchat`: Existing chat functionality

## 2. Test Role Assignment

### Set Initial Roles
You can test role assignment using the Firebase Functions emulator or deployed functions:

```javascript
// Example: Set a user as dispatcher
const functions = firebase.functions();
const setUserRole = functions.httpsCallable('setUserRole');

await setUserRole({
  uid: 'your-user-uid-here',
  role: 'dispatcher'  // or 'driver'
});
```

### Test in Android App
1. Build and run the Android app
2. Navigate to Settings screen
3. Switch between Driver and Dispatcher roles
4. Verify role changes are reflected in Firebase Auth custom claims

## 3. Data Model Testing

### Create Test Data
You can create test loads using the Cloud Function:

```javascript
const createLoad = functions.httpsCallable('createLoad');

await createLoad({
  origin: {
    lat: 39.7684,
    lng: -86.1581,
    address: "Indianapolis, IN"
  },
  destination: {
    lat: 39.1653, 
    lng: -86.5264,
    address: "Bloomington, IN"
  },
  pickupWindow: "2025-08-22T08:00:00Z",
  deliveryWindow: "2025-08-22T12:00:00Z",
  dims: { h: 4.1, w: 2.6, l: 18.0 },
  weight: 38000,
  special: ["oversized"]
});
```

This will:
1. Create a load in Firestore
2. Automatically create a `route_plan` task
3. Return the load ID for tracking

### Test Route Planning
```javascript
const planRoute = functions.httpsCallable('planRoute');

await planRoute({
  loadId: 'your-load-id-here'
});
```

This will:
1. Call HERE API for truck routing
2. Store route data in the load's `routePlans` subcollection
3. Mark the route planning task as completed

## 4. N8N Automation Setup (Future)

### Prerequisites for N8N
- N8N instance (cloud or self-hosted)
- Firebase Service Account JSON key
- Slack/Teams webhook for notifications (optional)

### Import Workflow
1. In N8N, import the workflow: `docs/n8n/dispatcher_mvp_flow.json`
2. Configure Firebase credentials using the Service Account JSON
3. Update function URLs if using custom domain
4. Test the workflow with manual trigger

### Workflow Steps
The N8N workflow demonstrates:
1. **Manual Trigger** → Start the automation
2. **Create Load** → Call `createLoad` Cloud Function
3. **Query Tasks** → Find open route planning tasks
4. **Plan Route** → Call `planRoute` for each task
5. **Validate Permit** → If permit exists, validate it
6. **Complete Task** → Mark tasks as done
7. **Notify** → Send completion notification

## 5. Android App Integration

### Driver View Features
- View assigned tasks: `tasks` where `driverUid == auth.uid`
- Filter by status: `open`, `in_progress`, `completed`
- Update task status when work is done

### Dispatcher View Features  
- View all loads: query `loads` collection
- View all tasks: query `tasks` collection
- Create new loads via UI
- Trigger route planning manually
- Assign drivers to loads

### Settings Screen
- Switch between Driver/Dispatcher views (testing)
- Real role changes use `setUserRole` Cloud Function
- UI updates automatically based on Firebase Auth custom claims

## 6. Firestore Collections Structure

### loads
```javascript
{
  origin: { lat, lng, address },
  destination: { lat, lng, address },
  pickupWindow: "ISO date string",
  deliveryWindow: "ISO date string", 
  dims: { h: number, w: number, l: number },
  weight: number,
  special: ["oversized", "hazmat"],
  assignedDriverUid: "string",
  status: "new|assigned|in_transit|delivered",
  createdAt: timestamp,
  createdBy: "uid"
}
```

### tasks
```javascript
{
  type: "route_plan|permit_validation|driver_notification",
  loadId: "string",
  driverUid: "string",
  status: "open|in_progress|completed",
  createdAt: timestamp,
  createdBy: "uid",
  completedAt: timestamp,
  completedBy: "uid"
}
```

### loads/{loadId}/routePlans
```javascript
{
  provider: "HERE",
  routeData: { /* HERE API response */ },
  truck: { /* truck specifications used */ },
  createdAt: timestamp,
  createdBy: "uid"
}
```

## 7. Security & Permissions

### Custom Claims (set via setUserRole)
- `role`: "driver" | "dispatcher"
- `service`: true (for automation accounts)

### Firestore Rules Summary
- **Drivers**: Can read their own tasks and assigned loads
- **Dispatchers**: Can read/write all tasks and loads
- **Service accounts**: Full access for automation
- **All authenticated**: Can read permits, state rules

### Storage Rules Summary
- **Permits**: Drivers can manage their own, dispatchers can manage any
- **State rules**: Read-only for drivers, write access for dispatchers
- **Route plans**: Read-only for drivers, write access for dispatchers

## 8. Troubleshooting

### Common Issues
1. **"Permission denied"**: Check Firestore rules and user roles
2. **"Function not found"**: Ensure functions are deployed
3. **"HERE API error"**: Verify API key is set correctly
4. **"Role not updating"**: Call `getIdToken(true)` to refresh claims

### Debugging Tools
- Firebase Console → Functions → Logs
- Firebase Console → Firestore → Rules playground
- Android Logcat for app-side errors
- N8N execution logs for workflow debugging

## 9. Next Development Steps

### Immediate (MVP)
- [ ] Test end-to-end workflow
- [ ] Add basic dispatcher UI screens
- [ ] Implement driver task notifications
- [ ] Connect permit validation to existing analyzer

### Short-term
- [ ] Add truck management (fleet)
- [ ] Implement driver assignment logic
- [ ] Add real-time task updates
- [ ] Create load board integration

### Medium-term
- [ ] Voice agent integration (Twilio)
- [ ] Email/SMS notifications
- [ ] Load board scraping automation
- [ ] Advanced permit validation

## 10. Support & Documentation

- **CLAUDE.md**: Core project context for AI assistants
- **docs/PRD.md**: Full product requirements
- **Firebase Console**: Live data and logs
- **HERE API Docs**: Routing API reference
- **N8N Docs**: Workflow automation guides

You now have a complete dispatcher automation foundation ready for testing and iteration!