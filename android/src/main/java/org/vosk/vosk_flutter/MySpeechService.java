package org.vosk.vosk_flutter;


// Copyright 2019 Alpha Cephei Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//package org.vosk.android;

import org.vosk.Recognizer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder.AudioSource;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.annotation.SuppressLint;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import org.vosk.android.RecognitionListener;

import java.io.IOException;


/**
 * Service that records audio in a thread, passes it to a recognizer and emits
 * recognition results. Recognition events are passed to a client using
 * {@link RecognitionListener}
 */
public class MySpeechService implements ServiceCallbacks {

    private final Recognizer recognizer;

    private final int sampleRate;

    private final Context applicationContext;

    private boolean bound = false;

    private MySpeechService self;
    private MicrophoneService service;
    Intent serviceIntent;

    /**
     * Creates speech service. Service holds the AudioRecord object, so you
     * need to call {@link #shutdown()} in order to properly finalize it.
     *
     * @throws IOException thrown if audio recorder can not be created for some reason.
     */

    @SuppressLint("MissingPermission")
    public MySpeechService(Recognizer recognizer, float sampleRate, Context applicationContext) throws IOException {
        this.recognizer = recognizer;
        this.sampleRate = (int) sampleRate;

        this.applicationContext = applicationContext;
        this.self = this;

        serviceIntent = new Intent(applicationContext, MicrophoneService.class);
        applicationContext.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }


    /** Callbacks for service binding, passed to bindService() */
    private final ServiceConnection serviceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className, IBinder iBinder) {
            // cast the IBinder and get MyService instance
            Log.d("ServiceConnection", "onServiceConnected");
            MicrophoneService.LocalBinder binder = (MicrophoneService.LocalBinder) iBinder;
            service = binder.getService();
            bound = true;
            service.setCallbacks(self); // register
            try {
                service.setParams(recognizer, sampleRate);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            bound = false;
        }
    };

    /**
     * Starts recognition. Does nothing if recognition is active.
     *
     * @return true if recognition was actually started
     */
    public boolean startListening(RecognitionListener listener) {
        return service.startListening(listener);
    }

    /**
     * Starts recognition. After specified timeout listening stops and the
     * endOfSpeech signals about that. Does nothing if recognition is active.
     * <p>
     * timeout - timeout in milliseconds to listen.
     *
     * @return true if recognition was actually started
     */
    public boolean startListening(RecognitionListener listener, int timeout) {
        return service.startListening(listener, timeout);
    }

    private boolean stopRecognizerThread() {
        return service.stopRecognizerThread();
    }

    /**
     * Stops recognition. Listener should receive final result if there is
     * any. Does nothing if recognition is not active.
     *
     * @return true if recognition was actually stopped
     */
    public boolean stop() {
        return stopRecognizerThread();
    }

    /**
     * Cancel recognition. Do not post any new events, simply cancel processing.
     * Does nothing if recognition is not active.
     *
     * @return true if recognition was actually stopped
     */
    public boolean cancel() {
        return service.cancel();
    }

    /**
     * Shutdown the recognizer and release the recorder
     */
    public void shutdown() {
        service.shutdown();
    }

    public void setPause(boolean paused) {
        service.setPause(paused);
    }

    /**
     * Resets recognizer in a thread, starts recognition over again
     */
    public void reset() {
        service.reset();
    }


//    private final class RecognizerThread extends Thread {
//
//        private int remainingSamples;
//        private final int timeoutSamples;
//        private final static int NO_TIMEOUT = -1;
//        private volatile boolean paused = false;
//        private volatile boolean reset = false;
//
//        RecognitionListener listener;
//
//        public RecognizerThread(RecognitionListener listener, int timeout) {
//            this.listener = listener;
//            if (timeout != NO_TIMEOUT)
//                this.timeoutSamples = timeout * sampleRate / 1000;
//            else
//                this.timeoutSamples = NO_TIMEOUT;
//            this.remainingSamples = this.timeoutSamples;
//        }
//
//        public RecognizerThread(RecognitionListener listener) {
//            this(listener, NO_TIMEOUT);
//        }
//
//        /**
//         * When we are paused, don't process audio by the recognizer and don't emit
//         * any listener results
//         *
//         * @param paused the status of pause
//         */
//        public void setPause(boolean paused) {
//            this.paused = paused;
//        }
//
//        /**
//         * Set reset state to signal reset of the recognizer and start over
//         */
//        public void reset() {
//            this.reset = true;
//        }
//
//        @Override
//        public void run() {
//
//            recorder.startRecording();
//            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_STOPPED) {
//                recorder.stop();
//                IOException ioe = new IOException(
//                        "Failed to start recording. Microphone might be already in use.");
//                mainHandler.post(() -> listener.onError(ioe));
//            }
//
//            short[] buffer = new short[bufferSize];
//
//            while (!interrupted() && ((timeoutSamples == NO_TIMEOUT) || (remainingSamples > 0))) {
//                int nread = recorder.read(buffer, 0, buffer.length);
//
//                if (paused) {
//                    continue;
//                }
//
//                if (reset) {
//                    recognizer.reset();
//                    reset = false;
//                }
//
//                if (nread < 0)
//                    throw new RuntimeException("error reading audio buffer");
//
//                if (recognizer.acceptWaveForm(buffer, nread)) {
//                    final String result = recognizer.getResult();
//                    mainHandler.post(() -> listener.onResult(result));
//                } else {
//                    final String partialResult = recognizer.getPartialResult();
//                    mainHandler.post(() -> listener.onPartialResult(partialResult));
//                }
//
//                if (timeoutSamples != NO_TIMEOUT) {
//                    remainingSamples = remainingSamples - nread;
//                }
//            }
//
//            recorder.stop();
//
//            if (!paused) {
//                // If we met timeout signal that speech ended
//                if (timeoutSamples != NO_TIMEOUT && remainingSamples <= 0) {
//                    mainHandler.post(() -> listener.onTimeout());
//                } else {
//                    final String finalResult = recognizer.getFinalResult();
//                    mainHandler.post(() -> listener.onFinalResult(finalResult));
//                }
//            }
//
//        }
//    }
}