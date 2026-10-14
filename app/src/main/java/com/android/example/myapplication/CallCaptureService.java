package com.android.example.myapplication;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.telecom.Call;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class CallCaptureService extends Service {

    private static final String TAG = "CallCaptureService";

    public static final String ACTION_STATUS_CHANGED =
            "com.android.example.myapplication.action.AUTO_CAPTURE_STATUS_CHANGED";

    private static final String ACTION_START_MONITORING =
            "com.android.example.myapplication.action.START_CALL_MONITORING";
    private static final String ACTION_START_UPLINK_INJECTION =
            "com.android.example.myapplication.action.START_UPLINK_INJECTION";
    private static final String ACTION_STOP_UPLINK_INJECTION =
            "com.android.example.myapplication.action.STOP_UPLINK_INJECTION";
    private static final String ACTION_START_DOWNLINK_REPLACEMENT =
            "com.android.example.myapplication.action.START_DOWNLINK_REPLACEMENT";
    private static final String ACTION_STOP_DOWNLINK_REPLACEMENT =
            "com.android.example.myapplication.action.STOP_DOWNLINK_REPLACEMENT";
    private static final String EXTRA_INJECTION_PCM_PATH = "injection_pcm_path";
    private static final String EXTRA_REPLACEMENT_PCM_PATH = "replacement_pcm_path";
    private static final String CALL_AUDIO_INTERCEPTION_PERMISSION =
            "android.permission.CALL_AUDIO_INTERCEPTION";
    private static final int NOTIFICATION_ID = 2001;
    private static final String NOTIFICATION_CHANNEL_ID = "call_capture_monitor";
    private static final long CALL_AUDIO_SETTLE_DELAY_MS = 300;

    private static final int SAMPLE_RATE_HZ = PcmFormat.TARGET_SAMPLE_RATE_HZ;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int READ_CHUNK_BYTES = SAMPLE_RATE_HZ * BYTES_PER_SAMPLE / 20;
    private static final int INJECTION_CHANNEL_MASK = AudioFormat.CHANNEL_OUT_STEREO;
    private static final int INJECTION_FRAME_BYTES = BYTES_PER_SAMPLE * 2;

    private static volatile boolean monitoring;
    private static volatile boolean captureRunningForUi;
    private static volatile String currentStatus = "自动监听服务未启动";
    private static volatile String lastCaptureSummary;
    private static volatile File lastDownlinkOutputFile;
    private static volatile File lastUplinkOutputFile;
    private static volatile File lastCompletedDownlinkOutputFile;
    private static volatile boolean uplinkInjectionActiveForUi;
    private static volatile String uplinkInjectionStatus = "上行注入未启动";
    private static volatile boolean downlinkReplacementActiveForUi;
    private static volatile String downlinkReplacementStatus = "下行替换未启动";
    private static volatile CallCaptureService instance;

    private final Object recorderLock = new Object();
    private final Object injectionLock = new Object();
    private final Object downlinkReplacementLock = new Object();
    private final AtomicBoolean captureRunning = new AtomicBoolean(false);
    private final AtomicBoolean injectionPlaybackRunning = new AtomicBoolean(false);
    private final AtomicInteger streamsRemaining = new AtomicInteger(0);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private NotificationManager notificationManager;
    private AudioManager audioManager;
    private CallAudioPlatform platform;
    private int injectionGeneration;
    private boolean vendorUplinkMute;
    private CaptureStream downlinkStream;
    private CaptureStream uplinkStream;
    private AudioTrack injectionAudioTrack;
    private boolean injectionSessionActive;
    private boolean muteStateBeforeInjection;
    private DownlinkReplacementSession downlinkReplacementSession;
    private File pendingDownlinkReplacementFile;
    private int foregroundCallState = Call.STATE_DISCONNECTED;
    private volatile boolean destroying;

    private final Runnable delayedCaptureStart = () -> {
        if (monitoring
                && foregroundCallState == Call.STATE_ACTIVE
                && !isDownlinkReplacementActive()
                && !captureRunning.get()) {
            startBidirectionalCapture();
        }
    };

    private static final class CaptureStream {
        final String directionName;
        final int audioSource;
        final AudioRecord recorder;
        final File outputFile;
        final int sampleRate;
        volatile long totalBytes;
        volatile int maximumPeak;
        volatile String terminalError;

        CaptureStream(
                String directionName,
                int audioSource,
                AudioRecord recorder,
                File outputFile) {
            this.directionName = directionName;
            this.audioSource = audioSource;
            this.recorder = recorder;
            this.outputFile = outputFile;
            this.sampleRate = PcmFormat.fromFile(outputFile).sampleRate;
        }
    }

    private static final class DownlinkReplacementSession {
        final AtomicBoolean running = new AtomicBoolean(true);
        final File pcmFile;
        final PcmFormat format;
        final boolean vendorGains;
        final AudioRecord originalDownlinkRecord;
        final AudioRecord microphoneRecord;
        final AudioTrack callUplinkTrack;
        AudioTrack replacementTrack;

        DownlinkReplacementSession(
                File pcmFile,
                AudioRecord originalDownlinkRecord,
                AudioRecord microphoneRecord,
                AudioTrack callUplinkTrack,
                AudioTrack replacementTrack) {
            this.pcmFile = pcmFile;
            this.format = PcmFormat.fromFile(pcmFile);
            this.vendorGains = originalDownlinkRecord == null;
            this.originalDownlinkRecord = originalDownlinkRecord;
            this.microphoneRecord = microphoneRecord;
            this.callUplinkTrack = callUplinkTrack;
            this.replacementTrack = replacementTrack;
        }
    }

    public static Intent createStartIntent(Context context) {
        return new Intent(context, CallCaptureService.class)
                .setAction(ACTION_START_MONITORING);
    }

    public static Intent createStartUplinkInjectionIntent(Context context, File pcmFile) {
        return new Intent(context, CallCaptureService.class)
                .setAction(ACTION_START_UPLINK_INJECTION)
                .putExtra(EXTRA_INJECTION_PCM_PATH, pcmFile.getAbsolutePath());
    }

    public static Intent createStopUplinkInjectionIntent(Context context) {
        return new Intent(context, CallCaptureService.class)
                .setAction(ACTION_STOP_UPLINK_INJECTION);
    }

    public static Intent createStartDownlinkReplacementIntent(Context context, File pcmFile) {
        return new Intent(context, CallCaptureService.class)
                .setAction(ACTION_START_DOWNLINK_REPLACEMENT)
                .putExtra(EXTRA_REPLACEMENT_PCM_PATH, pcmFile.getAbsolutePath());
    }

    public static Intent createStopDownlinkReplacementIntent(Context context) {
        return new Intent(context, CallCaptureService.class)
                .setAction(ACTION_STOP_DOWNLINK_REPLACEMENT);
    }

    public static boolean isMonitoring() {
        return monitoring;
    }

    public static boolean isCapturing() {
        return captureRunningForUi;
    }

    public static String getCurrentStatus() {
        return currentStatus;
    }

    public static boolean isUplinkInjectionActive() {
        return uplinkInjectionActiveForUi;
    }

    public static String getUplinkInjectionStatus() {
        return uplinkInjectionStatus;
    }

    public static boolean isDownlinkReplacementActive() {
        return downlinkReplacementActiveForUi;
    }

    public static String getDownlinkReplacementStatus() {
        return downlinkReplacementStatus;
    }

    @Nullable
    public static File getLastDownlinkOutputFile() {
        return lastDownlinkOutputFile;
    }

    @Nullable
    public static File getLastUplinkOutputFile() {
        return lastUplinkOutputFile;
    }

    @Nullable
    public static File getLastCompletedDownlinkOutputFile() {
        return lastCompletedDownlinkOutputFile;
    }

    public static void onTelecomCallStateChanged(int callState) {
        CallCaptureService service = instance;
        if (service != null) {
            service.mainHandler.post(() -> service.handleForegroundCallState(callState));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        notificationManager = getSystemService(NotificationManager.class);
        audioManager = getSystemService(AudioManager.class);
        platform = new CallAudioPlatform(audioManager);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (action == null || ACTION_START_MONITORING.equals(action)) {
            startMonitoring();
        } else if (ACTION_START_UPLINK_INJECTION.equals(action)) {
            if (!monitoring) {
                startMonitoring();
            }
            String pcmPath = intent.getStringExtra(EXTRA_INJECTION_PCM_PATH);
            if (monitoring && pcmPath != null) {
                startUplinkInjection(new File(pcmPath));
            } else if (pcmPath == null) {
                updateInjectionStatus("无法开始上行注入：没有指定 PCM 文件");
            }
        } else if (ACTION_STOP_UPLINK_INJECTION.equals(action)) {
            stopUplinkInjection("用户停止上行注入");
        } else if (ACTION_START_DOWNLINK_REPLACEMENT.equals(action)) {
            if (!monitoring) {
                startMonitoring();
            }
            String pcmPath = intent.getStringExtra(EXTRA_REPLACEMENT_PCM_PATH);
            if (monitoring && pcmPath != null) {
                startDownlinkReplacement(new File(pcmPath));
            } else if (pcmPath == null) {
                updateDownlinkReplacementStatus("无法开始下行替换：没有指定 PCM 文件");
            }
        } else if (ACTION_STOP_DOWNLINK_REPLACEMENT.equals(action)) {
            stopDownlinkReplacement("用户停止下行替换");
        }
        return START_NOT_STICKY;
    }

    private void startMonitoring() {
        if (monitoring) {
            updateStatus(currentStatus);
            return;
        }

        try {
            Notification notification = buildNotification("等待电话接通");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException exception) {
            currentStatus = "启动前台监听服务失败：" + exception.getMessage();
            broadcastStatus();
            stopSelf();
            return;
        }

        destroying = false;
        monitoring = true;
        foregroundCallState = CallStateInCallService.getCurrentCallState();
        Log.i(TAG, "Automatic call monitoring started; initial Telecom state="
                + telecomCallStateName(foregroundCallState));
        handleForegroundCallState(foregroundCallState);
    }

    private void handleForegroundCallState(int state) {
        if (foregroundCallState != state) {
            Log.i(TAG, "Observed Telecom state "
                    + telecomCallStateName(foregroundCallState)
                    + " -> " + telecomCallStateName(state));
        }
        foregroundCallState = state;
        mainHandler.removeCallbacks(delayedCaptureStart);

        if (state != Call.STATE_ACTIVE && injectionSessionActive) {
            stopUplinkInjection("通话已结束");
        }
        if (state != Call.STATE_ACTIVE && (isDownlinkReplacementActive()
                || pendingDownlinkReplacementFile != null)) {
            stopDownlinkReplacement("通话已结束");
        }

        if (state == Call.STATE_ACTIVE) {
            if (isDownlinkReplacementActive()) {
                updateStatus("下行替换进行中，普通双路采集已暂停");
                return;
            }
            if (!captureRunning.get()) {
                updateStatus("电话已接通，等待通话音频路由稳定");
                mainHandler.postDelayed(delayedCaptureStart, CALL_AUDIO_SETTLE_DELAY_MS);
            }
            return;
        }

        if (captureRunning.get()) {
            stopCapture("通话已结束");
            return;
        }

        if (state == Call.STATE_DIALING
                || state == Call.STATE_CONNECTING
                || state == Call.STATE_RINGING
                || state == Call.STATE_SELECT_PHONE_ACCOUNT
                || state == Call.STATE_AUDIO_PROCESSING) {
            updateStatus("检测到电话，但尚未接通：" + telecomCallStateName(state));
        } else {
            updateIdleStatus();
        }
    }

    private void updateIdleStatus() {
        String summary = lastCaptureSummary;
        if (summary == null) {
            updateStatus("自动监听已启动，等待电话接通");
        } else {
            updateStatus("自动监听已启动，等待下一通电话\n" + summary);
        }
    }

    private void startBidirectionalCapture() {
        if (isDownlinkReplacementActive()) {
            updateStatus("下行替换进行中，普通双路采集已暂停");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            updateStatus("无法启动双路采集：RECORD_AUDIO 未授予");
            return;
        }
        if (downlinkStream != null || uplinkStream != null || captureRunningForUi) {
            updateStatus("上一组采集仍在保存，等待释放后再开始");
            return;
        }
        if (checkSelfPermission("android.permission.CAPTURE_AUDIO_OUTPUT")
                != PackageManager.PERMISSION_GRANTED) {
            updateStatus("无法采集通话音频：CAPTURE_AUDIO_OUTPUT 未授予");
            return;
        }
        int sampleRate = platform.captureSampleRate();
        int channelMask = platform.unisoc ? AudioFormat.CHANNEL_IN_STEREO : CHANNEL_MASK;
        int minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate, channelMask, ENCODING);
        if (minBufferSize <= 0) {
            updateStatus("AudioRecord.getMinBufferSize() 失败：" + minBufferSize);
            return;
        }
        int recorderBufferSize = Math.max(
                minBufferSize * 2,
                sampleRate * BYTES_PER_SAMPLE * (platform.unisoc ? 2 : 1) / 5);

        String timestamp = new SimpleDateFormat(
                "yyyyMMdd_HHmmss", Locale.US).format(new Date());
        CaptureStream newDownlink = null;
        CaptureStream newUplink = null;
        try {
            String suffix = "_" + sampleRate + "_mono_s16le.pcm";
            if (platform.unisoc) {
                newDownlink = createCaptureStream("下行", MediaRecorder.AudioSource.VOICE_CALL,
                        "downlink_auto_" + timestamp + suffix, recorderBufferSize, sampleRate,
                        AudioFormat.CHANNEL_IN_STEREO);
                newUplink = new CaptureStream("上行", MediaRecorder.AudioSource.VOICE_CALL,
                        newDownlink.recorder, createOutputFile("uplink_auto_" + timestamp + suffix));
                startRecorder(newDownlink);
            } else {
                newDownlink = createCaptureStream("下行", MediaRecorder.AudioSource.VOICE_DOWNLINK,
                        "downlink_auto_" + timestamp + suffix, recorderBufferSize, sampleRate, CHANNEL_MASK);
                newUplink = createCaptureStream("上行", MediaRecorder.AudioSource.VOICE_UPLINK,
                        "uplink_auto_" + timestamp + suffix, recorderBufferSize, sampleRate, CHANNEL_MASK);
                startRecorder(newDownlink);
                startRecorder(newUplink);
            }
        } catch (SecurityException exception) {
            releaseFailedStart(newDownlink, newUplink);
            updateStatus("双路自动采集权限被拒绝：" + exception.getMessage());
            return;
        } catch (IOException | RuntimeException exception) {
            releaseFailedStart(newDownlink, newUplink);
            updateStatus("创建双路自动采集失败：" + exception.getMessage());
            return;
        }

        synchronized (recorderLock) {
            if (downlinkStream != null || uplinkStream != null) {
                releaseFailedStart(newDownlink, newUplink);
                updateStatus("上一组双路采集流尚未释放");
                return;
            }
            downlinkStream = newDownlink;
            uplinkStream = newUplink;
        }

        lastDownlinkOutputFile = newDownlink.outputFile;
        lastUplinkOutputFile = newUplink.outputFile;
        streamsRemaining.set(platform.unisoc ? 1 : 2);
        captureRunning.set(true);
        captureRunningForUi = true;

        Log.i(TAG, "Capture backend=" + (platform.unisoc ? "VOICE_CALL stereo TX=L RX=R" : "separate sources")
                + ", sampleRate=" + sampleRate);
        Log.i(TAG, "Starting downlink capture: "
                + newDownlink.outputFile.getAbsolutePath());
        Log.i(TAG, "Starting uplink capture: "
                + newUplink.outputFile.getAbsolutePath());
        updateStatus("电话已接通，正在分别采集下行和上行 PCM");

        CaptureStream finalDownlink = newDownlink;
        CaptureStream finalUplink = newUplink;
        if (platform.unisoc) {
            new Thread(() -> stereoCaptureLoop(finalDownlink, finalUplink),
                    "AutomaticVoiceCallStereoCapture").start();
        } else {
            new Thread(() -> captureLoop(finalDownlink), "AutomaticVoiceDownlinkCapture").start();
            new Thread(() -> captureLoop(finalUplink), "AutomaticVoiceUplinkCapture").start();
        }
    }

    @SuppressLint("MissingPermission")
    private CaptureStream createCaptureStream(
            String directionName,
            int audioSource,
            String fileName,
            int recorderBufferSize, int sampleRate, int channelMask) throws IOException {
        AudioRecord recorder = new AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(ENCODING)
                        .build())
                .setBufferSizeInBytes(recorderBufferSize)
                .build();
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            throw new IllegalStateException(
                    directionName + " AudioRecord 未初始化，source=" + audioSource);
        }
        final File outputFile;
        try {
            outputFile = createOutputFile(fileName);
        } catch (IOException | RuntimeException exception) {
            recorder.release();
            throw exception;
        }
        return new CaptureStream(directionName, audioSource, recorder, outputFile);
    }

    private void startRecorder(CaptureStream stream) {
        stream.recorder.startRecording();
        if (stream.recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            throw new IllegalStateException(
                    stream.directionName + " AudioRecord 没有进入 RECORDING 状态");
        }
    }

    private File createOutputFile(String fileName) throws IOException {
        File baseDirectory = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (baseDirectory == null) {
            baseDirectory = getFilesDir();
        }
        File captureDirectory = new File(baseDirectory, "call_pcm");
        if (!captureDirectory.exists() && !captureDirectory.mkdirs()) {
            throw new IOException("无法创建目录 " + captureDirectory);
        }
        return new File(captureDirectory, fileName);
    }

    private void captureLoop(CaptureStream stream) {
        byte[] buffer = new byte[READ_CHUNK_BYTES];
        long lastStatusUpdateMs = 0;

        try (BufferedOutputStream output = new BufferedOutputStream(
                new FileOutputStream(stream.outputFile), READ_CHUNK_BYTES * 4)) {
            while (captureRunning.get()) {
                int bytesRead = stream.recorder.read(
                        buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (bytesRead > 0) {
                    output.write(buffer, 0, bytesRead);
                    stream.totalBytes += bytesRead;
                    stream.maximumPeak = Math.max(
                            stream.maximumPeak,
                            calculatePcm16Peak(buffer, bytesRead));

                    long nowMs = SystemClock.elapsedRealtime();
                    if (nowMs - lastStatusUpdateMs >= 500) {
                        lastStatusUpdateMs = nowMs;
                        mainHandler.post(this::updateDualCaptureStatus);
                    }
                } else if (bytesRead < 0) {
                    if (captureRunning.get()) {
                        stream.terminalError = "AudioRecord.read() 失败："
                                + readErrorName(bytesRead);
                    }
                    break;
                }
            }
            output.flush();
        } catch (IOException exception) {
            stream.terminalError = "写文件失败：" + exception.getMessage();
        } catch (RuntimeException exception) {
            stream.terminalError = "采集异常：" + exception.getMessage();
        } finally {
            releaseStreamRecorder(stream);
            if (stream.terminalError != null && captureRunning.get()) {
                Log.e(TAG, stream.directionName + " capture failed: " + stream.terminalError);
                mainHandler.post(() -> stopCapture(stream.directionName + "采集失败"));
            }
            if (streamsRemaining.decrementAndGet() == 0) {
                mainHandler.post(this::finishCaptureSession);
            }
        }
    }

    private void stereoCaptureLoop(CaptureStream downlink, CaptureStream uplink) {
        // Read size must be a multiple of one TX/RX frame (4 bytes).
        byte[] stereo = new byte[3200];
        byte[] tx = new byte[1600];
        byte[] rx = new byte[1600];
        long lastUpdate = 0;
        try (BufferedOutputStream txOut = new BufferedOutputStream(new FileOutputStream(uplink.outputFile));
             BufferedOutputStream rxOut = new BufferedOutputStream(new FileOutputStream(downlink.outputFile))) {
            while (captureRunning.get()) {
                int read = downlink.recorder.read(stereo, 0, stereo.length, AudioRecord.READ_BLOCKING);
                if (read < 0) {
                    if (captureRunning.get()) throw new IOException("VOICE_CALL.read(): " + readErrorName(read));
                    break;
                }
                if (read == 0) continue;
                int monoBytes = PcmIo.splitTxRx(stereo, read, tx, rx);
                txOut.write(tx, 0, monoBytes); rxOut.write(rx, 0, monoBytes);
                uplink.totalBytes += monoBytes; downlink.totalBytes += monoBytes;
                uplink.maximumPeak = Math.max(uplink.maximumPeak, calculatePcm16Peak(tx, monoBytes));
                downlink.maximumPeak = Math.max(downlink.maximumPeak, calculatePcm16Peak(rx, monoBytes));
                long now = SystemClock.elapsedRealtime();
                if (now - lastUpdate >= 500) {
                    lastUpdate = now;
                    mainHandler.post(this::updateDualCaptureStatus);
                }
            }
        } catch (IOException | RuntimeException e) {
            downlink.terminalError = uplink.terminalError = "双声道采集失败：" + e.getMessage();
        } finally {
            releaseStreamRecorder(downlink); // Shared recorder must be released exactly once.
            captureRunning.set(false);
            streamsRemaining.decrementAndGet();
            mainHandler.post(this::finishCaptureSession);
        }
    }

    private void updateDualCaptureStatus() {
        if (!captureRunning.get()) {
            return;
        }
        CaptureStream downlink = downlinkStream;
        CaptureStream uplink = uplinkStream;
        if (downlink == null || uplink == null) {
            return;
        }
        updateStatus(String.format(
                Locale.US,
                "双路采集中\n下行：%.1f 秒，峰值 %d\n上行：%.1f 秒，峰值 %d",
                new PcmFormat(downlink.sampleRate).seconds(downlink.totalBytes),
                downlink.maximumPeak,
                new PcmFormat(uplink.sampleRate).seconds(uplink.totalBytes),
                uplink.maximumPeak));
    }

    private void finishCaptureSession() {
        captureRunning.set(false);
        captureRunningForUi = false;

        CaptureStream downlink = downlinkStream;
        CaptureStream uplink = uplinkStream;
        if (downlink == null || uplink == null) {
            return;
        }

        boolean hasError = downlink.terminalError != null || uplink.terminalError != null;
        if (downlink.terminalError == null && downlink.totalBytes > 0) {
            lastCompletedDownlinkOutputFile = downlink.outputFile;
        }
        lastCaptureSummary = String.format(
                Locale.US,
                "%s\n下行：%.1f 秒，%d 字节，峰值 %d%s\n上行：%.1f 秒，%d 字节，峰值 %d%s\n文件：%s / %s",
                hasError ? "上次双路采集结束（存在错误）" : "上次双路采集成功",
                new PcmFormat(downlink.sampleRate).seconds(downlink.totalBytes),
                downlink.totalBytes,
                downlink.maximumPeak,
                errorSuffix(downlink.terminalError),
                new PcmFormat(uplink.sampleRate).seconds(uplink.totalBytes),
                uplink.totalBytes,
                uplink.maximumPeak,
                errorSuffix(uplink.terminalError),
                downlink.outputFile.getName(),
                uplink.outputFile.getName());
        Log.i(TAG, lastCaptureSummary.replace('\n', ' '));

        synchronized (recorderLock) {
            downlinkStream = null;
            uplinkStream = null;
        }

        if (!destroying) {
            if (pendingDownlinkReplacementFile != null) {
                File pending = pendingDownlinkReplacementFile;
                pendingDownlinkReplacementFile = null;
                mainHandler.post(() -> startDownlinkReplacement(pending));
                return;
            }
            if (isDownlinkReplacementActive()) {
                updateStatus("下行替换进行中，普通双路采集已暂停");
            } else {
                updateIdleStatus();
                if (monitoring
                        && foregroundCallState == Call.STATE_ACTIVE
                        && !hasError) {
                    mainHandler.postDelayed(delayedCaptureStart, CALL_AUDIO_SETTLE_DELAY_MS);
                }
            }
        }
    }

    private static String errorSuffix(@Nullable String error) {
        return error == null ? "" : "，错误：" + error;
    }

    private void stopCapture(String reason) {
        if (!captureRunning.getAndSet(false)) {
            return;
        }
        Log.i(TAG, "Stopping bidirectional call capture: " + reason);
        updateStatus(reason + "，正在停止并分别保存上下行 PCM");

        synchronized (recorderLock) {
            requestRecorderStop(downlinkStream);
            if (uplinkStream != null && (downlinkStream == null
                    || uplinkStream.recorder != downlinkStream.recorder)) requestRecorderStop(uplinkStream);
        }
    }

    private static void requestRecorderStop(@Nullable CaptureStream stream) {
        if (stream == null) {
            return;
        }
        try {
            if (stream.recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                stream.recorder.stop();
            }
        } catch (IllegalStateException ignored) {
            // 采集线程会完成 release。
        }
    }

    private void releaseStreamRecorder(CaptureStream stream) {
        synchronized (recorderLock) {
            requestRecorderStop(stream);
            stream.recorder.release();
        }
    }

    private void releaseFailedStart(
            @Nullable CaptureStream downlink,
            @Nullable CaptureStream uplink) {
        synchronized (recorderLock) {
            releaseFailedStream(downlink);
            if (uplink != null && (downlink == null || uplink.recorder != downlink.recorder)) {
                releaseFailedStream(uplink);
            }
        }
    }

    private static void releaseFailedStream(@Nullable CaptureStream stream) {
        if (stream == null) {
            return;
        }
        requestRecorderStop(stream);
        stream.recorder.release();
    }

    private void startUplinkInjection(File pcmFile) {
        if (isDownlinkReplacementActive()) {
            updateInjectionStatus("无法开始上行注入：请先停止下行替换");
            return;
        }
        if (injectionSessionActive) {
            updateInjectionStatus("上行注入已经启动；请先停止当前注入");
            return;
        }
        if (foregroundCallState != Call.STATE_ACTIVE
                || CallStateInCallService.getCurrentCallState() != Call.STATE_ACTIVE) {
            updateInjectionStatus("无法开始上行注入：电话尚未接通");
            return;
        }
        if (checkSelfPermission(Manifest.permission.MODIFY_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            updateInjectionStatus("无法开始上行注入：MODIFY_PHONE_STATE 未授予");
            return;
        }
        if (!isValidInjectionPcm(pcmFile)) {
            updateInjectionStatus("无法开始上行注入：所选 PCM 无效或不在 App PCM 目录");
            return;
        }

        if (injectionAudioTrack != null) {
            updateInjectionStatus("上一条上行注入流尚未释放");
            return;
        }
        PcmFormat format = PcmFormat.fromFile(pcmFile);
        AudioDeviceInfo telephonyTxDevice = findTelephonyTxDevice();
        if (telephonyTxDevice == null) {
            updateInjectionStatus("无法开始上行注入：设备未声明 Telephony Tx 输出");
            return;
        }

        int minBufferSize = AudioTrack.getMinBufferSize(
                format.sampleRate, INJECTION_CHANNEL_MASK, ENCODING);
        if (minBufferSize <= 0) {
            updateInjectionStatus("无法开始上行注入：AudioTrack 最小缓冲区错误 "
                    + minBufferSize);
            return;
        }

        final AudioTrack track;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(format.sampleRate)
                            .setChannelMask(INJECTION_CHANNEL_MASK)
                            .setEncoding(ENCODING)
                            .build())
                    .setBufferSizeInBytes(Math.max(
                            minBufferSize * 2,
                            format.sampleRate * INJECTION_FRAME_BYTES / 5))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (RuntimeException exception) {
            updateInjectionStatus("创建 Telephony Tx AudioTrack 失败："
                    + exception.getMessage());
            return;
        }

        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            updateInjectionStatus("Telephony Tx AudioTrack 未初始化");
            return;
        }
        if (!track.setPreferredDevice(telephonyTxDevice)) {
            track.release();
            updateInjectionStatus("AudioTrack 拒绝路由到 Telephony Tx");
            return;
        }

        int currentMuteState = CallStateInCallService.getCurrentMuteState();
        muteStateBeforeInjection = currentMuteState == 1;
        vendorUplinkMute = platform.supportsVoiceGains;
        try {
            if (vendorUplinkMute) platform.muteOriginal(true, false);
            else if (currentMuteState < 0 || !CallStateInCallService.requestMuted(true)) {
                throw new IllegalStateException("无法静音真实麦克风");
            }
        } catch (RuntimeException e) {
            track.release();
            restoreMuteStateAfterInjection();
            updateInjectionStatus("已取消上行注入：" + e.getMessage());
            return;
        }

        synchronized (injectionLock) {
            if (injectionSessionActive || injectionAudioTrack != null) {
                track.release();
                restoreMuteStateAfterInjection();
                updateInjectionStatus("上一条上行注入流尚未释放");
                return;
            }
            injectionAudioTrack = track;
            injectionSessionActive = true;
            uplinkInjectionActiveForUi = true;
        }

        try {
            track.play();
        } catch (IllegalStateException exception) {
            abortUplinkInjectionStart(track, "启动 Telephony Tx AudioTrack 失败："
                    + exception.getMessage());
            return;
        }
        if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            abortUplinkInjectionStart(track, "Telephony Tx AudioTrack 没有进入 PLAYING 状态");
            return;
        }

        injectionPlaybackRunning.set(true);
        double seconds = format.seconds(pcmFile.length());
        updateInjectionStatus(String.format(
                Locale.US,
                "正在向通话上行注入：%s（%.1f 秒）\n真实麦克风已静音",
                pcmFile.getName(),
                seconds));
        Log.i(TAG, "Starting official Telephony Tx injection: file="
                + pcmFile.getAbsolutePath()
                + ", preferredDeviceId=" + telephonyTxDevice.getId());

        int generation = ++injectionGeneration;
        new Thread(
                () -> uplinkInjectionLoop(track, pcmFile, generation),
                "TelephonyTxPcmInjection").start();
    }

    private boolean isValidInjectionPcm(File pcmFile) {
        return PcmFileStore.isUsablePcm(this, pcmFile);
    }

    @Nullable
    private AudioDeviceInfo findTelephonyTxDevice() {
        if (audioManager == null) {
            return null;
        }
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.isSink() && device.getType() == AudioDeviceInfo.TYPE_TELEPHONY) {
                return device;
            }
        }
        return null;
    }

    private void uplinkInjectionLoop(AudioTrack track, File pcmFile, int generation) {
        byte[] monoBuffer = new byte[READ_CHUNK_BYTES];
        byte[] stereoBuffer = new byte[READ_CHUNK_BYTES * 2];
        long injectedMonoBytes = 0;
        String terminalError = null;
        boolean reachedEnd = false;
        boolean routeVerified = false;

        try (BufferedInputStream input = new BufferedInputStream(
                new FileInputStream(pcmFile), READ_CHUNK_BYTES * 4)) {
            while (injectionPlaybackRunning.get()) {
                int bytesRead = PcmIo.readFrames(input, monoBuffer, BYTES_PER_SAMPLE);
                if (bytesRead < 0) {
                    reachedEnd = true;
                    break;
                }
                int monoBytes = bytesRead & ~1;
                int stereoBytes = duplicateMonoToStereo(monoBuffer, monoBytes, stereoBuffer);
                int offset = 0;
                while (offset < stereoBytes && injectionPlaybackRunning.get()) {
                    int bytesWritten = track.write(
                            stereoBuffer,
                            offset,
                            stereoBytes - offset,
                            AudioTrack.WRITE_BLOCKING);
                    if (bytesWritten <= 0) {
                        terminalError = "Telephony Tx AudioTrack.write() 失败：" + bytesWritten;
                        break;
                    }
                    offset += bytesWritten;
                }
                injectedMonoBytes += offset / 2;
                if (terminalError != null) {
                    break;
                }

                if (!routeVerified && injectedMonoBytes > 0) {
                    AudioDeviceInfo routedDevice = track.getRoutedDevice();
                    if (routedDevice == null
                            || routedDevice.getType() != AudioDeviceInfo.TYPE_TELEPHONY) {
                        terminalError = "实际输出未路由到 Telephony Tx";
                        break;
                    }
                    routeVerified = true;
                    Log.i(TAG, "Telephony Tx route verified: deviceId="
                            + routedDevice.getId());
                }
            }
            if (reachedEnd && terminalError == null) {
                PcmPlayback.drain(track, injectedMonoBytes * 2, INJECTION_FRAME_BYTES,
                        injectionPlaybackRunning::get);
            }
        } catch (IOException exception) {
            terminalError = "读取注入 PCM 失败：" + exception.getMessage();
        } catch (RuntimeException exception) {
            terminalError = "上行注入异常：" + exception.getMessage();
        } finally {
            injectionPlaybackRunning.set(false);
            releaseInjectionAudioTrack(track);
            String finalError = terminalError;
            boolean finalReachedEnd = reachedEnd;
            long finalInjectedBytes = injectedMonoBytes;
            mainHandler.post(() -> {
                if (generation == injectionGeneration) onInjectionPlaybackFinished(
                        finalReachedEnd, finalInjectedBytes, PcmFormat.fromFile(pcmFile), finalError);
            });
        }
    }

    private static int duplicateMonoToStereo(byte[] mono, int monoBytes, byte[] stereo) {
        int outputIndex = 0;
        for (int inputIndex = 0; inputIndex + 1 < monoBytes; inputIndex += 2) {
            byte low = mono[inputIndex];
            byte high = mono[inputIndex + 1];
            stereo[outputIndex++] = low;
            stereo[outputIndex++] = high;
            stereo[outputIndex++] = low;
            stereo[outputIndex++] = high;
        }
        return outputIndex;
    }

    private void onInjectionPlaybackFinished(
            boolean reachedEnd,
            long injectedMonoBytes,
            PcmFormat format,
            @Nullable String error) {
        if (!injectionSessionActive) {
            return;
        }
        if (error != null) {
            Log.e(TAG, "Telephony Tx injection failed: " + error);
            finishUplinkInjection("上行注入失败：" + error);
        } else if (reachedEnd) {
            Log.i(TAG, "Telephony Tx PCM injection completed: bytes=" + injectedMonoBytes);
            finishUplinkInjection(String.format(Locale.US,
                    "PCM 注入完成：%.1f 秒", format.seconds(injectedMonoBytes)));
        } else {
            finishUplinkInjection("上行注入已停止");
        }
    }

    private void stopUplinkInjection(String reason) {
        if (!injectionSessionActive) {
            return;
        }
        ++injectionGeneration; // Invalidate completion callbacks from the cancelled session.
        Log.i(TAG, "Stopping Telephony Tx injection: " + reason);
        injectionPlaybackRunning.set(false);

        AudioTrack track;
        synchronized (injectionLock) {
            track = injectionAudioTrack;
        }
        if (track != null) {
            try {
                if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause();
                    track.flush();
                    track.stop();
                }
            } catch (IllegalStateException ignored) {
                // 注入线程负责 release。
            }
        }
        finishUplinkInjection(reason);
    }

    private void finishUplinkInjection(String status) {
        synchronized (injectionLock) {
            injectionSessionActive = false;
            uplinkInjectionActiveForUi = false;
        }
        restoreMuteStateAfterInjection();
        updateInjectionStatus(status + "；真实麦克风状态已恢复");
    }

    private void abortUplinkInjectionStart(AudioTrack track, String error) {
        synchronized (injectionLock) {
            injectionSessionActive = false;
            uplinkInjectionActiveForUi = false;
            if (injectionAudioTrack == track) {
                injectionAudioTrack = null;
            }
        }
        track.release();
        restoreMuteStateAfterInjection();
        updateInjectionStatus(error);
    }

    private void restoreMuteStateAfterInjection() {
        if (vendorUplinkMute) {
            try { platform.restoreOriginal(); }
            catch (RuntimeException e) { Log.e(TAG, "恢复上行原声失败", e); }
        } else {
            CallStateInCallService.requestMuted(muteStateBeforeInjection);
        }
    }

    private void releaseInjectionAudioTrack(AudioTrack track) {
        synchronized (injectionLock) {
            try {
                if (track.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) {
                    track.stop();
                }
            } catch (IllegalStateException ignored) {
                // release 仍需执行。
            }
            track.release();
            if (injectionAudioTrack == track) {
                injectionAudioTrack = null;
            }
        }
    }

    private void updateInjectionStatus(String status) {
        uplinkInjectionStatus = status;
        broadcastStatus();
        if (monitoring && notificationManager != null) {
            notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(notificationStatusText()));
        }
    }

    private void startDownlinkReplacement(File pcmFile) {
        if (!platform.supportsVoiceGains
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            updateDownlinkReplacementStatus("下行替换需要 Android 14 或更高版本");
            return;
        }
        if (isDownlinkReplacementActive()) {
            updateDownlinkReplacementStatus("下行替换已经启动；请先停止当前会话");
            return;
        }
        if (injectionSessionActive) {
            updateDownlinkReplacementStatus("无法开始下行替换：请先停止上行注入");
            return;
        }
        if (foregroundCallState != Call.STATE_ACTIVE
                || CallStateInCallService.getCurrentCallState() != Call.STATE_ACTIVE) {
            updateDownlinkReplacementStatus("无法开始下行替换：电话尚未接通");
            return;
        }
        if (checkSelfPermission(CALL_AUDIO_INTERCEPTION_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            updateDownlinkReplacementStatus(
                    "无法开始下行替换：CALL_AUDIO_INTERCEPTION 未授予");
            return;
        }
        if (checkSelfPermission(Manifest.permission.MODIFY_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            updateDownlinkReplacementStatus("无法开始下行替换：MODIFY_PHONE_STATE 未授予");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            updateDownlinkReplacementStatus("无法开始下行替换：RECORD_AUDIO 未授予");
            return;
        }
        if (!isValidInjectionPcm(pcmFile)) {
            updateDownlinkReplacementStatus(
                    "无法开始下行替换：所选 PCM 无效或不在 App PCM 目录");
            return;
        }

        if (captureRunning.get() || captureRunningForUi) {
            pendingDownlinkReplacementFile = pcmFile;
            updateDownlinkReplacementStatus("正在停止普通双路采集并切换到 Call Redirect……");
            stopCapture("准备启动下行替换");
            return;
        }
        pendingDownlinkReplacementFile = null;
        if (platform.supportsVoiceGains) {
            startVendorDownlinkReplacement(pcmFile);
            return;
        }
        PcmFormat pcmFormat = PcmFormat.fromFile(pcmFile);

        AudioRecord originalDownlinkRecord = null;
        AudioRecord microphoneRecord = null;
        AudioTrack callUplinkTrack = null;
        AudioTrack replacementTrack = null;
        boolean redirectModeRequested = false;

        try {
            if (!isPstnCallAudioInterceptable()) {
                throw new UnsupportedOperationException(
                        "设备未同时声明 Telephony Rx/Tx，PSTN 通话不可重定向");
            }

            AudioDeviceInfo communicationDevice = audioManager.getCommunicationDevice();
            AudioFormat downlinkFormat = new AudioFormat.Builder()
                    .setSampleRate(pcmFormat.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(ENCODING)
                    .build();
            AudioFormat uplinkFormat = new AudioFormat.Builder()
                    .setSampleRate(pcmFormat.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(ENCODING)
                    .build();

            int microphoneMinBuffer = AudioRecord.getMinBufferSize(
                    pcmFormat.sampleRate, AudioFormat.CHANNEL_IN_MONO, ENCODING);
            int replacementMinBuffer = AudioTrack.getMinBufferSize(
                    pcmFormat.sampleRate, AudioFormat.CHANNEL_OUT_MONO, ENCODING);
            if (microphoneMinBuffer <= 0 || replacementMinBuffer <= 0) {
                throw new IllegalStateException(
                        "本地音频缓冲区不可用：record=" + microphoneMinBuffer
                                + ", track=" + replacementMinBuffer);
            }

            audioManager.setMode(AudioManager.MODE_CALL_REDIRECT);
            redirectModeRequested = true;
            if (audioManager.getMode() != AudioManager.MODE_CALL_REDIRECT) {
                throw new IllegalStateException(
                        "Framework 未进入 MODE_CALL_REDIRECT，actual="
                                + audioManager.getMode());
            }

            originalDownlinkRecord = getCallDownlinkExtractionAudioRecord(downlinkFormat);
            callUplinkTrack = getCallUplinkInjectionAudioTrack(uplinkFormat);
            microphoneRecord = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(downlinkFormat)
                    .setBufferSizeInBytes(Math.max(
                            microphoneMinBuffer * 2, READ_CHUNK_BYTES * 4))
                    .build();
            replacementTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            // VOICE_COMMUNICATION 会在该设备上进入专用 VOIP_RX HAL。
                            // MODE_CALL_REDIRECT 下这条路径没有落到物理听筒/扬声器，
                            // 因此替换音频使用普通媒体输出，再显式偏好当前通话设备。
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(uplinkFormat)
                    .setBufferSizeInBytes(Math.max(
                            replacementMinBuffer * 2, READ_CHUNK_BYTES * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            requireInitialized(originalDownlinkRecord, "Call Downlink Extraction AudioRecord");
            requireInitialized(microphoneRecord, "本地麦克风 AudioRecord");
            requireInitialized(callUplinkTrack, "Call Uplink Injection AudioTrack");
            requireInitialized(replacementTrack, "下行替换 AudioTrack");
            if (communicationDevice != null
                    && !replacementTrack.setPreferredDevice(communicationDevice)) {
                Log.w(TAG, "Replacement AudioTrack rejected preferred communication device id="
                        + communicationDevice.getId());
            }

            originalDownlinkRecord.startRecording();
            microphoneRecord.startRecording();
            callUplinkTrack.play();
            replacementTrack.play();
            requireRecording(originalDownlinkRecord, "Call Downlink Extraction AudioRecord");
            requireRecording(microphoneRecord, "本地麦克风 AudioRecord");
            requirePlaying(callUplinkTrack, "Call Uplink Injection AudioTrack");
            requirePlaying(replacementTrack, "下行替换 AudioTrack");

            DownlinkReplacementSession session = new DownlinkReplacementSession(
                    pcmFile,
                    originalDownlinkRecord,
                    microphoneRecord,
                    callUplinkTrack,
                    replacementTrack);
            synchronized (downlinkReplacementLock) {
                downlinkReplacementSession = session;
                downlinkReplacementActiveForUi = true;
            }

            updateStatus("下行替换进行中，普通双路采集已暂停");
            updateDownlinkReplacementStatus(String.format(
                    Locale.US,
                    "已进入 MODE_CALL_REDIRECT\n正在播放：%s（%.1f 秒）\n"
                            + "真实下行已阻断；本机麦克风继续桥接到上行",
                    pcmFile.getName(),
                    pcmFormat.seconds(pcmFile.length())));
            Log.i(TAG, "Official PSTN downlink replacement started: file="
                    + pcmFile.getAbsolutePath()
                    + ", communicationDevice="
                    + describeAudioDevice(communicationDevice)
                    + ", playbackUsage=USAGE_MEDIA");

            new Thread(
                    () -> drainOriginalDownlink(session),
                    "CallRedirectDownlinkDrain").start();
            new Thread(
                    () -> bridgeMicrophoneToCallUplink(session),
                    "CallRedirectMicrophoneBridge").start();
            new Thread(
                    () -> playDownlinkReplacementPcm(session),
                    "CallRedirectReplacementPlayback").start();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            synchronized (downlinkReplacementLock) {
                if (downlinkReplacementSession != null
                        && downlinkReplacementSession.originalDownlinkRecord
                        == originalDownlinkRecord) {
                    downlinkReplacementSession.running.set(false);
                    downlinkReplacementSession.replacementTrack = null;
                    downlinkReplacementSession = null;
                    downlinkReplacementActiveForUi = false;
                }
            }
            stopAndReleaseAudioTrack(replacementTrack);
            stopAndReleaseAudioTrack(callUplinkTrack);
            stopAndReleaseAudioRecord(microphoneRecord);
            stopAndReleaseAudioRecord(originalDownlinkRecord);
            if (redirectModeRequested) {
                restoreAudioModeAfterCallRedirect();
            }
            updateDownlinkReplacementStatus(
                    "启动官方下行替换失败：" + rootCauseMessage(exception));
            if (!destroying
                    && monitoring
                    && foregroundCallState == Call.STATE_ACTIVE
                    && !captureRunning.get()) {
                mainHandler.postDelayed(delayedCaptureStart, CALL_AUDIO_SETTLE_DELAY_MS);
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void startVendorDownlinkReplacement(File pcmFile) {
        AudioTrack track = null;
        try {
            PcmFormat format = PcmFormat.fromFile(pcmFile);
            int min = AudioTrack.getMinBufferSize(format.sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, ENCODING);
            if (min <= 0) throw new IllegalStateException("下行播放缓冲区不可用");
            AudioDeviceInfo device = audioManager.getCommunicationDevice();
            if (device == null) throw new IllegalStateException("没有可用的通话输出设备");
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(format.sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(ENCODING).build())
                    .setBufferSizeInBytes(Math.max(min * 2, READ_CHUNK_BYTES * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
            requireInitialized(track, "厂商下行替换 AudioTrack");
            if (!track.setPreferredDevice(device)) throw new IllegalStateException("通话输出路由被拒绝");
            platform.muteOriginal(false, true);
            track.play();
            Log.i(TAG, "Vendor downlink replacement route: "
                    + describeAudioDevice(track.getRoutedDevice()));
            DownlinkReplacementSession session = new DownlinkReplacementSession(pcmFile,
                    null, null, null, track);
            synchronized (downlinkReplacementLock) {
                downlinkReplacementSession = session;
                downlinkReplacementActiveForUi = true;
            }
            updateDownlinkReplacementStatus("厂商下行替换已启动，麦克风保持原通话路由");
            new Thread(() -> playDownlinkReplacementPcm(session), "VendorDownlinkPlayback").start();
        } catch (RuntimeException e) {
            stopAndReleaseAudioTrack(track);
            try { platform.restoreOriginal(); } catch (RuntimeException restoreError) {
                Log.e(TAG, "恢复下行原声失败", restoreError);
            }
            updateDownlinkReplacementStatus("下行替换失败：" + rootCauseMessage(e));
            mainHandler.postDelayed(delayedCaptureStart, CALL_AUDIO_SETTLE_DELAY_MS);
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    public static void onTelecomAudioRouteChanged() {
        CallCaptureService service = instance;
        if (service == null) return;
        service.mainHandler.post(() -> {
            try {
                if (service.vendorUplinkMute && service.injectionSessionActive) {
                    service.platform.muteOriginal(true, false);
                }
                DownlinkReplacementSession session = service.downlinkReplacementSession;
                if (session != null && session.running.get()) {
                    if (session.vendorGains) service.platform.muteOriginal(false, true);
                    AudioTrack track = session.replacementTrack;
                    AudioDeviceInfo device = service.audioManager.getCommunicationDevice();
                    if (track != null && device != null && !track.setPreferredDevice(device)) {
                        throw new IllegalStateException("下行替换无法切换通话路由");
                    }
                }
            } catch (RuntimeException e) {
                service.stopUplinkInjection("路由切换失败");
                service.stopDownlinkReplacement("路由切换失败：" + e.getMessage());
            }
        });
    }

    private boolean isPstnCallAudioInterceptable() throws ReflectiveOperationException {
        Method method = AudioManager.class.getMethod("isPstnCallAudioInterceptable");
        return (Boolean) invokeAudioManagerMethod(method);
    }

    private AudioRecord getCallDownlinkExtractionAudioRecord(AudioFormat format)
            throws ReflectiveOperationException {
        Method method = AudioManager.class.getMethod(
                "getCallDownlinkExtractionAudioRecord", AudioFormat.class);
        return (AudioRecord) invokeAudioManagerMethod(method, format);
    }

    private AudioTrack getCallUplinkInjectionAudioTrack(AudioFormat format)
            throws ReflectiveOperationException {
        Method method = AudioManager.class.getMethod(
                "getCallUplinkInjectionAudioTrack", AudioFormat.class);
        return (AudioTrack) invokeAudioManagerMethod(method, format);
    }

    private Object invokeAudioManagerMethod(Method method, Object... arguments)
            throws ReflectiveOperationException {
        try {
            return method.invoke(audioManager, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw exception;
        }
    }

    private static void requireInitialized(AudioRecord record, String name) {
        if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException(name + " 未初始化");
        }
    }

    private static void requireInitialized(AudioTrack track, String name) {
        if (track == null || track.getState() != AudioTrack.STATE_INITIALIZED) {
            throw new IllegalStateException(name + " 未初始化");
        }
    }

    private static void requireRecording(AudioRecord record, String name) {
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            throw new IllegalStateException(name + " 没有进入 RECORDING 状态");
        }
    }

    private static void requirePlaying(AudioTrack track, String name) {
        if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            throw new IllegalStateException(name + " 没有进入 PLAYING 状态");
        }
    }

    private void drainOriginalDownlink(DownlinkReplacementSession session) {
        byte[] buffer = new byte[READ_CHUNK_BYTES];
        long totalBytes = 0;
        String error = null;
        try {
            while (session.running.get()) {
                int bytesRead = session.originalDownlinkRecord.read(
                        buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (bytesRead > 0) {
                    totalBytes += bytesRead;
                } else if (bytesRead < 0) {
                    error = "真实下行读取失败：" + readErrorName(bytesRead);
                    break;
                }
            }
        } catch (RuntimeException exception) {
            error = "真实下行提取异常：" + rootCauseMessage(exception);
        }
        Log.i(TAG, "Call Redirect original downlink drain ended: bytes=" + totalBytes);
        if (error != null && session.running.get()) {
            reportDownlinkReplacementFailure(session, error);
        }
    }

    private void bridgeMicrophoneToCallUplink(DownlinkReplacementSession session) {
        byte[] buffer = new byte[READ_CHUNK_BYTES];
        long totalBytes = 0;
        String error = null;
        try {
            while (session.running.get()) {
                int bytesRead = session.microphoneRecord.read(
                        buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (bytesRead < 0) {
                    error = "麦克风桥接读取失败：" + readErrorName(bytesRead);
                    break;
                }
                int offset = 0;
                while (offset < bytesRead && session.running.get()) {
                    int bytesWritten = session.callUplinkTrack.write(
                            buffer,
                            offset,
                            bytesRead - offset,
                            AudioTrack.WRITE_BLOCKING);
                    if (bytesWritten <= 0) {
                        error = "通话上行桥接写入失败：" + bytesWritten;
                        break;
                    }
                    offset += bytesWritten;
                }
                totalBytes += offset;
                if (error != null) {
                    break;
                }
            }
        } catch (RuntimeException exception) {
            error = "通话上行桥接异常：" + rootCauseMessage(exception);
        }
        Log.i(TAG, "Call Redirect microphone bridge ended: bytes=" + totalBytes);
        if (error != null && session.running.get()) {
            reportDownlinkReplacementFailure(session, error);
        }
    }

    private void playDownlinkReplacementPcm(DownlinkReplacementSession session) {
        byte[] buffer = new byte[READ_CHUNK_BYTES];
        long totalBytes = 0;
        int maximumPeak = 0;
        boolean routeReported = false;
        boolean reachedEnd = false;
        AudioTrack playbackTrack = session.replacementTrack;
        String error = null;
        try (BufferedInputStream input = new BufferedInputStream(
                new FileInputStream(session.pcmFile), READ_CHUNK_BYTES * 4)) {
            while (session.running.get()) {
                int bytesRead = PcmIo.readFrames(input, buffer, BYTES_PER_SAMPLE);
                if (bytesRead < 0) {
                    reachedEnd = true;
                    break;
                }
                maximumPeak = Math.max(maximumPeak, calculatePcm16Peak(buffer, bytesRead));
                int offset = 0;
                while (offset < bytesRead && session.running.get()) {
                    int bytesWritten = playbackTrack.write(
                            buffer,
                            offset,
                            bytesRead - offset,
                            AudioTrack.WRITE_BLOCKING);
                    if (bytesWritten <= 0) {
                        error = "替换 PCM 写入失败：" + bytesWritten;
                        break;
                    }
                    offset += bytesWritten;
                }
                totalBytes += offset;
                if (error != null) {
                    break;
                }
                if (!routeReported
                        && totalBytes >= session.format.sampleRate / 5L * BYTES_PER_SAMPLE) {
                    routeReported = true;
                    AudioDeviceInfo routedDevice = playbackTrack.getRoutedDevice();
                    String routeDescription = describeAudioDevice(routedDevice);
                    long reportedBytes = totalBytes;
                    int reportedPeak = maximumPeak;
                    Log.i(TAG, "Downlink replacement physical route: "
                            + routeDescription
                            + ", bytesWritten=" + reportedBytes
                            + ", pcmPeak=" + reportedPeak);
                    mainHandler.post(() -> reportDownlinkReplacementRoute(
                            session, routeDescription, reportedBytes, reportedPeak));
                }
            }
            if (reachedEnd && error == null) {
                PcmPlayback.drain(playbackTrack, totalBytes, BYTES_PER_SAMPLE, session.running::get);
            }
        } catch (IOException | RuntimeException exception) {
            error = "替换 PCM 播放异常：" + rootCauseMessage(exception);
        }

        AudioTrack trackToRelease = null;
        synchronized (downlinkReplacementLock) {
            if (downlinkReplacementSession == session && session.replacementTrack != null) {
                trackToRelease = session.replacementTrack;
                session.replacementTrack = null;
            }
        }
        stopAndReleaseAudioTrack(trackToRelease);

        Log.i(TAG, "Downlink replacement playback ended: bytes=" + totalBytes
                + ", maximumPcmPeak=" + maximumPeak
                + ", error=" + (error == null ? "none" : error));

        if (error != null && session.running.get()) {
            reportDownlinkReplacementFailure(session, error);
        } else if (session.running.get()) {
            long finalTotalBytes = totalBytes;
            mainHandler.post(() -> {
                synchronized (downlinkReplacementLock) {
                    if (downlinkReplacementSession != session || !session.running.get()) {
                        return;
                    }
                }
                stopDownlinkReplacement(String.format(Locale.US,
                        "替换 PCM 播放完成：%.1f 秒", session.format.seconds(finalTotalBytes)));
            });
        }
    }

    private void reportDownlinkReplacementRoute(
            DownlinkReplacementSession session,
            String routeDescription,
            long bytesWritten,
            int pcmPeak) {
        synchronized (downlinkReplacementLock) {
            if (downlinkReplacementSession != session || !session.running.get()) {
                return;
            }
        }
        String modeText = session.vendorGains
                ? "展锐 voice_dl_gain 原声静音链路"
                : "MODE_CALL_REDIRECT 下行替换链路";
        String bridgeText = session.vendorGains
                ? "真实下行已静音；PCM 正在通话输出路由播放"
                : "真实下行已阻断；本机麦克风继续桥接到上行";
        updateDownlinkReplacementStatus(String.format(
                Locale.US,
                "%s\n正在播放：%s（%.1f 秒）\n"
                        + "实际播放路由：%s\n已写入 %d 字节，PCM 峰值 %d\n%s",
                modeText,
                session.pcmFile.getName(),
                session.format.seconds(session.pcmFile.length()),
                routeDescription,
                bytesWritten,
                pcmPeak,
                bridgeText));
    }

    private static String describeAudioDevice(@Nullable AudioDeviceInfo device) {
        if (device == null) {
            return "系统默认设备";
        }
        String typeName;
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                typeName = "听筒";
                break;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                typeName = "扬声器";
                break;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                typeName = "有线耳麦";
                break;
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                typeName = "有线耳机";
                break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                typeName = "蓝牙通话设备";
                break;
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                typeName = "USB 耳麦";
                break;
            default:
                typeName = "音频设备";
                break;
        }
        return typeName + "(id=" + device.getId() + ", type=" + device.getType() + ")";
    }

    private void reportDownlinkReplacementFailure(
            DownlinkReplacementSession session, String error) {
        mainHandler.post(() -> {
            synchronized (downlinkReplacementLock) {
                if (downlinkReplacementSession != session || !session.running.get()) {
                    return;
                }
            }
            Log.e(TAG, "Official PSTN downlink replacement failed: " + error);
            stopDownlinkReplacement("下行替换失败：" + error);
        });
    }

    private void stopDownlinkReplacement(String reason) {
        boolean hadPendingStart = pendingDownlinkReplacementFile != null;
        pendingDownlinkReplacementFile = null;

        DownlinkReplacementSession session;
        AudioTrack replacementTrack;
        synchronized (downlinkReplacementLock) {
            session = downlinkReplacementSession;
            if (session == null) {
                if (hadPendingStart) {
                    downlinkReplacementActiveForUi = false;
                    updateDownlinkReplacementStatus(reason + "；待启动任务已取消");
                }
                return;
            }
            session.running.set(false);
            replacementTrack = session.replacementTrack;
            session.replacementTrack = null;
            downlinkReplacementSession = null;
            downlinkReplacementActiveForUi = false;
        }

        Log.i(TAG, "Stopping official PSTN downlink replacement: " + reason);
        stopAndReleaseAudioTrack(replacementTrack);
        stopAndReleaseAudioTrack(session.callUplinkTrack);
        stopAndReleaseAudioRecord(session.microphoneRecord);
        stopAndReleaseAudioRecord(session.originalDownlinkRecord);
        if (session.vendorGains) {
            try { platform.restoreOriginal(); } catch (RuntimeException e) {
                updateDownlinkReplacementStatus("恢复原声失败：" + e.getMessage());
                return;
            }
        } else {
            restoreAudioModeAfterCallRedirect();
        }
        updateDownlinkReplacementStatus(reason + "；原始通话上下行路由已恢复");

        if (!destroying
                && monitoring
                && foregroundCallState == Call.STATE_ACTIVE
                && !captureRunning.get()) {
            updateStatus("下行替换已停止，等待通话音频路由恢复");
            mainHandler.postDelayed(delayedCaptureStart, CALL_AUDIO_SETTLE_DELAY_MS);
        }
    }

    private void restoreAudioModeAfterCallRedirect() {
        if (audioManager == null) {
            return;
        }
        try {
            // MODE_NORMAL removes this process from AudioService's mode-owner stack. Telecom's
            // still-active MODE_IN_CALL owner then becomes effective and recreates the RX/TX
            // telephony patches.
            audioManager.setMode(AudioManager.MODE_NORMAL);
            Log.i(TAG, "Released MODE_CALL_REDIRECT; actual mode=" + audioManager.getMode());
        } catch (RuntimeException exception) {
            Log.e(TAG, "Failed to release MODE_CALL_REDIRECT", exception);
        }
    }

    private static void stopAndReleaseAudioRecord(@Nullable AudioRecord record) {
        if (record == null) {
            return;
        }
        try {
            if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop();
            }
        } catch (IllegalStateException ignored) {
            // release remains required.
        }
        record.release();
    }

    private static void stopAndReleaseAudioTrack(@Nullable AudioTrack track) {
        if (track == null) {
            return;
        }
        try {
            if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause();
                track.flush();
                track.stop();
            }
        } catch (IllegalStateException ignored) {
            // release remains required.
        }
        track.release();
    }

    private static String rootCauseMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : "：" + message);
    }

    private void updateDownlinkReplacementStatus(String status) {
        downlinkReplacementStatus = status;
        broadcastStatus();
        if (monitoring && notificationManager != null) {
            notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(notificationStatusText()));
        }
    }

    private String notificationStatusText() {
        String text = currentStatus;
        if (injectionSessionActive) {
            text += "\n" + uplinkInjectionStatus;
        }
        if (isDownlinkReplacementActive()) {
            text += "\n" + downlinkReplacementStatus;
        }
        return text;
    }

    private static int calculatePcm16Peak(byte[] pcm, int size) {
        int peak = 0;
        for (int index = 0; index + 1 < size; index += 2) {
            int sample = (short) ((pcm[index] & 0xff) | ((pcm[index + 1] & 0xff) << 8));
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }

    private static String readErrorName(int error) {
        switch (error) {
            case AudioRecord.ERROR_INVALID_OPERATION:
                return "ERROR_INVALID_OPERATION";
            case AudioRecord.ERROR_BAD_VALUE:
                return "ERROR_BAD_VALUE";
            case AudioRecord.ERROR_DEAD_OBJECT:
                return "ERROR_DEAD_OBJECT";
            case AudioRecord.ERROR:
                return "ERROR";
            default:
                return String.valueOf(error);
        }
    }

    private void updateStatus(String status) {
        currentStatus = status;
        broadcastStatus();
        if (monitoring && notificationManager != null) {
            notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(notificationStatusText()));
        }
    }

    private void broadcastStatus() {
        sendBroadcast(new Intent(ACTION_STATUS_CHANGED).setPackage(getPackageName()));
    }

    private Notification buildNotification(String text) {
        Intent activityIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("通话采集与双向注入")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void createNotificationChannel() {
        if (notificationManager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "通话自动采集",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("监听通话、分别采集上下行 PCM，并支持上下行注入");
        notificationManager.createNotificationChannel(channel);
    }

    private static String telecomCallStateName(int state) {
        switch (state) {
            case Call.STATE_NEW:
                return "NEW";
            case Call.STATE_ACTIVE:
                return "ACTIVE";
            case Call.STATE_HOLDING:
                return "HOLDING";
            case Call.STATE_DIALING:
                return "DIALING";
            case Call.STATE_RINGING:
                return "RINGING";
            case Call.STATE_DISCONNECTED:
                return "DISCONNECTED";
            case Call.STATE_DISCONNECTING:
                return "DISCONNECTING";
            case Call.STATE_SELECT_PHONE_ACCOUNT:
                return "SELECT_PHONE_ACCOUNT";
            case Call.STATE_CONNECTING:
                return "CONNECTING";
            case Call.STATE_PULLING_CALL:
                return "PULLING_CALL";
            case Call.STATE_AUDIO_PROCESSING:
                return "AUDIO_PROCESSING";
            case Call.STATE_SIMULATED_RINGING:
                return "SIMULATED_RINGING";
            default:
                return String.valueOf(state);
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Automatic call monitoring stopped");
        destroying = true;
        stopUplinkInjection("自动监听服务已停止");
        stopDownlinkReplacement("自动监听服务已停止");
        monitoring = false;
        mainHandler.removeCallbacks(delayedCaptureStart);
        stopCapture("自动监听服务已停止");
        currentStatus = "自动监听服务未启动";
        broadcastStatus();
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
