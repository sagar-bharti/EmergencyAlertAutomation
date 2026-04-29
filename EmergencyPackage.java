// EmergencyPackage.java
// Place at: EmergencyApp/android/app/src/main/java/com/emergencyapp/EmergencyPackage.java
//
// Register this in MainApplication.java:
//   @Override protected List<ReactPackage> getPackages() {
//     List<ReactPackage> packages = new PackageList(this).getPackages();
//     packages.add(new EmergencyPackage()); // ← ADD THIS
//     return packages;
//   }

package com.emergencyapp;

import androidx.annotation.NonNull;
import com.facebook.react.ReactPackage;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.uimanager.ViewManager;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class EmergencyPackage implements ReactPackage {

    @NonNull
    @Override
    public List<NativeModule> createNativeModules(@NonNull ReactApplicationContext reactContext) {
        return Arrays.asList(
            new SmsHelper(reactContext),      // NativeModules.SmsHelper
            new LocationHelper(reactContext), // NativeModules.LocationHelper
            new ServiceBridge(reactContext)   // NativeModules.ServiceBridge
        );
    }

    @NonNull
    @Override
    public List<ViewManager> createViewManagers(@NonNull ReactApplicationContext reactContext) {
        return Collections.emptyList();
    }
}
