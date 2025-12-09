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
import us.zoom.sdk.JoinMeetingOptions
import us.zoom.sdk.JoinMeetingParams
import us.zoom.sdk.MeetingService
import us.zoom.sdk.ZoomError
import us.zoom.sdk.ZoomSDK
import us.zoom.sdk.ZoomSDKInitParams
import us.zoom.sdk.ZoomSDKInitializeListener

class FlutterZoomWrapperPlugin :
    FlutterPlugin,
    MethodCallHandler,
    ActivityAware {

    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private lateinit var zoomSDK: ZoomSDK
    private var activityBinding: ActivityPluginBinding? = null
    private var currentActivity: Activity? = null

    // نتحكم فيها من Flutter علشان نفتح/نقفل FLAG_SECURE
    private var shouldSecureScreen: Boolean = true

    // ---------------------------------------------------------
    //  Plugin Setup
    // ---------------------------------------------------------
    override fun onAttachedToEngine(
        @NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding
    ) {
        context = flutterPluginBinding.applicationContext
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "flutter_zoom_wrapper")
        channel.setMethodCallHandler(this)
        zoomSDK = ZoomSDK.getInstance()
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "getPlatformVersion" ->
                result.success("Android ${android.os.Build.VERSION.RELEASE}")

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

            "joinMeeting" -> {
                val meetingId = call.argument<String>("meetingId")
                val password = call.argument<String>("meetingPassword")
                val displayName = call.argument<String>("displayName")
                joinMeeting(meetingId, password, displayName, result)
            }

            else -> result.notImplemented()
        }
    }

    // ---------------------------------------------------------
    //  FLAG_SECURE helper
    // ---------------------------------------------------------
    private fun applyScreenSecurity(activity: Activity?) {
        if (!shouldSecureScreen) {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            Log.d("ZoomPlugin", "FLAG_SECURE cleared")
            return
        }

        try {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            Log.d("ZoomPlugin", "FLAG_SECURE applied on: $activity")
        } catch (e: Exception) {
            Log.e("ZoomPlugin", "Failed FLAG_SECURE: ${e.message}")
        }
    }

    // ---------------------------------------------------------
    //  Initialize Zoom SDK
    // ---------------------------------------------------------
    private fun initZoom(jwt: String, result: Result) {
        zoomSDK = ZoomSDK.getInstance()

        if (zoomSDK.isInitialized) {
            Log.d("ZoomPlugin", "Zoom SDK already initialized")
            applyScreenSecurity(currentActivity)

            // اختيارى: نخفي invite URL لو متاحة
            try {
                zoomSDK.zoomUIService?.hideMeetingInviteUrl(true)
            } catch (e: Exception) {
                Log.e("ZoomPlugin", "hideMeetingInviteUrl failed: ${e.message}")
            }

            result.success(true)
            return
        }

        val activity = activityBinding?.activity ?: currentActivity ?: run {
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

        val listener = object : ZoomSDKInitializeListener {
            override fun onZoomSDKInitializeResult(errorCode: Int, internalErrorCode: Int) {
                if (errorCode == ZoomError.ZOOM_ERROR_SUCCESS) {
                    Log.d("ZoomPlugin", "Zoom SDK initialized successfully")

                    try {
                        zoomSDK.zoomUIService?.hideMeetingInviteUrl(true)
                    } catch (e: Exception) {
                        Log.e("ZoomPlugin", "hideMeetingInviteUrl failed: ${e.message}")
                    }

                    applyScreenSecurity(currentActivity)
                    result.success(true)
                } else {
                    Log.e(
                        "ZoomPlugin",
                        "Init failed: error=$errorCode internal=$internalErrorCode"
                    )
                    result.error(
                        "INIT_ERROR",
                        "Failed to initialize Zoom SDK. Error: $errorCode, internalErrorCode: $internalErrorCode",
                        null
                    )
                }
            }

            override fun onZoomAuthIdentityExpired() {
                Log.w("ZoomPlugin", "Zoom auth identity expired")
            }
        }

        zoomSDK.initialize(activity, listener, initParams)
    }

    // ---------------------------------------------------------
    //  Join Meeting + UI options
    // ---------------------------------------------------------
    private fun joinMeeting(
        meetingId: String?,
        password: String?,
        displayName: String?,
        result: Result
    ) {
        if (!zoomSDK.isInitialized) {
            result.error("SDK_NOT_INITIALIZED", "Zoom SDK not initialized", null)
            return
        }

        if (meetingId.isNullOrEmpty() ||
            password.isNullOrEmpty() ||
            displayName.isNullOrEmpty()
        ) {
            result.error("INVALID_ARGUMENTS", "Missing meeting details", null)
            return
        }

        val joinParams = JoinMeetingParams().apply {
            meetingNo = meetingId
            this.password = password
            this.displayName = displayName
        }

        val options = JoinMeetingOptions().apply {
            // دول انت شايفهم في الـ IDE وبيشتغلوا مع 6.3.1
            no_invite = true
            no_meeting_id = true
            no_meeting_password = true
            no_meeting_url = true
            no_chat_msg = false    // خليه false علشان الشات يشتغل
            no_participants = false
            no_share = false
            no_record = true
        }

        val ctx = activityBinding?.activity ?: currentActivity ?: context

        applyScreenSecurity(ctx as? Activity)

        val meetingService: MeetingService = zoomSDK.meetingService
        meetingService.joinMeetingWithParams(ctx, joinParams, options)

        result.success(true)
    }

    // ---------------------------------------------------------
    //  Activity Aware
    // ---------------------------------------------------------
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        currentActivity = binding.activity
        applyScreenSecurity(binding.activity)
        Log.d("ZoomPlugin", "Attached to activity: ${binding.activity}")
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activityBinding = null
        currentActivity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activityBinding = binding
        currentActivity = binding.activity
        applyScreenSecurity(binding.activity)
    }

    override fun onDetachedFromActivity() {
        activityBinding = null
        currentActivity = null
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }
}
