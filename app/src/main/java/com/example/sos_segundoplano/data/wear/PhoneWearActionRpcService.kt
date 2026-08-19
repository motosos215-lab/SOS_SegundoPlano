package com.example.sos_segundoplano.data.wear

import android.util.Log
import com.example.sos_segundoplano.BuildConfig
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking

class PhoneWearActionRpcService : WearableListenerService() {
    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        if (BuildConfig.DEBUG && path.startsWith("/motosos/v1")) {
            Log.d(LOG_TAG, "event=phone_rpc_received path=$path")
        }
        val response = runBlocking { PhoneWearActionRpcHandler(WearPhoneActionProvider.get(applicationContext)).handle(path, request) }
        if (BuildConfig.DEBUG && path.startsWith("/motosos/v1")) {
            Log.d(LOG_TAG, "event=phone_rpc_result path=$path handled=${response != null}")
        }
        return response?.let { Tasks.forResult(it) }
    }

    private companion object {
        const val LOG_TAG = "MotoSOS.WearLink"
    }
}
