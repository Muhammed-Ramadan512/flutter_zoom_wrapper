/// A Flutter plugin that wraps Zoom SDK functionality for Android and iOS.
///
/// This class provides methods to initialize Zoom using a JWT token and
/// join Zoom meetings with required details. It also includes a utility
/// to generate JWT tokens using your Zoom SDK credentials.

library;

import 'package:dart_jsonwebtoken/dart_jsonwebtoken.dart';
import 'package:flutter/services.dart';

/// Main interface for communicating with the native Zoom SDK via platform channels.
class FlutterZoomWrapper {
  static const MethodChannel _channel = MethodChannel('flutter_zoom_wrapper');

  /// Initializes the Zoom SDK using a JWT token.
  ///
  /// This must be called before attempting to join a meeting.
  ///
  /// [jwtToken] should be generated securely using your Zoom SDK credentials.
  ///
  /// [enableProtection] controls screenshot/screen recording blocking.
  static Future<void> initializeZoom(
    String jwtToken, {
    bool enableProtection = true, // 🔥 new parameter
  }) async {
    await _channel.invokeMethod('initZoom', {
      'jwt': jwtToken,
      'enableProtection': enableProtection,
    });
  }

  /// Joins a Zoom meeting using the provided details.
  ///
  /// [meetingId] - The Zoom meeting ID as a string.
  /// [meetingPassword] - The password for the Zoom meeting.
  /// [displayName] - The name to be shown in the meeting.
  static Future<void> joinMeeting({
    required String meetingId,
    required String meetingPassword,
    required String displayName,
  }) async {
    await _channel.invokeMethod('joinMeeting', {
      'meetingId': meetingId,
      'meetingPassword': meetingPassword,
      'displayName': displayName,
    });
  }

  /// Generates a JWT token using the provided Zoom SDK API Key and Secret.
  ///
  /// [apiKey] - Your Zoom SDK API Key.
  /// [apiSecret] - Your Zoom SDK API Secret.
  ///
  /// This JWT can be used to initialize the Zoom SDK via [initializeZoom].
  static String generateZoomJWT(String apiKey, String apiSecret) {
    final jwt = JWT({
      'appKey': apiKey,
      'iat': DateTime.now().millisecondsSinceEpoch ~/ 1000,
      'exp': DateTime.now()
              .add(const Duration(hours: 1))
              .millisecondsSinceEpoch ~/
          1000,
      'tokenExp': DateTime.now()
              .add(const Duration(hours: 1))
              .millisecondsSinceEpoch ~/
          1000,
    });

    return jwt.sign(SecretKey(apiSecret), algorithm: JWTAlgorithm.HS256);
  }
}
