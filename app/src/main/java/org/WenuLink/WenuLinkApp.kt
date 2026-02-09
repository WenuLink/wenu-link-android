package org.WenuLink

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

import org.WenuLink.sdk.SDKManager
import io.getstream.log.AndroidStreamLogger
import org.WenuLink.adapters.WenuLinkService

class WenuLinkApp : Application() {
    private val TAG: String = WenuLinkApp::class.java.simpleName
    var wenuLinkService: WenuLinkService? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == SDKManager.getIntentAction()) {
                Log.i(TAG, "USB event detected: ${intent.action}")
                context.startActivity(MainActivity.getIntent(context))
            }
        }
    }

    override fun attachBaseContext(paramContext: Context?) {
        super.attachBaseContext(paramContext)
        SDKManager.attachBaseContext(this)
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "STARTING..")
        if (!SDKManager.isContextAttached) {
            Log.e(TAG, "Fatal error: SDK context not attached!")
            return
        }
        // Register to listen for USB attach/detach
        val filter = IntentFilter().apply {
            addAction(SDKManager.getIntentAction())  // your SDK’s action
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_EXPORTED)
        }
        else {
            registerReceiver(usbReceiver, filter)
        }

        AndroidStreamLogger.installOnDebuggableApp(this)
    }

}
