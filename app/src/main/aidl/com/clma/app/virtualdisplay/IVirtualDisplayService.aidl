package com.clma.app.virtualdisplay;

import android.os.ParcelFileDescriptor;
import android.view.Surface;

interface IVirtualDisplayService {
    int createDisplay(String name, int width, int height, int density, in Surface surface) = 1;
    int createOwnedDisplay(String name, int width, int height, int density) = 2;
    void attachPreviewSurface(int displayId, in Surface surface) = 3;
    void detachPreviewSurface(int displayId) = 4;
    void releaseDisplay(int displayId) = 5;
    void launchPackage(String packageName, int displayId) = 6;
    ParcelFileDescriptor captureFrameStream(int displayId) = 7;
    String listDisplaysJson() = 13;
    String listInstalledAppsJson() = 14;
    void linkClient(IBinder client) = 16;
    boolean preciseSwipe(int displayId, int x1, int y1, int x2, int y2, int durationMs) = 17;
    void destroy() = 16777114;
}
