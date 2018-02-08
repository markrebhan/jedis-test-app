package io.beaconhome.jedistestapp;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import redis.clients.jedis.Jedis;
import timber.log.Timber;

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;


public class RedisService extends BaseThreadedService {


    public static final String ACTION_COPY_ASSETS = "ACTION_COPY_ASSETS";
    public static final String ACTION_START_REDIS = "ACTION_START_REDIS";
    public static final String ACTION_STOP_REDIS = "ACTION_STOP_REDIS";

    private static final String ASSET_REDIS = "redis";
    private static final String DIRECTORY_REDIS = ASSET_REDIS + "/";
    private static final String EXECUTABLE_REDIS = "/redis-server";
    private static final String REDIS_CONF = "redis.conf";

    private final LinkedBlockingQueue<String> logQueue = new LinkedBlockingQueue<>();

    private Process currentProcess;

    private MyStdOutThread stdOutThread;
    private MyLoggingThread loggingThread;

    private IBinder binder = new RedisBinder();

    public static Intent startIntent(Context context) {
        Intent intent = new Intent(context, RedisService.class);
        intent.setAction(ACTION_START_REDIS);
        return intent;
    }

    public static Intent stopIntent(Context context) {
        Intent intent = new Intent(context, RedisService.class);
        intent.setAction(ACTION_STOP_REDIS);
        return intent;
    }

    public RedisService() {
        super(RedisService.class.getSimpleName());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        closeThreads();
    }

    @Override
    protected void onHandleCreate() {}

    @Override
    protected void onStartCommand(Intent intent) {
        if (intent != null && intent.getAction() != null) {
            Timber.d("onStartCommand: " + intent.getAction());
            String action = intent.getAction();
            switch (action) {
                case ACTION_START_REDIS:
                    startRedisServer();
                    break;
                case ACTION_STOP_REDIS:
                    stopRedisServer();
                    break;
                case ACTION_COPY_ASSETS:
                    copyAssets();
                    break;
                default:
                    break;
            }
        }
    }

    private Jedis jedis;

    public Jedis getJedis() {
        return jedis;
    }

    private Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Create a new process, sets the LD_LIBRARY_PATH environment variable so Redis can find all its
     * dependencies, starts the process and creates the threads to process the messages in stdout.
     */
    private void startRedisServer() {
        try {
            if (this.currentProcess != null) {
                Timber.w("Redis already running.");
                return;
            }

            stopRedisServer();

            Timber.d("startRedisServer");

            if (!getRedisDir().exists()) {
                copyAssets();
            }

            ProcessBuilder pb = new ProcessBuilder(new File(getRedisDir(), EXECUTABLE_REDIS).getPath(), new File(getRedisDir(), REDIS_CONF).getPath());
            Map<String, String> env = pb.environment();
            env.put("LD_LIBRARY_PATH", getRedisDir().getPath());
            pb.directory(getRedisDir());
            pb.redirectErrorStream(true);
            this.currentProcess = pb.start();
            startLoopingThreads();

        } catch (Exception e) {
            Timber.e(e,"Exception");
        }
    }

    /**
     * Kills current process and logging threads
     */
    private void stopRedisServer() {
        Timber.d("stopRedisServer");

        if (currentProcess != null) {
            currentProcess.destroy();
            this.currentProcess = null;
        }

        closeThreads();
    }

    /**
     * Creates threads to handle log messages from stdout
     */
    private void startLoopingThreads() {
        final BufferedReader stdout = new BufferedReader(new InputStreamReader(this.currentProcess.getInputStream()));
        stdOutThread = new MyStdOutThread(stdout);
        stdOutThread.startLooping();

        loggingThread = new MyLoggingThread();
        loggingThread.startLooping();
    }

    private void closeThreads() {
        if (stdOutThread != null) {
            stdOutThread.close();
            stdOutThread = null;
        }

        if (loggingThread != null) {
            loggingThread.close();
            loggingThread = null;
        }
    }

    /**
     * Gets expected location of redis directory in file system
     */
    private File getRedisDir() {
        return new File(getFilesDir(), DIRECTORY_REDIS);
    }

    /**
     * Copies Redis Server files from APK raw to the file system in apps files directory.
     * The executables will have execute permissions.
     */
    private void copyAssets() {
        AssetManager assetManager = getAssets();
        String[] files = null;

        getRedisDir().mkdir();

        try {
            files = assetManager.list(ASSET_REDIS);
        } catch (IOException e) {
            Timber.e(e, "Failed to get asset file list.");
        }

        if (files != null) {
            for (String filename : files) {
                Timber.d("Found asset: %s", filename);
                InputStream in = null;
                OutputStream out = null;
                try {
                    in = assetManager.open(DIRECTORY_REDIS + filename, MODE_PRIVATE);
                    File outFile = new File(getRedisDir(), filename);
                    out = new FileOutputStream(outFile);
                    copyFile(in, out);
                    if (!filename.endsWith(".so")) {
                        outFile.setExecutable(true);
                    }

                } catch (IOException e) {
                    Timber.e(e, "Failed to copy asset file: %s", filename);
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (IOException e) {
                            // NOOP
                        }
                    }
                    if (out != null) {
                        try {
                            out.close();
                        } catch (IOException e) {
                            // NOOP
                        }
                    }
                }
            }
        }
    }

    private static void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static boolean isAlive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch(IllegalThreadStateException e) {
            return true;
        }
    }

    /**
     * Background Thread that reads log output from stdout on Redis Server and add to processing queue
     *
     * TODO make this optional for builds?
     */
    private class MyStdOutThread implements LoopingThreadHandler {

        private final BufferedReader stdOut;

        private final Looper looper;
        private final MyHandler handler;

        public MyStdOutThread(BufferedReader stdOut) {
            this.stdOut = stdOut;

            HandlerThread handlerThread = new HandlerThread("redisStdOut", THREAD_PRIORITY_BACKGROUND);
            handlerThread.start();
            looper = handlerThread.getLooper();

            handler = new MyHandler(looper);
        }

        @Override
        public void startLooping() {
            handler.sendEmptyMessage(0);
        }

        @Override
        public void stop() {
            handler.removeCallbacksAndMessages(null);
        }

        @Override
        public void close() {
            stop();
            looper.quit();

            try {
                stdOut.close();
            } catch (IOException e) {
                // NOOP
            }
        }

        private class MyHandler extends Handler {

            public MyHandler(Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message msg) {

                if (currentProcess != null && isAlive(currentProcess)) {
                    try {
                        String line = stdOut.readLine();
                        if (line != null) {
                            RedisService.this.logQueue.put(line);
                            sendEmptyMessage(0);
                        } else {
                            sendEmptyMessageDelayed(0, 100);
                        }
                    } catch (Exception e) {
                        Timber.e(e);

                    }
                } else {
                    close();
                }
            }
        }
    }

    /**
     * Background thread the takes top of queue and logs to logcat
     *
     * TODO make this optional for builds?
     */
    private class MyLoggingThread implements LoopingThreadHandler {

        private final Looper looper;
        private final Handler handler;

        public MyLoggingThread() {
            HandlerThread handlerThread = new HandlerThread("redisLog", THREAD_PRIORITY_BACKGROUND);
            handlerThread.start();
            looper = handlerThread.getLooper();

            handler = new Handler(looper);
        }

        @Override
        public void startLooping() {
            handler.post(() -> {
                while (true) {
                    try {
                        String message = logQueue.take();
                        Timber.d("REDIS: %s", message);

                        if (message.contains("Ready to accept connections")) {
                            Timber.d("Redis server ready!");
                            jedis = new Jedis(URI.create("redis://localhost:6379"));

                            if (listener != null) {
                                listener.onClientAvailable(jedis);
                            }
                        }

                    } catch (InterruptedException e) {
                        Timber.e(e);
                    }
                }
            });
        }

        @Override
        public void stop() {
            handler.removeCallbacksAndMessages(null);
            logQueue.clear();
        }

        @Override
        public void close() {
            stop();
            looper.quit();
        }

    }

    public class RedisBinder extends Binder {

        RedisService getService() {
            return RedisService.this;
        }
    }

    private interface LoopingThreadHandler {
        void startLooping();
        void stop();
        void close();
    }

    public interface Listener {
        void onClientAvailable(Jedis jedis);
    }
}
