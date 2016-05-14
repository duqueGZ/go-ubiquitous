/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with Sunshine weather data.
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {

    private static final String TAG = SunshineWatchFace.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);
    // Minimum gaps between consecutive data sync requests
    private static final long DATA_SYNC_REQUESTS_GAP_MS = 60 * 1000; // 60 seconds

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        private static final String WATCH_FACE_DATA_PATH = "/sunshine-watch-face-data";
        private static final String WATCH_FACE_HIGH_TEMP_KEY = "high-temp";
        private static final String WATCH_FACE_LOW_TEMP_KEY = "low-temp";
        private static final String WATCH_FACE_WEATHER_ICON_KEY = "weather-icon";
        private static final String REQUEST_DATA_SYNC_PATH = "/sunshine-sync-data-request";

        Paint mBackgroundPaint;
        Paint mTextPaintDay;
        Paint mTextPaintHighTemp;
        Paint mTextPaintLowTemp;
        Paint mTextPaintHour;
        Paint mWeatherIconPaint;
        Paint mSeparatorLinePaint;
        boolean mAmbient;
        boolean mIsRound;
        Calendar mCalendar;
        float mXOffset;
        float mYOffset;
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        final SimpleDateFormat mDayFormat
                = new SimpleDateFormat(getApplicationContext().getResources()
                .getString(R.string.day_sdf));
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        private GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        // Watch Face data
        String mHighTemp = null;
        String mLowTemp = null;
        Bitmap mWeatherIcon = null;
        Bitmap mWeatherGrayIcon = null;
        long lastDataSyncRequestTimestamp = -1;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.y_init_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaintHour
                    = createTextPaint(resources.getColor(R.color.text_color), BOLD_TYPEFACE,
                    resources.getDimension(R.dimen.hour_data_textsize));
            mTextPaintDay
                    = createTextPaint(resources.getColor(R.color.text_color), NORMAL_TYPEFACE,
                    resources.getDimension(R.dimen.day_data_textsize));
            mTextPaintHighTemp
                    = createTextPaint(resources.getColor(R.color.text_color), BOLD_TYPEFACE,
                    resources.getDimension(R.dimen.temp_data_textsize));
            mTextPaintLowTemp
                    = createTextPaint(resources.getColor(R.color.text_color), NORMAL_TYPEFACE,
                    resources.getDimension(R.dimen.temp_data_textsize));

            mSeparatorLinePaint = new Paint();
            mSeparatorLinePaint.setColor(resources.getColor(R.color.separator_line_color));

            mWeatherIconPaint = new Paint();

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, Typeface typeface, float size) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setTextSize(size);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();

                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            mIsRound = insets.isRound();
            mXOffset = resources.getDimension(mIsRound
                    ? R.dimen.x_init_offset : R.dimen.x_init_offset_square);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaintHour.setAntiAlias(!inAmbientMode);
                    mTextPaintDay.setAntiAlias(!inAmbientMode);
                    mTextPaintHighTemp.setAntiAlias(!inAmbientMode);
                    mTextPaintLowTemp.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            Resources resources = getApplicationContext().getResources();
            Locale locale = getApplicationContext().getResources().getConfiguration().locale;
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            float dayDataOffset = resources.getDimension(R.dimen.day_data_offset);
            float separatorLineOffset = mIsRound ?
                    resources.getDimension(R.dimen.separator_line_offset) :
                    resources.getDimension(R.dimen.separator_line_offset_square);
            float weatherDataOffset = mIsRound ?
                    resources.getDimension(R.dimen.weather_data_offset) :
                    resources.getDimension(R.dimen.weather_data_offset_square);
            float tempDataOffset = mIsRound ?
                    resources.getDimension(R.dimen.temp_data_offset) :
                    resources.getDimension(R.dimen.temp_data_offset_square);
            float separatorLineSize = resources.getDimension(R.dimen.separator_line_size);

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            String hourText = String.format(locale, "%d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY),
                    mCalendar.get(Calendar.MINUTE));
            canvas.drawText(hourText, bounds.centerX() - (mTextPaintHour.measureText(hourText)/2),
                    mYOffset, mTextPaintHour);

            String dayText = mDayFormat.format(mCalendar.getTime());
            canvas.drawText(dayText, bounds.centerX() - (mTextPaintDay.measureText(dayText)/2),
                    mYOffset + dayDataOffset, mTextPaintDay);

            canvas.drawLine(bounds.centerX() - separatorLineSize / 2,
                    mYOffset + separatorLineOffset,
                    bounds.centerX() + separatorLineSize / 2,
                    mYOffset + separatorLineOffset,
                    mSeparatorLinePaint);

            // Weather Data
            boolean askForWeatherData = Boolean.TRUE;

            if (!mLowBitAmbient) {
                if (!isInAmbientMode() && mWeatherIcon != null) {
                    askForWeatherData = Boolean.FALSE;
                    canvas.drawBitmap(mWeatherIcon,
                            new Rect(0, 0, mWeatherIcon.getWidth(), mWeatherIcon.getHeight()),
                            new Rect(bounds.centerX() - 25, (int) (mYOffset + weatherDataOffset - 25),
                                    bounds.centerX() + 25, (int) (mYOffset + weatherDataOffset + 25)),
                            mWeatherIconPaint);
                } else if (isInAmbientMode() && mWeatherGrayIcon != null) {
                    askForWeatherData = Boolean.FALSE;
                    canvas.drawBitmap(mWeatherGrayIcon,
                            new Rect(0, 0, mWeatherGrayIcon.getWidth(), mWeatherGrayIcon.getHeight()),
                            new Rect(bounds.centerX() - 25, (int) (mYOffset + weatherDataOffset - 25),
                                    bounds.centerX() + 25, (int) (mYOffset + weatherDataOffset + 25)),
                            mWeatherIconPaint);
                }
            }
            if (mHighTemp!=null) {
                askForWeatherData = Boolean.FALSE;
                canvas.drawText(mHighTemp,
                        bounds.centerX() - (mTextPaintHighTemp.measureText(mHighTemp)/2) - 25,
                        mYOffset + tempDataOffset, mTextPaintHighTemp);
            }
            if (mLowTemp!=null) {
                askForWeatherData = Boolean.FALSE;
                canvas.drawText(mLowTemp,
                        bounds.centerX() - (mTextPaintLowTemp.measureText(mLowTemp)/2) + 25,
                        mYOffset + tempDataOffset, mTextPaintLowTemp);
            }


            if ((askForWeatherData) && (mGoogleApiClient.isConnected())
                    && ((now - lastDataSyncRequestTimestamp)>SunshineWatchFace.DATA_SYNC_REQUESTS_GAP_MS )) {
                lastDataSyncRequestTimestamp = now;
                // Trigger an AsyncTask that will query for a list of connected nodes and send a
                // "sunshine-sync-data-request" message to each connected node.
                new SendDataSyncRequestTask().execute();
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }


        @Override // DataApi.DataListener
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.d(TAG, "onDataChanged received");

            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() != DataEvent.TYPE_CHANGED) {
                    continue;
                }

                DataItem dataItem = dataEvent.getDataItem();
                if (!dataItem.getUri().getPath().equals(
                        Engine.WATCH_FACE_DATA_PATH)) {
                    continue;
                }

                DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
                DataMap dataMap = dataMapItem.getDataMap();
                mHighTemp = dataMap.getString(Engine.WATCH_FACE_HIGH_TEMP_KEY);
                mLowTemp = dataMap.getString(Engine.WATCH_FACE_LOW_TEMP_KEY);
                Asset iconAsset = dataMap.getAsset(Engine.WATCH_FACE_WEATHER_ICON_KEY);
                if (iconAsset!=null) {
                    // Loads image on background thread.
                    new LoadBitmapAsyncTask().execute(iconAsset);
                }
                invalidate();
            }
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnected(Bundle connectionHint) {
            Log.d(TAG, "onConnected: " + connectionHint);
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnectionSuspended(int cause) {
            Log.d(TAG, "onConnectionSuspended: " + cause);
        }

        @Override  // GoogleApiClient.OnConnectionFailedListener
        public void onConnectionFailed(ConnectionResult result) {
            Log.d(TAG, "onConnectionFailed: " + result);
        }

        /*
         * Extracts {@link android.graphics.Bitmap} data from the
         * {@link com.google.android.gms.wearable.Asset}
         */
        private class LoadBitmapAsyncTask extends AsyncTask<Asset, Void, Bitmap> {

            @Override
            protected Bitmap doInBackground(Asset... params) {

                if (params.length > 0) {

                    Asset asset = params[0];

                    InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                            mGoogleApiClient, asset).await().getInputStream();

                    if (assetInputStream == null) {
                        Log.w(TAG, "Requested an unknown Asset.");
                        return null;
                    }
                    return BitmapFactory.decodeStream(assetInputStream);

                } else {
                    Log.e(TAG, "Asset must be non-null");
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {

                if (bitmap != null) {
                    mWeatherIcon = bitmap;
                    mWeatherGrayIcon = createGrayBitmap(bitmap);

                    invalidate();
                }
            }

            private Bitmap createGrayBitmap(Bitmap bitmap) {
                Bitmap grayBitmap = Bitmap.createBitmap(
                        bitmap.getWidth(),
                        bitmap.getHeight(),
                        Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(grayBitmap);
                Paint grayPaint = new Paint();
                ColorMatrix colorMatrix = new ColorMatrix();
                colorMatrix.setSaturation(0);
                ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
                grayPaint.setColorFilter(filter);
                canvas.drawBitmap(bitmap, 0, 0, grayPaint);

                return grayBitmap;
            }
        }

        private class SendDataSyncRequestTask extends AsyncTask<Void, Void, Void> {

            @Override
            protected Void doInBackground(Void... args) {
                Collection<String> nodes = getNodes();
                for (final String node : nodes) {

                    Wearable.MessageApi.sendMessage(mGoogleApiClient, node,
                            Engine.REQUEST_DATA_SYNC_PATH, new byte[0]).setResultCallback(
                            new ResultCallback<MessageApi.SendMessageResult>() {
                                @Override
                                public void onResult(@NonNull MessageApi.SendMessageResult sendMessageResult) {
                                    if (sendMessageResult.getStatus().isSuccess()) {
                                        Log.d(TAG, "Request data sync message successfully sent to node: " + node);
                                    }
                                }
                            }
                    );
                }
                return null;
            }

            private Collection<String> getNodes() {
                HashSet<String> results = new HashSet<>();
                NodeApi.GetConnectedNodesResult nodes =
                        Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).await();

                for (Node node : nodes.getNodes()) {
                    results.add(node.getId());
                }

                return results;
            }
        }
    }
}
