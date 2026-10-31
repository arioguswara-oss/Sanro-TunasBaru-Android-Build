package com.sanrotech.pos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;

/** Explicit internal receiver for Android 14+ mutable USB permission PendingIntent. */
public final class UsbPermissionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !MainActivity.USB_PERMISSION_ACTION.equals(intent.getAction())) return;
        UsbDevice device;
        if (Build.VERSION.SDK_INT >= 33)
            device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        else
            device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device == null) return;
        boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
        Intent local = new Intent(MainActivity.USB_PERMISSION_RESULT_ACTION).setPackage(context.getPackageName());
        local.putExtra(UsbManager.EXTRA_DEVICE, device);
        local.putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, granted);
        context.sendBroadcast(local);
    }
}
