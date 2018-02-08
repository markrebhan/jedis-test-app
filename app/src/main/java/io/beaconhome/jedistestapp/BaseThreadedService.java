package io.beaconhome.jedistestapp;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.CallSuper;
import android.support.annotation.WorkerThread;

/*
* The implementation is similar to {@link android.app.IntentService} where a handler thread
* is created and runs the service work on it but does not call stopSelf().
*/
public abstract class BaseThreadedService extends Service {

    private static final int MSG_CREATE = 0;
    private static final int MSG_START = 1;

    private final String name;

    private MyHandler handler;
    private Looper serviceLooper;

    public BaseThreadedService(String name) {
        this.name = name;
    }

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();

        HandlerThread handlerThread = new HandlerThread(name);
        handlerThread.start();

        serviceLooper = handlerThread.getLooper();
        handler = new MyHandler(serviceLooper);

        Message message = handler.obtainMessage(MSG_CREATE);
        handler.sendMessage(message);
    }

    /**
     * This method gets called once the new handler thread is initialized in the onCreate() method
     */
    @WorkerThread
    protected abstract void onHandleCreate();

    /**
     * This method gets called when a new start command comes when start service is explicitly called.
     * The method runs on the services handler thread.
     */
    @WorkerThread
    protected void onStartCommand(Intent intent) {}

    @Override
    public final int onStartCommand(Intent intent, int flags, int startId) {
        Message message = handler.obtainMessage(MSG_START, intent);
        handler.sendMessage(message);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        serviceLooper.quit();
        super.onDestroy();
    }

    public Looper getServiceLooper() {
        return serviceLooper;
    }

    private class MyHandler extends Handler {

        public MyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_CREATE:
                    onHandleCreate();
                    break;
                case MSG_START:
                    onStartCommand((Intent) msg.obj);
                    break;
            }
        }
    }
}
