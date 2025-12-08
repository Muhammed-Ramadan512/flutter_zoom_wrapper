package com.mfy.flutter_zoom_wrapper

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
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

    private var shouldSecureScreen: Boolean = true

    // ---------------------------------------------------------
    //  Plugin Setup
    // ---------------------------------------------------------
    override fun onAttachedToEngine(
        @NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding
    ) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "flutter_zoom_wrapper")
        channel.setMethodCallHandler(this)
        context = flutterPluginBinding.applicationContext
        zoomSDK = ZoomSDK.getInstance()
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
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
    //  FLAG_SECURE
    // ---------------------------------------------------------
    private fun applyScreenSecurity(activity: Activity?) {
        if (!shouldSecureScreen) {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
            applyScreenSecurity(currentActivity)
            result.success(true)
            return
        }

        val activity = currentActivity ?: run {
            result.error("NO_ACTIVITY", "Activity is null", null)
            return
        }

        applyScreenSecurity(activity)

        val params = ZoomSDKInitParams().apply {
            jwtToken = jwt
            domain = "zoom.us"
            enableLog = true
            enableGenerateDump = true
            logSize = 5
        }

        zoomSDK.initialize(activity, this, params)
        result.success(true)
    }

    // ---------------------------------------------------------
    //  Helper: إخفاء عناصر Meeting Info من الـ View Tree
    // ---------------------------------------------------------
    private fun sanitizeMeetingInfo(activity: Activity?) {
        if (activity == null) return
        try {
            val root = activity.window?.decorView?.rootView ?: return
            hideSensitiveViews(root)
        } catch (e: Exception) {
            Log.e("ZoomPlugin", "sanitizeMeetingInfo error: ${e.message}")
        }
    }

    private fun hideSensitiveViews(view: View?) {
        if (view == null) return

        if (view is TextView) {
            val txt = view.text?.toString()?.lowercase() ?: ""

            // لو ده لابل Meeting ID / Invite link / Passcode → خبّي السطر كله
            if (txt.contains("meeting id") ||
                txt.contains("invite link") ||
                txt.contains("passcode") ||
                txt.contains("password")
            ) {
                (view.parent as? View)?.visibility = View.GONE
            }

            // لو النص نفسه هو اللينك zoom أو فيه zoom.us → اخفه
            if (txt.contains("zoom.us")) {
                (view.parent as? View)?.visibility = View.GONE
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                hideSensitiveViews(view.getChildAt(i))
            }
        }
    }

    // ---------------------------------------------------------
    //  Meeting Listener → حراسة مستمرة على شاشة Zoom
    // ---------------------------------------------------------
    private val meetingListener = object : InMeetingServiceListener {
        override fun onMeetingStatusChanged(
            status: MeetingStatus?,
            error: Int,
            internalError: Int
        ) {
            if (status == MeetingStatus.MEETING_STATUS_INMEETING) {
                try {
                    // تأكيد حماية شاشة Zoom
                    zoomSDK.inMeetingService.inMeetingActivity?.window
                        ?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

                    // نحاول نمنع شاشة Meeting Info من جوه الـ SDK لو option موجود
                    try {
                        val uiOptions = zoomSDK.inMeetingService.inMeetingUIOptions
                        uiOptions?.isShowMeetingInfoEnabled = false
                    } catch (_: Throwable) {
                        // لو مش موجودة مش هنكراش
                    }

                    // نبدأ "حارس" يراجع الـ View Tree كل ثانية
                    val activity = zoomSDK.inMeetingService.inMeetingActivity
                    activity?.window?.decorView?.post(object : Runnable {
                        override fun run() {
                            sanitizeMeetingInfo(activity)
                            // نعيد الكرة كل ثانية علشان لو الطالب فتح Meeting Info بعدين
                            activity.window?.decorView?.postDelayed(this, 1000)
                        }
                    })

                    Log.d("ZoomPlugin", "Meeting in progress – protection active")

                } catch (e: Exception) {
                    Log.e("ZoomPlugin", "meetingListener error: ${e.message}")
                }
            }
        }
    }

    // باقي الدوال الفاضية المطلوبة من InMeetingServiceListener
    override fun onZoomSDKInitializeResult(errorCode: Int, internalErrorCode: Int) {}
    override fun onZoomAuthIdentityExpired() {}

    // (الـ SDK ممكن يطلب implement لباقي الميثودز؛ لو طلبها زودها فاضية هنا)

    // ---------------------------------------------------------
    //  Join Meeting
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
            no_invite = true
            no_meeting_id = true
            no_meeting_password = true
            no_meeting_url = true
            no_chat_msg = false      // الشات شغال
            no_participants = true   // تخفي الـ list (رجّعها false لو عايزها)
            no_share = true          // تمنع share screen
            // no_record = true       // لو عايز تمنع زر record
        }

        applyScreenSecurity(currentActivity)

        // Listener مهم قبل الانضمام
        zoomSDK.inMeetingService.addListener(meetingListener)

        zoomSDK.meetingService.joinMeetingWithParams(
            currentActivity ?: context,
            joinParams,
            options
        )

        result.success(true)
    }

    // ---------------------------------------------------------
    //  Activity Aware
    // ---------------------------------------------------------
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        activityBinding = binding
        applyScreenSecurity(binding.activity)
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

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }
}
