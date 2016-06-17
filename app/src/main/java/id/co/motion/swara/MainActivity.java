package id.co.motion.swara;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Environment;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.VideoView;

import com.intrications.systemuihelper.SystemUiHelper;

import org.openni.Device;
import org.openni.DeviceInfo;
import org.openni.OpenNI;
import org.openni.SensorType;
import org.openni.VideoFrameRef;
import org.openni.VideoStream;
import org.openni.android.OpenNIHelper;
import org.openni.android.OpenNIView;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    /**********************************************************************************************
     * Private Member
     **********************************************************************************************/

    private static final String TAG = "MainActivity";
    private static final String IDLE_VIDEO = "idle.mp4";
    private static final String PLAY_VIDEO = "play.mp4";
    private static final String WIN_VIDEO = "win.mp4";
    private static final String LOSE_VIDEO = "lose.mp4";
    private static final String NONE_VIDEO = "";

    private OpenNIHelper mOpenNIHelper;
    private boolean mIsOrbbecDeviceOpening;
    private Thread mMainLoopThread;
    private boolean mWorkerRun;
    private Device mDevice;
    private VideoStream mStream;
    private OpenNIView mFrameView;
    private VideoView mVideoView;
    private ProgressBar mProgressBar;
    private SpeechRecognizer mSpeechRecognizer;
    private OpenNIHelper.DeviceOpenListener mDeviceOpenListener;
    private RecognitionListener mRecognitionListener;
    private Intent mRecognizerIntent;
    private boolean mSpeechReady;
    private boolean mIsOrbbecStreamReady;
    private Context mContext;
    private boolean mPeopleReady;
    private String mVideoDir;
    private String mVideoFile;
    private int mCountOk;
    private int mCountNg;

    /*--------------------------------------------------------------------------------------------*/

    /**********************************************************************************************
     * Override Method
     **********************************************************************************************/

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);

        nonUiInit();
        uiInit();

        orbbecPrepare();
        videoPrepare();
        speechPrepare();
    }

    /*..............................................................................................
     Fullscreen helper (SystemUiHelper)
     http://stackoverflow.com/a/29577469/4218210
    ..............................................................................................*/

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        Log.d(TAG, "onWindowFocusChanged");
        super.onWindowFocusChanged(hasFocus);

        final int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

        SystemUiHelper uiHelper = new SystemUiHelper(this, SystemUiHelper.LEVEL_IMMERSIVE, flags);
        uiHelper.hide();
    }

    /*............................................................................................*/

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();

        if (mOpenNIHelper != null) {
            mOpenNIHelper.shutdown();
            mOpenNIHelper = null;
        }

        OpenNI.shutdown();

        if (mSpeechRecognizer != null) {
            mSpeechRecognizer.destroy();
            mSpeechRecognizer = null;
        }
    }

    @Override
    protected void onResume() {
        Log.d(TAG, "onResume");
        super.onResume();

        /*..........................................................................................
         mIsOrbbecDeviceOpening is a state variable so we didn't request repeatedly.
        ..........................................................................................*/

        if (mIsOrbbecDeviceOpening) {
            return;
        }

        resumeOrbbec();
        resumeSpeech();
        resumeWorker();

        /*........................................................................................*/

    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause");
        super.onPause();

        /*..........................................................................................
         mIsOrbbecDeviceOpening is a state variable so we didn't request device permission repeatedly.
        ..........................................................................................*/

        if (mIsOrbbecDeviceOpening) {
            return;
        }

        stopWorker();
        stopSpeech();
        stopOrbbec();

        /*........................................................................................*/

    }

    /*--------------------------------------------------------------------------------------------*/

    /**********************************************************************************************
     * Helper Method
     **********************************************************************************************/

    private void nonUiInit() {
        mIsOrbbecDeviceOpening = false;
        mOpenNIHelper = new OpenNIHelper(this);
        mIsOrbbecStreamReady = false;

        mSpeechReady = false;
        mSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        mContext = this;

        mWorkerRun = true;

        mPeopleReady = false;
        mVideoDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString();
        mVideoFile = IDLE_VIDEO;
    }

    private void uiInit() {
        setContentView(R.layout.activity_main);
        mFrameView = (OpenNIView) findViewById(R.id.frameView);
        mVideoView = (VideoView) findViewById(R.id.videoView);
        mProgressBar = (ProgressBar) findViewById(R.id.progressBar);
    }

    private void orbbecPrepare() {
//        OpenNI.setLogAndroidOutput(true);
        OpenNI.setLogMinSeverity(0);
        OpenNI.initialize();

        mDeviceOpenListener = new OpenNIHelper.DeviceOpenListener() {
            @Override
            public void onDeviceOpened(Device device) {
                Log.d(TAG, "onDeviceOpened. Uri Device: " + device.getDeviceInfo().getUri());

                mIsOrbbecDeviceOpening = false;

                try {
                    mDevice = device;
                    mStream = VideoStream.create(mDevice, SensorType.DEPTH);
                    mStream.setVideoMode(mStream.getSensorInfo().getSupportedVideoModes().get(0));
                    mStream.start();
                    mIsOrbbecStreamReady = true;

                } catch (RuntimeException e) {
                    mIsOrbbecStreamReady = false;
                    showAlertAndExit("Failed to open stream: " + e.getMessage());
                }
            }

            @Override
            public void onDeviceOpenFailed(String uri) {
                Log.d(TAG, "onDeviceOpenFailed");

                mIsOrbbecStreamReady = false;
                mIsOrbbecDeviceOpening = false;
                showAlertAndExit("Failed to open device");
            }
        };
    }

    private void videoPrepare() {
        MediaPlayer.OnCompletionListener onCompletePlay = new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                mVideoFile = LOSE_VIDEO;
                videoPrepare();
            }
        };

        MediaPlayer.OnCompletionListener onCompleteWinOrLose = new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                mVideoFile = IDLE_VIDEO;
                videoPrepare();
            }
        };

        MediaPlayer.OnPreparedListener onPreparedIdle = new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                mp.setLooping(true);
            }
        };

        MediaPlayer.OnPreparedListener onPreparedPlayOrWinOrLose = new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                mp.setLooping(false);
            }
        };

        switch (mVideoFile) {
            case IDLE_VIDEO:
                mVideoView.setOnPreparedListener(onPreparedIdle);
                mVideoView.setOnCompletionListener(null);
                break;
            case PLAY_VIDEO:
                mVideoView.setOnCompletionListener(onCompletePlay);
                mVideoView.setOnPreparedListener(onPreparedPlayOrWinOrLose);
                break;
            case LOSE_VIDEO:
                mVideoView.setOnCompletionListener(onCompleteWinOrLose);
                mVideoView.setOnPreparedListener(onPreparedPlayOrWinOrLose);
                break;
            case WIN_VIDEO:
                mVideoView.setOnCompletionListener(onCompleteWinOrLose);
                mVideoView.setOnPreparedListener(onPreparedPlayOrWinOrLose);
        }

        videoRefresh(mVideoFile);
    }

    private void speechPrepare() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showAlertAndExit("Speech Recognition Service is not available.");
        }

        mRecognizerIntent = new Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE);
        mRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10000)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);

        mRecognitionListener = new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                Log.d(TAG, "onReadyForSpeech");
            }

            @Override
            public void onBeginningOfSpeech() {
                Log.d(TAG, "onBeginningOfSpeech");

                mProgressBar.setIndeterminate(false);
                mProgressBar.setMax(10);
            }

            @Override
            public void onRmsChanged(float rmsdB) {
                mProgressBar.setProgress((int) rmsdB);
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
                Log.d(TAG, "onBufferReceived");
            }

            @Override
            public void onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech");

                mProgressBar.setIndeterminate(true);
            }

            @Override
            public void onError(int error) {
                Log.d(TAG, "onError: " + error);

                switch (error) {
                    case SpeechRecognizer.ERROR_AUDIO:
                    case SpeechRecognizer.ERROR_NO_MATCH:
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    case SpeechRecognizer.ERROR_CLIENT:
                    case SpeechRecognizer.ERROR_NETWORK:
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    case SpeechRecognizer.ERROR_SERVER:
                        mSpeechReady = mPeopleReady;
                        break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                        break;
                }
            }

            @Override
            public void onResults(Bundle results) {
                Log.d(TAG, "onResults");

                String answer = "mountain";

                ArrayList<String> guess = new ArrayList<>();
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                for (String word : matches) {
                    if (word.toLowerCase().equals(answer)) {
                        mVideoFile = WIN_VIDEO;
                        videoPrepare();
                    }
                    Log.d(TAG, "word=" + word);
                }

                mSpeechReady = true;
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                Log.d(TAG, "onPartialResults");
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
                Log.d(TAG, "onEvent");
            }
        };
    }

    private void resumeOrbbec() {
        List<DeviceInfo> devices = OpenNI.enumerateDevices();
        if (devices.isEmpty()) {
            showAlertAndExit("No OpenNI-compliant device found.");
            return;
        }

        String uri = devices.get(0).getUri();

        mIsOrbbecDeviceOpening = true;
        mOpenNIHelper.requestDeviceOpen(uri, mDeviceOpenListener);
    }

    private void resumeSpeech() {
        mProgressBar.setVisibility(View.INVISIBLE);
        mSpeechRecognizer.setRecognitionListener(mRecognitionListener);
        mSpeechReady = false;
    }

    private void resumeWorker() {
        mMainLoopThread = new Thread() {
            @Override
            public void run() {
                while (mWorkerRun) {
                    if (mIsOrbbecStreamReady) {
                        try {
                            VideoFrameRef frame = mStream.readFrame();

                            checkPeople(frame);

                            // Request rendering of the current OpenNI frame
                            mFrameView.setBaseColor(Color.WHITE);
                            mFrameView.update(frame);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed reading frame: " + e);
                        }
                    }

                    if (mPeopleReady && mVideoFile.equals(IDLE_VIDEO)) {
                        mVideoFile = PLAY_VIDEO;
                        videoPrepare();

                        showOrbbecView(false);

                        mSpeechReady = true;
                    } else if (!mPeopleReady && !mVideoFile.equals(IDLE_VIDEO)) {
                        mVideoFile = IDLE_VIDEO;
                        videoPrepare();

                        showOrbbecView(true);

                        mSpeechReady = false;
                    }

                    if (mSpeechReady) {
                        mSpeechReady = false;
                        startSpeechToText();
                    }
                }
            }
        };

        mMainLoopThread.setName("Swara MainLoop Thread");
        mMainLoopThread.start();
    }

    private void stopOrbbec() {
        if (mStream != null) {
            mStream.stop();
            mStream.destroy();
            mStream = null;
        }

        if (mDevice != null) {
            mDevice.close();
            mDevice = null;
        }
    }

    private void stopSpeech() {
        if (mSpeechRecognizer != null) {
            mSpeechRecognizer.destroy();
        }
    }

    private void stopWorker() {
        mWorkerRun = false;

        while (mMainLoopThread != null) {
            try {
                mMainLoopThread.join();
                mMainLoopThread = null;
                break;
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void checkPeople(VideoFrameRef frame) {
        int min = 0xffff;
        ByteBuffer data = frame.getData();
        for (int i = 0; i < frame.getWidth() * frame.getHeight(); i++) {
            int value = data.getChar();
            if (min > value && value > 0) {
                min = value;
            }
        }

        if (400 < min && min < 1000) {
            mCountOk++;
        } else {
            mCountNg++;
        }

        int tolerance = 10;
        if (mCountOk > tolerance) {
            mPeopleReady = true;
            mCountOk = 0;
        }

        if (mCountNg > tolerance) {
            mPeopleReady = false;
            mCountNg = 0;
        }
    }

    private void showOrbbecView(final boolean yes) {
        MainActivity.this.runOnUiThread(new Runnable() {
            public void run() {
                mFrameView.setVisibility(yes ? View.VISIBLE : View.INVISIBLE);
            }
        });
    }

    private void showAlertAndExit(String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(message);
        builder.setNeutralButton("OK", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                finish();
            }
        });
        builder.show();
    }

    private void videoRefresh(final String videoFile) {
        MainActivity.this.runOnUiThread(new Runnable() {
            public void run() {
                mVideoView.stopPlayback();
                mVideoView.setVideoPath(mVideoDir + "/" + videoFile);
                mVideoView.start();
            }
        });
    }

    private void startSpeechToText() {
        MainActivity.this.runOnUiThread(new Runnable() {
            public void run() {
                Log.d(TAG, "Restart Speech");
                stopSpeech();
                speechPrepare();
                resumeSpeech();
                mProgressBar.setVisibility(View.VISIBLE);
                mProgressBar.setIndeterminate(true);
                mSpeechRecognizer.startListening(mRecognizerIntent);
            }
        });
    }

    /*--------------------------------------------------------------------------------------------*/

}
