package com.busalarm;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private Button tabSchoolBtn, tabHomeBtn;
    private TextView statusBadge, gpsAccText;
    private LinearLayout stopListContainer;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final Runnable uiUpdater = new Runnable() {
        @Override
        public void run() {
            refreshStatusAndDistances();
            uiHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BusService.loadPrefs(this);
        getWindow().setStatusBarColor(Color.parseColor("#0F172A"));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0F172A"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, dp(48));
        scroll.addView(root);

        // 타이틀
        TextView title = new TextView(this);
        title.setText("🎧 버스 하차 음성 알림 (APK)");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("홀드(화면 꺼짐) 및 다른 앱 사용 중 100% 작동 버전");
        sub.setTextColor(Color.parseColor("#94A3B8"));
        sub.setTextSize(13);
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub);

        // 배터리 절전 예외 설정 버튼
        Button batteryBtn = makeButton("⚡ 갤럭시 배터리 제한 없음 설정 (최초 1회 권장)", "#1E293B", "#60A5FA");
        batteryBtn.setOnClickListener(v -> requestBatteryExemption());
        LinearLayout.LayoutParams batLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        batLp.bottomMargin = dp(12);
        root.addView(batteryBtn, batLp);

        // 방향 선택 탭
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabSchoolBtn = makeButton("🎓 가천대 방면\n(세곡푸르지오 출발)", "#3B82F6", "#FFFFFF");
        tabHomeBtn = makeButton("🏠 세곡푸르지오 방면\n(가천대 출발)", "#1E293B", "#94A3B8");

        LinearLayout.LayoutParams tabLp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tabLp1.rightMargin = dp(6);
        LinearLayout.LayoutParams tabLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tabLp2.leftMargin = dp(6);

        tabSchoolBtn.setOnClickListener(v -> switchDirection("toSchool"));
        tabHomeBtn.setOnClickListener(v -> switchDirection("toHome"));
        tabs.addView(tabSchoolBtn, tabLp1);
        tabs.addView(tabHomeBtn, tabLp2);
        root.addView(tabs);

        // 상태 & 메인 컨트롤 카드
        LinearLayout statusCard = makeCard();
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(14);
        cardLp.bottomMargin = dp(16);

        LinearLayout statusHeader = new LinearLayout(this);
        statusHeader.setOrientation(LinearLayout.HORIZONTAL);
        statusHeader.setGravity(Gravity.CENTER_VERTICAL);

        statusBadge = new TextView(this);
        statusBadge.setText("대기 중");
        statusBadge.setTextSize(12);
        statusBadge.setTypeface(null, Typeface.BOLD);
        statusBadge.setPadding(dp(10), dp(4), dp(10), dp(4));

        gpsAccText = new TextView(this);
        gpsAccText.setText("GPS 수신 대기");
        gpsAccText.setTextColor(Color.parseColor("#94A3B8"));
        gpsAccText.setTextSize(12);
        gpsAccText.setGravity(Gravity.END);

        statusHeader.addView(statusBadge);
        statusHeader.addView(gpsAccText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statusCard.addView(statusHeader);

        LinearLayout ctrlRow = new LinearLayout(this);
        ctrlRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ctrlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctrlLp.topMargin = dp(12);

        Button startBtn = makeButton("▶ 탑승 시작 (GPS 켜기)", "#10B981", "#06281E");
        Button stopBtn = makeButton("■ 안내 종료", "#EF4444", "#FFFFFF");
        startBtn.setOnClickListener(v -> checkPermsAndStart());
        stopBtn.setOnClickListener(v -> stopServiceTracking());

        ctrlRow.addView(startBtn, tabLp1);
        ctrlRow.addView(stopBtn, tabLp2);
        statusCard.addView(ctrlRow, ctrlLp);
        root.addView(statusCard, cardLp);

        // 정류장 목록 섹션
        root.addView(makeSectionTitle("📍 알림 정류장 목록 (홀드/백그라운드 자동 감지)"));
        stopListContainer = new LinearLayout(this);
        stopListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(stopListContainer);

        // 시뮬레이션 섹션
        root.addView(makeSectionTitle("🔊 이어폰 음성 안내 & 음악 볼륨 줄임 즉시 테스트"));
        LinearLayout simRow = new LinearLayout(this);
        simRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            String label = (i == 0) ? "2정거장 전\n테스트" : (i == 1) ? "1정거장 전\n테스트" : "하차 정류장\n테스트";
            Button simBtn = makeButton(label, "#1E293B", "#93C5FD");
            simBtn.setTextSize(12);
            simBtn.setOnClickListener(v -> simulateVoice(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < 2) lp.rightMargin = dp(6);
            simRow.addView(simBtn, lp);
        }
        root.addView(simRow);

        // 설정 섹션
        root.addView(makeSectionTitle("⚙️ 알림 반경 설정 및 초기화"));
        LinearLayout settingRow = new LinearLayout(this);
        settingRow.setOrientation(LinearLayout.HORIZONTAL);

        Button radiusBtn = makeButton("반경 변경 (현재 " + BusService.alertRadius + "m)", "#1E293B", "#F8FAFC");
        radiusBtn.setOnClickListener(v -> showRadiusDialog(radiusBtn));

        Button resetBtn = makeButton("기본 좌표 복원", "#334155", "#E2E8F0");
        resetBtn.setOnClickListener(v -> {
            BusService.initDefaults();
            BusService.savePrefs(this);
            renderStopCards();
            Toast.makeText(this, "기본 정류장 좌표로 복원되었습니다.", Toast.LENGTH_SHORT).show();
        });

        settingRow.addView(radiusBtn, tabLp1);
        settingRow.addView(resetBtn, tabLp2);
        root.addView(settingRow);

        setContentView(scroll);
        updateTabStyles();
        renderStopCards();
    }

    private void switchDirection(String dir) {
        BusService.currentDir = dir;
        for (BusService.StopItem s : BusService.getCurrentStops()) s.notified = false;
        BusService.savePrefs(this);
        updateTabStyles();
        renderStopCards();
    }

    private void updateTabStyles() {
        boolean isSchool = "toSchool".equals(BusService.currentDir);
        styleButton(tabSchoolBtn, isSchool ? "#3B82F6" : "#1E293B", isSchool ? "#FFFFFF" : "#94A3B8");
        styleButton(tabHomeBtn, !isSchool ? "#3B82F6" : "#1E293B", !isSchool ? "#FFFFFF" : "#94A3B8");
    }

    private void renderStopCards() {
        stopListContainer.removeAllViews();
        BusService.StopItem[] stops = BusService.getCurrentStops();

        for (int i = 0; i < stops.length; i++) {
            final int idx = i;
            BusService.StopItem stop = stops[i];

            LinearLayout card = makeCard();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);

            LinearLayout topRow = new LinearLayout(this);
            topRow.setOrientation(LinearLayout.HORIZONTAL);

            TextView stepTv = new TextView(this);
            stepTv.setText(stop.step + (stop.notified ? " ✓ 안내완료" : ""));
            stepTv.setTextColor(Color.parseColor(stop.notified ? "#34D399" : "#60A5FA"));
            stepTv.setTypeface(null, Typeface.BOLD);
            stepTv.setTextSize(12);

            TextView distTv = new TextView(this);
            distTv.setTag("dist_" + idx);
            distTv.setText("-- m");
            distTv.setTextColor(Color.parseColor("#FBBF24"));
            distTv.setTypeface(null, Typeface.BOLD);
            distTv.setTextSize(15);
            distTv.setGravity(Gravity.END);

            topRow.addView(stepTv);
            topRow.addView(distTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(topRow);

            TextView nameTv = new TextView(this);
            nameTv.setText(stop.name);
            nameTv.setTextColor(Color.WHITE);
            nameTv.setTextSize(17);
            nameTv.setTypeface(null, Typeface.BOLD);
            nameTv.setPadding(0, dp(6), 0, dp(6));
            card.addView(nameTv);

            TextView coordTv = new TextView(this);
            coordTv.setText(String.format(Locale.US, "좌표: %.4f, %.4f", stop.lat, stop.lng));
            coordTv.setTextColor(Color.parseColor("#94A3B8"));
            coordTv.setTextSize(11);
            card.addView(coordTv);

            LinearLayout btnRow = new LinearLayout(this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            btnRowLp.topMargin = dp(8);

            Button gpsBtn = makeButton("📍 현위치로 보정", "#334155", "#E2E8F0");
            gpsBtn.setTextSize(11);
            gpsBtn.setOnClickListener(v -> {
                if (BusService.lastLat == 0.0) {
                    Toast.makeText(this, "먼저 [탑승 시작]을 눌러 GPS를 수신해 주세요.", Toast.LENGTH_SHORT).show();
                    return;
                }
                stop.lat = BusService.lastLat;
                stop.lng = BusService.lastLng;
                BusService.savePrefs(this);
                renderStopCards();
                Toast.makeText(this, stop.name + " 위치가 현재 좌표로 저장되었습니다.", Toast.LENGTH_SHORT).show();
            });

            Button editBtn = makeButton("✏️ 이름 수정", "#334155", "#E2E8F0");
            editBtn.setTextSize(11);
            editBtn.setOnClickListener(v -> showRenameDialog(stop));

            Button skipBtn = makeButton(stop.notified ? "재알림 켜기" : "건너뛰기", "#334155", "#E2E8F0");
            skipBtn.setTextSize(11);
            skipBtn.setOnClickListener(v -> {
                stop.notified = !stop.notified;
                renderStopCards();
            });

            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            bLp.rightMargin = dp(4);
            btnRow.addView(gpsBtn, bLp);
            btnRow.addView(editBtn, bLp);
            btnRow.addView(skipBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            card.addView(btnRow, btnRowLp);
            stopListContainer.addView(card, lp);
        }
        refreshStatusAndDistances();
    }

    private void refreshStatusAndDistances() {
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(dp(20));
        if (BusService.isRunning) {
            statusBadge.setText("백그라운드 추적 중 (홀드 작동)");
            statusBadge.setTextColor(Color.parseColor("#34D399"));
            badgeBg.setColor(Color.parseColor("#064E3B"));
            gpsAccText.setText(BusService.lastLat == 0.0 ? "GPS 위성 연결 중..." : "GPS 오차 ±" + BusService.lastAcc + "m");
        } else {
            statusBadge.setText("대기 중");
            statusBadge.setTextColor(Color.parseColor("#94A3B8"));
            badgeBg.setColor(Color.parseColor("#334155"));
            gpsAccText.setText("GPS 수신 대기");
        }
        statusBadge.setBackground(badgeBg);

        if (BusService.lastLat != 0.0) {
            BusService.StopItem[] stops = BusService.getCurrentStops();
            for (int i = 0; i < stops.length; i++) {
                TextView distTv = stopListContainer.findViewWithTag("dist_" + i);
                if (distTv != null) {
                    int d = BusService.getDistanceMeters(BusService.lastLat, BusService.lastLng, stops[i].lat, stops[i].lng);
                    distTv.setText(d >= 1000 ? String.format(Locale.US, "%.2f km", d / 1000.0) : d + " m");
                }
            }
        }
    }

    private void checkPermsAndStart() {
        List<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), 100);
        } else {
            startServiceTracking();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 100) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                startServiceTracking();
            } else {
                Toast.makeText(this, "위치 권한을 허용해야 버스 하차 알림이 작동합니다.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startServiceTracking() {
        Intent i = new Intent(this, BusService.class);
        i.setAction(BusService.ACTION_START);
        startForegroundService(i);
        refreshStatusAndDistances();
    }

    private void stopServiceTracking() {
        Intent i = new Intent(this, BusService.class);
        i.setAction(BusService.ACTION_STOP);
        startService(i);
        refreshStatusAndDistances();
    }

    private void simulateVoice(int idx) {
        Intent i = new Intent(this, BusService.class);
        i.setAction(BusService.ACTION_SIMULATE);
        i.putExtra("sim_idx", idx);
        startService(i);
    }

    private void requestBatteryExemption() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } else {
            Toast.makeText(this, "이미 배터리 제한 없음(최적화 제외)이 적용되어 있습니다!", Toast.LENGTH_SHORT).show();
        }
    }

    private void showRenameDialog(BusService.StopItem stop) {
        EditText input = new EditText(this);
        input.setText(stop.name);
        new AlertDialog.Builder(this)
                .setTitle("정류장 이름 수정")
                .setView(input)
                .setPositiveButton("저장", (d, w) -> {
                    stop.name = input.getText().toString().trim();
                    BusService.savePrefs(this);
                    renderStopCards();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void showRadiusDialog(Button btn) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(BusService.alertRadius));
        new AlertDialog.Builder(this)
                .setTitle("정류장 진입 감지 반경 (m)")
                .setView(input)
                .setPositiveButton("저장", (d, w) -> {
                    try {
                        BusService.alertRadius = Integer.parseInt(input.getText().toString().trim());
                        BusService.savePrefs(this);
                        btn.setText("반경 변경 (현재 " + BusService.alertRadius + "m)");
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private LinearLayout makeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        card.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1E293B"));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.parseColor("#334155"));
        card.setBackground(bg);
        return card;
    }

    private Button makeButton(String text, String bgHex, String textHex) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTypeface(null, Typeface.BOLD);
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        styleButton(b, bgHex, textHex);
        return b;
    }

    private void styleButton(Button b, String bgHex, String textHex) {
        b.setTextColor(Color.parseColor(textHex));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(bgHex));
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
    }

    private TextView makeSectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#94A3B8"));
        tv.setTextSize(13);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(14), 0, dp(8));
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        uiHandler.post(uiUpdater);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(uiUpdater);
    }
}
