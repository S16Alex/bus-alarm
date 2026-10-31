package com.busalarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public class BusService extends Service implements LocationListener {

    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String ACTION_SIMULATE = "ACTION_SIMULATE";
    private static final String CHANNEL_ID = "bus_alarm_channel";
    private static final int NOTIF_ID = 1001;

    public static class StopItem {
        public String step;
        public String name;
        public double lat;
        public double lng;
        public boolean notified;

        public StopItem(String step, String name, double lat, double lng) {
            this.step = step;
            this.name = name;
            this.lat = lat;
            this.lng = lng;
            this.notified = false;
        }
    }

    public static boolean isRunning = false;
    public static String currentDir = "toSchool";
    public static int alertRadius = 180;
    public static double lastLat = 0.0;
    public static double lastLng = 0.0;
    public static int lastAcc = 0;

    public static StopItem[] toSchoolStops = new StopItem[3];
    public static StopItem[] toHomeStops = new StopItem[3];

    private LocationManager locationManager;
    private PowerManager.WakeLock wakeLock;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastNotifUpdate = 0;

    public static void initDefaults() {
        toSchoolStops[0] = new StopItem("2정거장 전", "복정역·복정파출소", 37.4692, 127.1267);
        toSchoolStops[1] = new StopItem("1정거장 전", "동서울대학교", 37.4581, 127.1287);
        toSchoolStops[2] = new StopItem("하차 정류장", "가천대학교", 37.4503, 127.1288);

        toHomeStops[0] = new StopItem("2정거장 전", "세곡동사거리", 37.4660, 127.1065);
        toHomeStops[1] = new StopItem("1정거장 전", "은곡마을·대왕초교", 37.4688, 127.1018);
        toHomeStops[2] = new StopItem("하차 정류장", "세곡푸르지오아파트", 37.4668, 127.0945);
    }

    public static StopItem[] getCurrentStops() {
        return "toSchool".equals(currentDir) ? toSchoolStops : toHomeStops;
    }

    public static void loadPrefs(Context ctx) {
        if (toSchoolStops[0] == null) initDefaults();
        SharedPreferences prefs = ctx.getSharedPreferences("BusAlarmPrefs", MODE_PRIVATE);
        alertRadius = prefs.getInt("alertRadius", 180);
        currentDir = prefs.getString("currentDir", "toSchool");
        loadStopArray(prefs.getString("toSchool", null), toSchoolStops);
        loadStopArray(prefs.getString("toHome", null), toHomeStops);
    }

    private static void loadStopArray(String json, StopItem[] target) {
        if (json == null) return;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < 3; i++) {
                JSONObject o = arr.getJSONObject(i);
                target[i].name = o.getString("name");
                target[i].lat = o.getDouble("lat");
                target[i].lng = o.getDouble("lng");
            }
        } catch (Exception ignored) {}
    }

    public static void savePrefs(Context ctx) {
        SharedPreferences.Editor ed = ctx.getSharedPreferences("BusAlarmPrefs", MODE_PRIVATE).edit();
        ed.putInt("alertRadius", alertRadius);
        ed.putString("currentDir", currentDir);
        ed.putString("toSchool", toJson(toSchoolStops));
        ed.putString("toHome", toJson(toHomeStops));
        ed.apply();
    }

    private static String toJson(StopItem[] items) {
        JSONArray arr = new JSONArray();
        try {
            for (StopItem s : items) {
                JSONObject o = new JSONObject();
                o.put("name", s.name);
                o.put("lat", s.lat);
                o.put("lng", s.lng);
                arr.put(o);
            }
        } catch (Exception ignored) {}
        return arr.toString();
    }

    public static int getDistanceMeters(double lat1, double lng1, double lat2, double lng2) {
        float[] res = new float[1];
        Location.distanceBetween(lat1, lng1, lat2, lng2, res);
        return Math.round(res[0]);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        loadPrefs(this);
        createChannel();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();

        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(change -> {})
                .build();

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.KOREAN);
                tts.setSpeechRate(0.95f);
                tts.setAudioAttributes(attrs);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {}
                    @Override public void onDone(String id) {
                        handler.postDelayed(() -> audioManager.abandonAudioFocusRequest(focusRequest), 300);
                    }
                    @Override public void onError(String id) {
                        handler.postDelayed(() -> audioManager.abandonAudioFocusRequest(focusRequest), 300);
                    }
                });
                ttsReady = true;
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopTrackingInternal();
            stopSelf();
            return START_NOT_STICKY;
        } else if (ACTION_SIMULATE.equals(action)) {
            int idx = intent.getIntExtra("sim_idx", 0);
            triggerStopAlert(idx);
            return START_STICKY;
        } else if (ACTION_START.equals(action)) {
            startTrackingInternal();
        }
        return START_STICKY;
    }

    private void startTrackingInternal() {
        for (StopItem s : getCurrentStops()) s.notified = false;
        isRunning = true;

        Notification notif = buildNotification("실시간 GPS 추적 중 (화면을 꺼도 작동합니다)");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, notif);
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BusAlarm::LocationLock");
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(4 * 60 * 60 * 1000L); // 최대 4시간 유지
        }

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2500, 0, this);
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2500, 0, this);
        } catch (SecurityException ignored) {}

        handler.postDelayed(() -> speakWithDucking("버스 하차 알림을 시작합니다. 화면을 끄거나 다른 앱을 사용하셔도 정상 작동합니다."), 500);
    }

    private void stopTrackingInternal() {
        isRunning = false;
        if (locationManager != null) {
            locationManager.removeUpdates(this);
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        stopForeground(true);
    }

    @Override
    public void onLocationChanged(Location loc) {
        lastLat = loc.getLatitude();
        lastLng = loc.getLongitude();
        lastAcc = Math.round(loc.getAccuracy());

        StopItem[] list = getCurrentStops();
        for (int i = 0; i < list.length; i++) {
            int dist = getDistanceMeters(lastLat, lastLng, list[i].lat, list[i].lng);
            if (!list[i].notified && dist <= alertRadius) {
                list[i].notified = true;
                triggerStopAlert(i);
            }
        }

        long now = System.currentTimeMillis();
        if (now - lastNotifUpdate > 4000) {
            lastNotifUpdate = now;
            int destDist = getDistanceMeters(lastLat, lastLng, list[2].lat, list[2].lng);
            updateNotification("목적지(" + list[2].name + ")까지 " + destDist + "m 남음");
        }
    }

    public void triggerStopAlert(int idx) {
        StopItem stop = getCurrentStops()[idx];
        String msg;
        if (idx == 0) {
            msg = "하차 두 정거장 전입니다. 현재 정류장은 " + stop.name + " 입니다. 슬슬 내릴 준비를 해 주세요.";
        } else if (idx == 1) {
            msg = "하차 한 정거장 전입니다. 현재 정류장은 " + stop.name + " 입니다. 다음 정류장에서 하차합니다.";
        } else {
            msg = "이번 정류장은 목적지인 " + stop.name + " 입니다. 잊으신 물건 없이 하차하시기 바랍니다.";
        }

        Vibrator vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vib != null) {
            vib.vibrate(VibrationEffect.createWaveform(new long[]{0, 300, 150, 300}, -1));
        }

        speakWithDucking(msg);
    }

    private void speakWithDucking(String text) {
        audioManager.requestAudioFocus(focusRequest);
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 85);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 350);
            handler.postDelayed(tone::release, 450);
        } catch (Exception ignored) {}

        handler.postDelayed(() -> {
            if (ttsReady && tts != null) {
                Bundle params = new Bundle();
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "BUS_TTS_ID");
            }
        }, 650);
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "버스 하차 알림 서비스", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("백그라운드 및 화면 꺼짐 상태에서 버스 위치를 추적합니다.");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String content) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("🎧 버스 하차 음성 알림 작동 중")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String content) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && isRunning) {
            nm.notify(NOTIF_ID, buildNotification(content));
        }
    }

    @Override
    public void onDestroy() {
        stopTrackingInternal();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onStatusChanged(String p, int s, Bundle e) {}
    @Override public void onProviderEnabled(String p) {}
    @Override public void onProviderDisabled(String p) {}
}
