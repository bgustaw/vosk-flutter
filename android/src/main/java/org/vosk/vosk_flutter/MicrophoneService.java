package org.vosk.vosk_flutter;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;

import java.io.IOException;

public class MicrophoneService extends Service implements ServiceCallbacks {

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // Binder given to clients
    private final IBinder binder = new LocalBinder();
    // Registered callbacks

    public class LocalBinder extends Binder {
        MicrophoneService getService() {
            // Return this instance of MyService so clients can call public methods
            return MicrophoneService.this;
        }
    }

    public void setCallbacks(ServiceCallbacks callbacks) {
        serviceCallbacks = callbacks;
    }
    private ServiceCallbacks serviceCallbacks;

    private static final float BUFFER_SIZE_SECONDS = 0.2f;

    private Recognizer recognizer;

    private int sampleRate;

    private int bufferSize;
    private AudioRecord recorder;

    private RecognizerThread recognizerThread;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());


    @SuppressLint("MissingPermission")
    public void setParams(Recognizer recognizer, float sampleRate) throws IOException {
        this.recognizer = recognizer;
        this.sampleRate = (int) sampleRate;

        bufferSize = Math.round(this.sampleRate * BUFFER_SIZE_SECONDS);

        recorder = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                this.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
        );

        if (recorder.getState() == AudioRecord.STATE_UNINITIALIZED) {
            recorder.release();
            throw new IOException("Failed to initialize recorder. Microphone might be already in use.");
        }
    }

    /// INTERFACE FUNCTIONS
    public boolean startListening(RecognitionListener listener) {
        if (null != recognizerThread)
            return false;

        recognizerThread = new RecognizerThread(listener);
        recognizerThread.start();
        return true;
    }

    public boolean startListening(RecognitionListener listener, int timeout) {
        if (null != recognizerThread)
            return false;

        recognizerThread = new RecognizerThread(listener, timeout);
        recognizerThread.start();
        return true;
    }

    public boolean stopRecognizerThread() {
        if (null == recognizerThread)
            return false;

        try {
            recognizerThread.interrupt();
            recognizerThread.join();
        } catch (InterruptedException e) {
            // Restore the interrupted status.
            Thread.currentThread().interrupt();
        }

        recognizerThread = null;
        return true;
    }

    public boolean cancel() {
        if (recognizerThread != null) {
            recognizerThread.setPause(true);
        }
        return stopRecognizerThread();
    }

    public void shutdown() {
        recorder.release();
    }

    public void setPause(boolean paused) {
        if (recognizerThread != null) {
            recognizerThread.setPause(paused);
        }
    }

    public void reset() {
        if (recognizerThread != null) {
            recognizerThread.reset();
        }
    }
    ///

    @Override
    public void onDestroy() {
        cancel();
        shutdown();
        super.onDestroy();
    }

    @Override
    public void onCreate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForegroundService();
        }
        super.onCreate();
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    void startForegroundService() {
        String channelId = "channelgeo";
        NotificationChannel channel;
        channel = new NotificationChannel(
                channelId,
                "GeoChannelName",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        NotificationManager notificationManager;
        notificationManager = getApplicationContext().getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setContentTitle(null)
                .setContentText(null)
                .build();
        ServiceCompat.startForeground(
                this, // service
                100,  // id Cannot be 0
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        );
    }

    private final class RecognizerThread extends Thread {

        private int remainingSamples;
        private final int timeoutSamples;
        private final static int NO_TIMEOUT = -1;
        private volatile boolean paused = false;
        private volatile boolean reset = false;

        RecognitionListener listener;

        public RecognizerThread(RecognitionListener listener, int timeout) {
            this.listener = listener;
            if (timeout != NO_TIMEOUT)
                this.timeoutSamples = timeout * sampleRate / 1000;
            else
                this.timeoutSamples = NO_TIMEOUT;
            this.remainingSamples = this.timeoutSamples;
        }

        public RecognizerThread(RecognitionListener listener) {
            this(listener, NO_TIMEOUT);
        }

        /**
         * When we are paused, don't process audio by the recognizer and don't emit
         * any listener results
         *
         * @param paused the status of pause
         */
        public void setPause(boolean paused) {
            this.paused = paused;
        }

        /**
         * Set reset state to signal reset of the recognizer and start over
         */
        public void reset() {
            this.reset = true;
        }

        @Override
        public void run() {

            recorder.startRecording();
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_STOPPED) {
                recorder.stop();
                IOException ioe = new IOException(
                        "Failed to start recording. Microphone might be already in use.");
                mainHandler.post(() -> listener.onError(ioe));
            }

            short[] buffer = new short[bufferSize];

            while (!interrupted() && ((timeoutSamples == NO_TIMEOUT) || (remainingSamples > 0))) {
                int nread = recorder.read(buffer, 0, buffer.length);

                if (paused) {
                    continue;
                }

                if (reset) {
                    recognizer.reset();
                    reset = false;
                }

                if (nread < 0)
                    throw new RuntimeException("error reading audio buffer");

                if (recognizer.acceptWaveForm(buffer, nread)) {
                    final String result = recognizer.getResult();
                    mainHandler.post(() -> listener.onResult(result));
                } else {
                    final String partialResult = recognizer.getPartialResult();
                    mainHandler.post(() -> listener.onPartialResult(partialResult));
                }

                if (timeoutSamples != NO_TIMEOUT) {
                    remainingSamples = remainingSamples - nread;
                }
            }

            recorder.stop();

            if (!paused) {
                // If we met timeout signal that speech ended
                if (timeoutSamples != NO_TIMEOUT && remainingSamples <= 0) {
                    mainHandler.post(() -> listener.onTimeout());
                } else {
                    final String finalResult = recognizer.getFinalResult();
                    mainHandler.post(() -> listener.onFinalResult(finalResult));
                }
            }

        }
    }

}
