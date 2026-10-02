package com.dsacms.sheikhrequests;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.dsacms.sheikhrequests.notifications.SheikhMessagingService;

public final class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 410;
    private View screenContainer;
    private String pendingRequestId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        screenContainer = findViewById(R.id.screen_container);
        ViewCompat.setOnApplyWindowInsetsListener(screenContainer, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
        pendingRequestId = getIntent().getStringExtra(SheikhMessagingService.EXTRA_REQUEST_ID);
        if (pendingRequestId == null || pendingRequestId.isBlank()) showLogin();
        else showRequestDetails(pendingRequestId);
        createNotificationChannel();
        requestNotificationPermissionIfNeeded();
        requestFcmTokenIfConfigured();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingRequestId = intent.getStringExtra(SheikhMessagingService.EXTRA_REQUEST_ID);
        if (pendingRequestId != null && !pendingRequestId.isBlank()) {
            showRequestDetails(pendingRequestId);
        }
    }

    private void showLogin() {
        View view = getLayoutInflater().inflate(R.layout.screen_login, null);
        screenContainer = replaceScreen(view);
        TextView message = view.findViewById(R.id.login_message);
        view.findViewById(R.id.login_button).setOnClickListener(v ->
                message.setText("سيُفعّل تسجيل الدخول بعد ربط التطبيق بخادم الموقع."));
        view.findViewById(R.id.login_notification_settings).setOnClickListener(v -> showSettings());
    }

    private void showRequests(String filter) {
        View view = getLayoutInflater().inflate(R.layout.screen_requests, null);
        replaceScreen(view);
        view.findViewById(R.id.settings_button).setOnClickListener(v -> showSettings());
        view.findViewById(R.id.filter_new).setOnClickListener(v -> showFilterMessage("جديدة"));
        view.findViewById(R.id.filter_all).setOnClickListener(v -> showFilterMessage("الكل"));
        view.findViewById(R.id.filter_contacted).setOnClickListener(v -> showFilterMessage("تم التواصل"));
        view.findViewById(R.id.filter_ignored).setOnClickListener(v -> showFilterMessage("متجاهلة"));
        if (filter != null) showFilterMessage(filter);
    }

    private void showFilterMessage(String filter) {
        Toast.makeText(this, "ستظهر الطلبات " + filter + " بعد ربط التطبيق بالخادم.", Toast.LENGTH_SHORT).show();
    }

    private void showRequestDetails(String requestId) {
        View view = getLayoutInflater().inflate(R.layout.screen_request_detail, null);
        replaceScreen(view);
        TextView question = view.findViewById(R.id.detail_question);
        question.setText("تفاصيل الطلب رقم " + requestId + " ستظهر بعد ربط التطبيق بالخادم.");
        view.findViewById(R.id.detail_back).setOnClickListener(v -> showRequests(null));
        view.findViewById(R.id.whatsapp_button).setEnabled(false);
        view.findViewById(R.id.ignore_button).setEnabled(false);
        view.findViewById(R.id.viewed_button).setEnabled(false);
    }

    private void showSettings() {
        View view = getLayoutInflater().inflate(R.layout.screen_settings, null);
        replaceScreen(view);
        TextView status = view.findViewById(R.id.notification_status);
        refreshNotificationStatus(status);
        view.findViewById(R.id.settings_back).setOnClickListener(v -> showLogin());
        view.findViewById(R.id.enable_notifications).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
            } else {
                openNotificationSettings();
            }
        });
        view.findViewById(R.id.open_notification_settings).setOnClickListener(v -> openNotificationSettings());
    }

    private void refreshNotificationStatus(TextView status) {
        boolean enabled = NotificationManagerCompat.from(this).areNotificationsEnabled();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            enabled = enabled && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        status.setText(enabled ? "🟢 الإشعارات مفعلة" : "🔴 الإشعارات غير مفعلة");
    }

    private void openNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        startActivity(intent);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    private void requestFcmTokenIfConfigured() {
        if (FirebaseApp.getApps(this).isEmpty()) return;
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                // Token registration belongs to the authenticated server integration phase.
                // Never print or expose the token in the UI or logs.
            }
        });
    }

    private View replaceScreen(View view) {
        android.widget.FrameLayout container = findViewById(R.id.screen_container);
        container.removeAllViews();
        container.addView(view);
        return view;
    }

    private void createNotificationChannel() {
        SheikhMessagingService.createNotificationChannel(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (screenContainer != null && screenContainer.findViewById(R.id.notification_status) instanceof TextView status) {
            refreshNotificationStatus(status);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST && grantResults.length > 0
                && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "الإشعارات متوقفة. يمكنك تفعيلها من إعدادات التطبيق.", Toast.LENGTH_LONG).show();
        }
    }
}
