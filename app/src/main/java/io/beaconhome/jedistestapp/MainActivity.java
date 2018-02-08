package io.beaconhome.jedistestapp;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.StreamMessage;
import redis.clients.jedis.XReadArgs;
import redis.clients.util.SafeEncoder;
import timber.log.Timber;

public class MainActivity extends AppCompatActivity {

    private static final String STREAM_TEST = "STREAMTEST";
    private static final String STREAM_TEST_2 = "STREAMTEST2";

    private RedisService service;
    private MyConnection connection = new MyConnection();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Timber.d("onCreate");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Timber.d("onResume");
        startService(RedisService.startIntent(this));
        boolean bound = bindService(RedisService.startIntent(this), connection, 0);

        Timber.d("Service bound %s", bound);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Timber.d("onPause");
        unbindService(connection);
    }

    private void startTest(Jedis jedis) {
        Timber.d("Service bound, starting jedis tests!");

        String offset = "0";
        String offset2 = "0";

        List<Long> xaddDurations = new ArrayList<>();
        List<Long> xreadDurations = new ArrayList<>();

        for (int i = 0; i < 100; i++) {

            long startTime = System.currentTimeMillis();
            Timber.d("XADD START");
            String result = jedis.xadd(STREAM_TEST, Collections.singletonMap("Key", "Value"));
            String result2 = jedis.xadd(STREAM_TEST_2, Collections.singletonMap("Key2", "Value2"));
            long addDuration = System.currentTimeMillis() - startTime;
            Timber.d("XADD RESULT %s, DURATION %d", result, addDuration);
            xaddDurations.add(addDuration);

            startTime = System.currentTimeMillis();
            Timber.d("XREAD START");
            List<StreamMessage> readResult = jedis.xRead(XReadArgs.builder().add(STREAM_TEST, offset).add(STREAM_TEST_2, offset2));
            long readDuration = System.currentTimeMillis() - startTime;
            Timber.d("XREAD STREAM SIZE %d, DURATION %d",  readResult.size(), readDuration);
            xreadDurations.add(readDuration);

            offset = result;
            offset2 = result2;
        }

        double xAddAverage= xaddDurations.stream().mapToLong(aLong -> aLong).average().getAsDouble();
        double xReadAverage= xreadDurations.stream().mapToLong(aLong -> aLong).average().getAsDouble();

        Timber.d("add average %.2f milliseconds, read average %.2f milliseconds", xAddAverage, xReadAverage);

    }

    public class MyConnection implements ServiceConnection {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            service = ((RedisService.RedisBinder) iBinder).getService();
            service.setListener(jedis -> {
                startTest(jedis);
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            service = null;
        }
    }
}
