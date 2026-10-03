// ITouchEventCallback.aidl
package com.maapocket.core;

// Declare any non-default types here with import statements

oneway interface ITouchEventCallback {
   void onCallback(int x, int y, int type, int contact);
}
