package com.mfy.flutter_zoom_wrapper

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.WindowManager
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import us.zoom.sdk.*

class FlutterZoomWrapperPlugin :
    FlutterPlugin,
    MethodCallHandler,
    ActivityAware,
    ZoomSDKInitializeListener {

    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private lateinit var zoomSDK: ZoomSDK
    private var activityBinding: ActivityPluginBinding? = null
    private var currentActivity: Activity? = null

    /** 🔥 هل الحماية مفعّلة ؟ */
    private var shouldSecureScreen: Boolean = true


    // ---------------------------------------------------------------
    //  Flutter Plugin Setup
    // ---------------------------------------------------------------
    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "flutter_zoom_wrapper")
        channel.setMethodCallHandler(this)
        context = flutterPluginBinding.applicationContext
        zoomSDK = ZoomSDK.getInstance()
    }


    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {

            "getPlatformVersion" ->
                result.success("Android ${android.os.Build.VERSION.RELEASE}")


            // -------------------- initZoom ------------------------
            "initZoom" -> {
                val jwt = call.argument<String>("jwt")
                val enableProtection = call.argument<Boolean>("enableProtection") ?: true

                shouldSecureScreen = enableProtection

                if (jwt.isNullOrEmpty()) {
                    result.error("INVALID_ARGUMENT", "JWT token is missing", null)
                } else {
                    initZoom(jwt, result)
                }
            }


            // -------------------- joinMeeting ---------------------
            "joinMeeting" -> {
                val meetingId = call.argument<String>("meetingId")
                val password = call.argument<String>("meetingPassword")
                val displayName = call.argument<String>("displayName")

                joinMeeting(meetingId, password, displayName, result)
            }

            else -> result.notImplemented()
        }
    }


    // ---------------------------------------------------------------
    //         🔥 Apply or Remove Screen Protection
    // ---------------------------------------------------------------
    private fun applyScreenSecurity(activity: Activity?) {

        if (!shouldSecureScreen) {
            Log.d("ZoomPlugin", "🔓 Screen security disabled")
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            return
        }

        try {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            Log.d("ZoomPlugin", "🚫 Screen capture disabled for $activity")
        } catch (e: Exception) {
            Log.e("ZoomPlugin", "Failed to set FLAG_SECURE: ${e.message}")
        }
    }


    // ---------------------------------------------------------------
    //                      Zoom SDK Init
    // ---------------------------------------------------------------
    private fun initZoom(jwt: String, result: Result) {

        zoomSDK = ZoomSDK.getInstance()

        if (zoomSDK.isInitialized) {
            applyScreenSecurity(currentActivity)
            result.success(true)
            return
        }

        val activity = currentActivity
        if (activity == null) {
            result.error("NO_ACTIVITY", "Activity is null. Cannot initialize Zoom SDK.", null)
            return
        }

        applyScreenSecurity(activity)

        val initParams = ZoomSDKInitParams().apply {
            jwtToken = jwt
            domain = "zoom.us"
            enableLog = true
            enableGenerateDump = true
            logSize = 5
        }

        zoomSDK.initialize(activity, this, initParams)
        result.success(true)
    }


    // ---------------------------------------------------------------
    //                            Join Meeting
    // ---------------------------------------------------------------
   private fun joinMeeting(meetingId: String?, password: String?, displayName: String?, result: Result) {

    if (!zoomSDK.isInitialized) {
        result.error("SDK_NOT_INITIALIZED", "Zoom SDK not initialized", null)
        return
    }

    if (meetingId.isNullOrEmpty() || password.isNullOrEmpty() || displayName.isNullOrEmpty()) {
        result.error("INVALID_ARGUMENTS", "Missing meeting details", null)
        return
    }

    val joinParams = JoinMeetingParams().apply {
        meetingNo = meetingId
        this.password = password
        this.displayName = displayName
    }

    // 🔥 إعدادات إخفاء عناصر الواجهة داخل Zoom Meeting
    val options = JoinMeetingOptions().apply {
        no_invite = true                // إخفاء زر Invite
        no_meeting_id = true            // إخفاء Meeting ID
        no_meeting_password = true      // إخفاء Password
        no_meeting_url = true           // إخفاء رابط الاجتماع
        no_chat_msg = true              // إخفاء Chat
        no_participants = true          // إخفاء Participants
        no_share = true                 // إخفاء Share Screen
        no_record = true                // إخفاء Recording
    }

    val act = currentActivity ?: context

    applyScreenSecurity(currentActivity)

    zoomSDK.meetingService.joinMeetingWithParams(act, joinParams, options)

    result.success(true)
}



    // ---------------------------------------------------------------
    //                 Zoom SDK Listener
    // ---------------------------------------------------------------
    override fun onZoomSDKInitializeResult(errorCode: Int, internalErrorCode: Int) {
        if (errorCode == ZoomError.ZOOM_ERROR_SUCCESS) {
            Log.d("Zoom", "Zoom SDK initialized successfully")
        } else {
            Log.e(
                "Zoom",
                "Zoom SDK initialization failed. Error: $errorCode, Internal: $internalErrorCode"
            )
        }
    }

    override fun onZoomAuthIdentityExpired() {
        Log.w("Zoom", "Auth identity expired")
    }


    // ---------------------------------------------------------------
    //                     Activity Aware
    // ---------------------------------------------------------------
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        activityBinding = binding

        applyScreenSecurity(binding.activity)

        binding.addOnPostResumeListener {
            applyScreenSecurity(binding.activity)
        }

        Log.d("ZoomPlugin", "📌 Attached to activity ${binding.activity}")
    }

    override fun onDetachedFromActivityForConfigChanges() {
        currentActivity = null
        activityBinding = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        activityBinding = binding
        applyScreenSecurity(binding.activity)
    }

    override fun onDetachedFromActivity() {
        currentActivity = null
        activityBinding = null
    }


    // ---------------------------------------------------------------
    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }
}
