package com.example.android.sunshine.app.sync;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;
import com.example.android.sunshine.app.R;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

public class SunshineDataSyncRequestService extends WearableListenerService {

    private static final String REQUEST_DATA_SYNC_PATH = "/sunshine-sync-data-request";
    private static final String LOG_TAG = SunshineDataSyncRequestService.class.getSimpleName();

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        Log.d(LOG_TAG, "onMessageReceived: " + messageEvent + ", with path: "
                + messageEvent.getPath());

        // Check to see if the message is to start a data sync
        if (messageEvent.getPath().equals(SunshineDataSyncRequestService.REQUEST_DATA_SYNC_PATH)) {

            // Reseting last data sent to watch face, to force data to be sent this time
            SharedPreferences prefs =
                    PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            String lastWatchFaceHighTempKey =
                    getApplicationContext().getString(R.string.pref_last_watch_face_high_temp_key);
            String lastWatchFaceLowTempKey =
                    getApplicationContext().getString(R.string.pref_last_watch_face_low_temp_key);
            String lastWatchFaceWeatherIdKey =
                    getApplicationContext().getString(R.string.pref_last_watch_face_weather_id_key);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(lastWatchFaceHighTempKey, "");
            editor.putString(lastWatchFaceLowTempKey, "");
            editor.putInt(lastWatchFaceWeatherIdKey, -1);
            editor.commit();

            SunshineSyncAdapter.syncImmediately(getApplicationContext());
        }
    }
}