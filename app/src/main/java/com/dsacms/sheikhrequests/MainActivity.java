package com.dsacms.sheikhrequests;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.dsacms.sheikhrequests.notifications.SheikhMessagingService;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.android.gms.tasks.Tasks;

import org.json.JSONArray;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 410;
    private final SiteApiClient apiClient = new SiteApiClient();
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private View screenContainer;
    private SecureSessionStore sessionStore;
    private String sessionToken;
    private String pendingRequestId;
    private String currentFilter = "pending";
    private SiteApiClient.ContactRequest currentRequest;
    private TextView detailStatus;

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
        sessionStore = new SecureSessionStore(this);
        try {
            sessionToken = sessionStore.read();
        } catch (Exception ignored) {
            sessionStore.clear();
            sessionToken = null;
        }
        if (sessionToken == null || sessionToken.isBlank()) {
            showLogin();
        } else if (pendingRequestId != null && !pendingRequestId.isBlank()) {
            showRequestDetails(pendingRequestId);
        } else {
            showRequests("pending");
        }
        SheikhMessagingService.createNotificationChannel(this);
        requestNotificationPermissionIfNeeded();
        requestFcmTokenIfConfigured();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingRequestId = intent.getStringExtra(SheikhMessagingService.EXTRA_REQUEST_ID);
        if (sessionToken != null && pendingRequestId != null && !pendingRequestId.isBlank()) {
            showRequestDetails(pendingRequestId);
        }
    }

    private void showLogin() {
        View view = getLayoutInflater().inflate(R.layout.screen_login, null);
        replaceScreen(view);
        TextView message = view.findViewById(R.id.login_message);
        EditText username = view.findViewById(R.id.username);
        EditText password = view.findViewById(R.id.password);
        Button login = view.findViewById(R.id.login_button);
        login.setOnClickListener(v -> {
            String userValue = username.getText().toString().trim();
            String passwordValue = password.getText().toString();
            if (userValue.isEmpty() || passwordValue.isEmpty()) {
                message.setText("أدخل اسم المستخدم وكلمة المرور.");
                return;
            }
            login.setEnabled(false);
            message.setText("جارٍ تسجيل الدخول…");
            networkExecutor.execute(() -> {
                try {
                    SiteApiClient.LoginResult result = apiClient.login(userValue, passwordValue);
                    sessionStore.save(result.token);
                    runOnUiThread(() -> {
                        sessionToken = result.token;
                        password.setText("");
                        requestFcmTokenIfConfigured();
                        if (pendingRequestId != null && !pendingRequestId.isBlank()) {
                            showRequestDetails(pendingRequestId);
                        } else {
                            showRequests("pending");
                        }
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        login.setEnabled(true);
                        message.setText(error instanceof SiteApiClient.ApiException
                                ? error.getMessage() : "تعذر الاتصال بالخادم. تحقق من الإنترنت وحاول مجددًا.");
                    });
                }
            });
        });
        view.findViewById(R.id.login_notification_settings).setOnClickListener(v -> showSettings());
    }

    private void showRequests(String filter) {
        currentFilter = filter == null ? "" : filter;
        View view = getLayoutInflater().inflate(R.layout.screen_requests, null);
        replaceScreen(view);
        view.findViewById(R.id.settings_button).setOnClickListener(v -> showSettings());
        view.findViewById(R.id.refresh_requests).setOnClickListener(v -> loadContactRequests(view));
        view.findViewById(R.id.filter_new).setOnClickListener(v -> showRequests("pending"));
        view.findViewById(R.id.filter_all).setOnClickListener(v -> showRequests("all"));
        view.findViewById(R.id.filter_contacted).setOnClickListener(v -> showRequests("answered"));
        TextView archived = view.findViewById(R.id.filter_ignored);
        archived.setText("مؤرشفة");
        archived.setVisibility(View.VISIBLE);
        archived.setOnClickListener(v -> showRequests("archived"));
        loadContactRequests(view);
    }

    private void loadContactRequests(View root) {
        View emptyState = root.findViewById(R.id.empty_state);
        View listScroll = root.findViewById(R.id.request_list_scroll);
        TextView emptyMessage = root.findViewById(R.id.empty_message);
        TextView emptySubtitle = root.findViewById(R.id.empty_subtitle);
        LinearLayout list = root.findViewById(R.id.request_list);
        emptyState.setVisibility(View.VISIBLE);
        listScroll.setVisibility(View.GONE);
        emptyMessage.setText("جارٍ تحميل الأسئلة…");
        emptySubtitle.setText("يتم جلب الأسئلة العامة الخاصة بحساب الشيخ بأمان.");

        networkExecutor.execute(() -> {
            try {
                JSONArray items = apiClient.getContactRequests(sessionToken, currentFilter);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    list.removeAllViews();
                    if (items.length() == 0) {
                        emptyMessage.setText("لا توجد أسئلة في هذا القسم.");
                        emptySubtitle.setText("ستظهر الأسئلة الجديدة هنا فور إرسالها من الموقع.");
                        emptyState.setVisibility(View.VISIBLE);
                        listScroll.setVisibility(View.GONE);
                        return;
                    }
                    for (int i = 0; i < items.length(); i++) {
                        SiteApiClient.ContactRequest item = SiteApiClient.ContactRequest.from(items.optJSONObject(i));
                        list.addView(createRequestCard(item));
                    }
                    emptyState.setVisibility(View.GONE);
                    listScroll.setVisibility(View.VISIBLE);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    if (handleExpiredSession(error)) return;
                    emptyMessage.setText("تعذر تحميل الأسئلة.");
                    emptySubtitle.setText("تحقق من اتصال الإنترنت ثم اضغط «تحديث الأسئلة».");
                    emptyState.setVisibility(View.VISIBLE);
                    listScroll.setVisibility(View.GONE);
                });
            }
        });
    }

    private View createRequestCard(SiteApiClient.ContactRequest item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackgroundResource(R.drawable.request_card_background);
        card.setElevation(dp(2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(params);

        TextView name = cardText(item.name, 20, true, 0xFF14253E);
        TextView state = cardText(statusLabel(item.status), 15, true, 0xFF80622D);
        TextView date = cardText(dateLabel(item.createdAt), 14, false, 0xFF5A6A7A);
        String preview = item.question.length() > 140 ? item.question.substring(0, 140) + "…" : item.question;
        TextView question = cardText("السؤال: " + preview, 15, false, 0xFF293746);
        question.setMaxLines(3);
        card.addView(name);
        card.addView(state);
        card.addView(date);
        card.addView(question);
        card.setContentDescription("طلب من " + item.name + ". " + statusLabel(item.status));
        card.setOnClickListener(v -> showRequestDetails(item.id));
        return card;
    }

    private TextView cardText(String value, int size, boolean bold, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setGravity(Gravity.START);
        if (bold) text.setTypeface(null, android.graphics.Typeface.BOLD);
        text.setPadding(0, dp(2), 0, dp(5));
        return text;
    }

    private void showRequestDetails(String requestId) {
        View view = getLayoutInflater().inflate(R.layout.screen_request_detail, null);
        replaceScreen(view);
        currentRequest = null;
        detailStatus = view.findViewById(R.id.detail_status);
        TextView name = view.findViewById(R.id.detail_name);
        TextView question = view.findViewById(R.id.detail_question);
        TextView date = view.findViewById(R.id.detail_date);
        TextView heading = view.findViewById(R.id.detail_heading);
        EditText answer = view.findViewById(R.id.detail_answer);
        Button publish = view.findViewById(R.id.answer_button);
        Button archive = view.findViewById(R.id.archive_question_button);
        Button delete = view.findViewById(R.id.delete_question_button);
        heading.setText("السؤال العام");
        name.setText("السائل: جارٍ التحميل…");
        question.setText("السؤال: —");
        date.setText("التاريخ: —");
        detailStatus.setText("الحالة: جارٍ التحميل…");
        publish.setEnabled(false);
        view.findViewById(R.id.detail_back).setOnClickListener(v -> showRequests(currentFilter));

        networkExecutor.execute(() -> {
            try {
                SiteApiClient.ContactRequest request = apiClient.getContactRequest(sessionToken, requestId);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    currentRequest = request;
                    name.setText("السائل: " + request.name + " · " + request.category);
                    question.setText("السؤال: " + request.question);
                    date.setText("التاريخ: " + dateLabel(request.createdAt));
                    renderDetailStatus(request.status);
                    answer.setText(request.answer);
                    publish.setText("answered".equals(request.status) ? "حفظ تعديل الإجابة" : "نشر الإجابة");
                    boolean isArchived = "archived".equals(request.status);
                    publish.setEnabled(!isArchived);
                    archive.setText(isArchived ? "إرجاع إلى الأسئلة" : "أرشفة السؤال");
                    archive.setEnabled(true);
                    delete.setEnabled(true);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (handleExpiredSession(error)) return;
                    heading.setText(error instanceof SiteApiClient.ApiException
                            && ((SiteApiClient.ApiException) error).statusCode == 404
                            ? "السؤال غير موجود" : "تعذر تحميل تفاصيل السؤال");
                    question.setText("تحقق من اتصال الإنترنت ثم ارجع إلى القائمة وحاول مجددًا.");
                });
            }
        });

        publish.setOnClickListener(v -> publishAnswer(requestId, answer.getText().toString()));
        archive.setOnClickListener(v -> archiveQuestion(requestId,
                currentRequest != null && "archived".equals(currentRequest.status)));
        delete.setOnClickListener(v -> confirmDeleteQuestion(requestId));
    }

    private void archiveQuestion(String requestId, boolean restore) {
        networkExecutor.execute(() -> {
            try {
                apiClient.archiveQuestion(sessionToken, requestId, restore);
                runOnUiThread(() -> {
                    Toast.makeText(this, restore ? "تم إرجاع السؤال." : "تمت أرشفة السؤال.", Toast.LENGTH_SHORT).show();
                    showRequests(currentFilter);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (handleExpiredSession(error)) return;
                    Toast.makeText(this, error instanceof SiteApiClient.ApiException
                            ? error.getMessage() : "تعذر حفظ حالة السؤال. تحقق من الاتصال وحاول مجددًا.", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void confirmDeleteQuestion(String requestId) {
        new AlertDialog.Builder(this)
                .setTitle("حذف السؤال")
                .setMessage("سيتم حذف السؤال وإجابته نهائياً. هل تريد المتابعة؟")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حذف", (dialog, which) -> networkExecutor.execute(() -> {
                    try {
                        apiClient.deleteQuestion(sessionToken, requestId);
                        runOnUiThread(() -> {
                            Toast.makeText(this, "تم حذف السؤال.", Toast.LENGTH_SHORT).show();
                            showRequests(currentFilter);
                        });
                    } catch (Exception error) {
                        runOnUiThread(() -> {
                            if (handleExpiredSession(error)) return;
                            Toast.makeText(this, error instanceof SiteApiClient.ApiException
                                    ? error.getMessage() : "تعذر حذف السؤال. تحقق من الاتصال وحاول مجددًا.", Toast.LENGTH_LONG).show();
                        });
                    }
                })).show();
    }

    private void publishAnswer(String requestId, String answer) {
        String value = answer.trim();
        if (value.length() < 2) {
            Toast.makeText(this, "اكتب الإجابة أولاً.", Toast.LENGTH_SHORT).show();
            return;
        }
        networkExecutor.execute(() -> {
            try {
                apiClient.answerQuestion(sessionToken, requestId, value);
                runOnUiThread(() -> {
                    if (detailStatus != null) renderDetailStatus("answered");
                    Toast.makeText(this, "تم نشر الإجابة.", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (handleExpiredSession(error)) return;
                    Toast.makeText(this, "تعذر نشر الإجابة. حاول مرة أخرى.", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showSettings() {
        View view = getLayoutInflater().inflate(R.layout.screen_settings, null);
        replaceScreen(view);
        TextView status = view.findViewById(R.id.notification_status);
        TextView registration = view.findViewById(R.id.push_registration_status);
        refreshNotificationStatus(status);
        refreshPushRegistrationStatus(registration);
        view.findViewById(R.id.settings_back).setOnClickListener(v -> showRequests(currentFilter));
        view.findViewById(R.id.logout_button).setOnClickListener(v -> logout());
        view.findViewById(R.id.test_notification).setOnClickListener(v -> testNotification());
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

    private void testNotification() {
        networkExecutor.execute(() -> {
            try {
                org.json.JSONObject result = apiClient.testNotification(sessionToken);
                int registered = result.optInt("registered", 0);
                int accepted = result.optInt("accepted", 0);
                org.json.JSONArray failures = result.optJSONArray("failures");
                String message;
                if (registered == 0) {
                    message = "لا يوجد جهاز مسجل عند السيرفر. أعد تسجيل الدخول ثم جرّب.";
                } else if (accepted > 0) {
                    message = "قبل Firebase إرسال الإشعار لهذا الجهاز. راجع شريط الإشعارات.";
                } else {
                    String code = failures == null || failures.length() == 0 ? "غير معروف" : failures.optString(0);
                    message = "Firebase رفض الإشعار: " + code;
                }
                String finalMessage = message;
                runOnUiThread(() -> Toast.makeText(this, finalMessage, Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (handleExpiredSession(error)) return;
                    Toast.makeText(this, error instanceof SiteApiClient.ApiException
                            ? error.getMessage() : "تعذر اختبار الإشعار. تحقق من الاتصال.", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void logout() {
        String oldSession = sessionToken;
        sessionToken = null;
        sessionStore.clear();
        showLogin();
        if (oldSession == null) return;
        networkExecutor.execute(() -> {
            try {
                if (!FirebaseApp.getApps(this).isEmpty()) {
                    String fcmToken = Tasks.await(FirebaseMessaging.getInstance().getToken(), 10, TimeUnit.SECONDS);
                    apiClient.unregisterPushToken(oldSession, fcmToken);
                }
            } catch (Exception ignored) {
                // Continue to revoke the server session even if push-token cleanup fails.
            }
            try {
                apiClient.logout(oldSession);
            } catch (Exception ignored) {
                // The session is cleared locally; an unreachable backend can expire it later.
            }
        });
    }

    private void requestFcmTokenIfConfigured() {
        if (FirebaseApp.getApps(this).isEmpty()) return;
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null || sessionToken == null) return;
            String token = task.getResult();
            String authenticatedSession = sessionToken;
            networkExecutor.execute(() -> {
                try {
                    apiClient.registerPushToken(authenticatedSession, token);
                    getSharedPreferences("push_status", MODE_PRIVATE).edit().putBoolean("registered", true).apply();
                    runOnUiThread(() -> {
                        if (screenContainer != null && screenContainer.findViewById(R.id.push_registration_status) instanceof TextView status) {
                            status.setText("🟢 هذا الجهاز مسجل لتنبيهات الطلبات");
                        }
                    });
                } catch (Exception ignored) {
                    getSharedPreferences("push_status", MODE_PRIVATE).edit().putBoolean("registered", false).apply();
                }
            });
        });
    }

    private void refreshPushRegistrationStatus(TextView status) {
        boolean registered = !FirebaseApp.getApps(this).isEmpty()
                && getSharedPreferences("push_status", MODE_PRIVATE).getBoolean("registered", false);
        status.setText(registered ? "🟢 هذا الجهاز مسجل لتنبيهات الطلبات"
                : "🟠 يحتاج التطبيق إلى إعداد Firebase واتصال بالخادم لتسجيل هذا الجهاز");
    }

    private void refreshNotificationStatus(TextView status) {
        boolean enabled = NotificationManagerCompat.from(this).areNotificationsEnabled();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            enabled = enabled && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        status.setText(enabled ? "🟢 الإشعارات مفعلة في الهاتف" : "🔴 الإشعارات غير مفعلة في الهاتف");
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

    private boolean handleExpiredSession(Exception error) {
        if (error instanceof SiteApiClient.ApiException
                && ((SiteApiClient.ApiException) error).statusCode == 401) {
            sessionStore.clear();
            sessionToken = null;
            showLogin();
            Toast.makeText(this, "انتهت الجلسة. سجّل الدخول مرة أخرى.", Toast.LENGTH_LONG).show();
            return true;
        }
        return false;
    }

    private void renderDetailStatus(String status) {
        if (detailStatus != null) detailStatus.setText("الحالة: " + statusLabel(status));
    }

    private String statusLabel(String status) {
        return "answered".equals(status) ? "تمت الإجابة" : "بانتظار الإجابة";
    }

    private String dateLabel(String value) {
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX"};
        for (String pattern : patterns) {
            try {
                Date parsed = new SimpleDateFormat(pattern, Locale.US).parse(value);
                if (parsed != null) return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT,
                        new Locale("ar")).format(parsed);
            } catch (ParseException ignored) {
                // Try the timestamp format without fractional seconds.
            }
        }
        return value == null || value.isBlank() ? "غير محدد" : value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private View replaceScreen(View view) {
        android.widget.FrameLayout container = findViewById(R.id.screen_container);
        container.removeAllViews();
        container.addView(view);
        screenContainer = view;
        return view;
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

    @Override
    protected void onDestroy() {
        networkExecutor.shutdownNow();
        super.onDestroy();
    }
}
