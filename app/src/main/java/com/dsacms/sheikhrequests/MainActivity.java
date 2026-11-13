package com.dsacms.sheikhrequests;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.media.MediaRecorder;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ProgressBar;
import android.database.Cursor;
import android.provider.OpenableColumns;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;

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
    private static final int AUDIO_PERMISSION_REQUEST = 411;
    private static final int AUDIO_PICK_REQUEST = 412;
    private static final long MAX_AUDIO_BYTES = 20L * 1024 * 1024;
    private static final java.util.Set<String> AUDIO_EXTENSIONS = new java.util.HashSet<>(java.util.Arrays.asList("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "3gp"));
    private final SiteApiClient apiClient = new SiteApiClient();
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private View screenContainer;
    private SecureSessionStore sessionStore;
    private String sessionToken;
    private String pendingRequestId;
    private String currentFilter = "pending";
    private SiteApiClient.ContactRequest currentRequest;
    private TextView detailStatus;
    private Uri selectedAudioUri;
    private String selectedAudioName = "";
    private String selectedAudioMime = "";
    private String existingAudioUrl = "";
    private boolean clearAudioRequested;
    private boolean awaitingRecordPermission;
    private MediaRecorder mediaRecorder;
    private File recordedAudioFile;
    private TextView audioAttachmentStatus;
    private Button recordAudioButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().hide();
        getWindow().setStatusBarColor(getColorCompat(R.color.site_navy));
        getWindow().setNavigationBarColor(getColorCompat(R.color.site_cream));
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
        view.findViewById(R.id.filter_ignored).setOnClickListener(v -> showRequests("archived"));
        updateFilterStyles(view, -1);
        view.findViewById(R.id.request_list_scroll).setOnTouchListener(new View.OnTouchListener() {
            private float startY;
            @Override public boolean onTouch(View touched, android.view.MotionEvent event) {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) startY = event.getY();
                else if (event.getAction() == android.view.MotionEvent.ACTION_UP
                        && ((android.widget.ScrollView) touched).getScrollY() == 0
                        && event.getY() - startY > dp(72)) {
                    loadContactRequests(view);
                }
                return false;
            }
        });
        loadContactRequests(view);
    }

    private void loadContactRequests(View root) {
        String filter = currentFilter;
        View refresh = root.findViewById(R.id.refresh_requests);
        if (refresh != null) refresh.animate().rotationBy(360f).setDuration(450).start();
        View emptyState = root.findViewById(R.id.empty_state);
        View listScroll = root.findViewById(R.id.request_list_scroll);
        TextView emptyMessage = root.findViewById(R.id.empty_message);
        TextView emptySubtitle = root.findViewById(R.id.empty_subtitle);
        LinearLayout list = root.findViewById(R.id.request_list);
        JSONArray cached = readCachedRequests(filter);
        if (cached != null) {
            displayRequests(root, cached, true);
        } else {
            emptyState.setVisibility(View.VISIBLE);
            listScroll.setVisibility(View.GONE);
            emptyMessage.setText("جارٍ تحميل الأسئلة…");
            emptySubtitle.setText("تُحمّل الأسئلة العامة لحساب الشيخ.");
        }

        networkExecutor.execute(() -> {
            try {
                JSONArray items = apiClient.getContactRequests(sessionToken, filter);
                getSharedPreferences("request_cache", MODE_PRIVATE).edit()
                        .putString(filter, items.toString()).apply();
                runOnUiThread(() -> {
                    if (isFinishing() || screenContainer != root || !filter.equals(currentFilter)) return;
                    displayRequests(root, items, false);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || screenContainer != root || !filter.equals(currentFilter)) return;
                    if (handleExpiredSession(error)) return;
                    if (cached == null) {
                        emptyMessage.setText("تعذر تحميل الأسئلة.");
                        emptySubtitle.setText(error instanceof SiteApiClient.ApiException
                                ? error.getMessage() : "تحقق من الاتصال ثم حاول التحديث مرة أخرى.");
                        emptyState.setVisibility(View.VISIBLE);
                        listScroll.setVisibility(View.GONE);
                    } else {
                        Toast.makeText(this, "تعذر تحديث القائمة؛ المعروض هو آخر تحميل محفوظ.", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private JSONArray readCachedRequests(String filter) {
        String value = getSharedPreferences("request_cache", MODE_PRIVATE).getString(filter, null);
        if (value == null || value.isBlank()) return null;
        try { return new JSONArray(value); } catch (Exception ignored) { return null; }
    }

    private void displayRequests(View root, JSONArray items, boolean cached) {
        if (isFinishing() || screenContainer != root) return;
        View emptyState = root.findViewById(R.id.empty_state);
        View listScroll = root.findViewById(R.id.request_list_scroll);
        TextView emptyMessage = root.findViewById(R.id.empty_message);
        TextView emptySubtitle = root.findViewById(R.id.empty_subtitle);
        LinearLayout list = root.findViewById(R.id.request_list);
        list.removeAllViews();
        for (int i = 0; i < items.length(); i++) {
            SiteApiClient.ContactRequest item = SiteApiClient.ContactRequest.from(items.optJSONObject(i));
            list.addView(createRequestCard(item));
        }
        updateFilterStyles(root, "pending".equals(currentFilter) ? items.length() : -1);
        if (items.length() == 0) {
            emptyMessage.setText("لا توجد أسئلة في هذا القسم.");
            emptySubtitle.setText(cached ? "هذه آخر قائمة محفوظة على الجهاز." : "ستظهر الأسئلة الجديدة هنا فور إرسالها.");
            emptyState.setVisibility(View.VISIBLE);
            listScroll.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            listScroll.setVisibility(View.VISIBLE);
        }
    }

    private void updateFilterStyles(View root, int pendingCount) {
        String[] filters = {"pending", "all", "answered", "archived"};
        int[] ids = {R.id.filter_new, R.id.filter_all, R.id.filter_contacted, R.id.filter_ignored};
        String[] labels = {pendingCount >= 0 ? "بانتظار الإجابة (" + pendingCount + ")" : "بانتظار الإجابة",
                "كل الأسئلة", "تمت الإجابة", "مؤرشفة"};
        for (int i = 0; i < ids.length; i++) {
            TextView chip = root.findViewById(ids[i]);
            boolean active = filters[i].equals(currentFilter);
            chip.setText(labels[i]);
            chip.setBackgroundResource(active ? R.drawable.filter_chip_active : R.drawable.filter_chip_inactive);
            chip.setTextColor(active ? Color.WHITE : getColorCompat(R.color.site_navy));
            chip.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private View createRequestCard(SiteApiClient.ContactRequest item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundResource(R.drawable.request_card_background);
        card.setClipToOutline(true);
        card.setElevation(dp(3));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(14));
        card.setLayoutParams(params);

        View accent = new View(this);
        accent.setBackgroundColor(getColorCompat(R.color.site_gold));
        card.addView(accent, new LinearLayout.LayoutParams(dp(4), -1));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(13), dp(12), dp(13), dp(12));
        card.addView(content, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout identity = new LinearLayout(this);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        identity.setOrientation(LinearLayout.HORIZONTAL);
        ImageView avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.ic_person_white);
        avatar.setBackgroundResource(R.drawable.avatar_circle_background);
        avatar.setPadding(dp(8), dp(8), dp(8), dp(8));
        identity.addView(avatar, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView name = cardText(item.name, 17, true, 0xFF0F2137);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1);
        nameParams.setMargins(dp(9), 0, dp(7), 0);
        identity.addView(name, nameParams);
        TextView state = new TextView(this);
        state.setText(statusLabel(item.status));
        state.setTextSize(12);
        state.setTypeface(null, android.graphics.Typeface.BOLD);
        state.setTextColor("pending".equals(item.status) ? 0xFF7A5313 : 0xFF0F2137);
        state.setBackgroundResource("pending".equals(item.status) ? R.drawable.pending_badge_background : R.drawable.filter_chip_inactive);
        state.setPadding(dp(10), dp(5), dp(10), dp(5));
        identity.addView(state, new LinearLayout.LayoutParams(-2, -2));
        content.addView(identity);

        TextView question = cardText("السؤال: " + item.question, 15, false, 0xFF293746);
        question.setBackgroundResource(R.drawable.question_quote_background);
        question.setPadding(dp(12), dp(10), dp(12), dp(10));
        question.setMaxLines(3);
        question.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams questionParams = new LinearLayout.LayoutParams(-1, -2);
        questionParams.setMargins(0, dp(11), 0, dp(9));
        content.addView(question, questionParams);

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        TextView date = cardText(dateLabel(item.createdAt), 12, false, 0xFF64748B);
        date.setSingleLine(true);
        date.setEllipsize(android.text.TextUtils.TruncateAt.END);
        footer.addView(date, new LinearLayout.LayoutParams(0, -2, 1));
        TextView open = cardText("عرض والرد ←", 14, true, 0xFFC89D54);
        footer.addView(open);
        content.addView(footer);
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
        text.setPadding(0, dp(1), 0, dp(1));
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
        Button recordAudio = view.findViewById(R.id.record_audio_button);
        Button pickAudio = view.findViewById(R.id.pick_audio_button);
        Button clearAudio = view.findViewById(R.id.clear_audio_button);
        audioAttachmentStatus = view.findViewById(R.id.audio_attachment_status);
        recordAudioButton = recordAudio;
        selectedAudioUri = null;
        selectedAudioName = "";
        selectedAudioMime = "";
        existingAudioUrl = "";
        clearAudioRequested = false;
        renderAudioAttachment();
        Button publish = view.findViewById(R.id.answer_button);
        ProgressBar audioUploadProgress = view.findViewById(R.id.audio_upload_progress);
        Button archive = view.findViewById(R.id.archive_question_button);
        Button delete = view.findViewById(R.id.delete_question_button);
        heading.setText("تفاصيل السؤال");
        name.setText("السائل: جارٍ التحميل…");
        question.setText("السؤال: —");
        date.setText("التاريخ: —");
        detailStatus.setText("الحالة: جارٍ التحميل…");
        publish.setEnabled(false);
        archive.setEnabled(false);
        delete.setEnabled(false);
        view.findViewById(R.id.detail_back).setOnClickListener(v -> showRequests(currentFilter));

        networkExecutor.execute(() -> {
            try {
                SiteApiClient.ContactRequest request = apiClient.getContactRequest(sessionToken, requestId);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    currentRequest = request;
                    name.setText("السائل: " + request.name + " • " + request.category);
                    question.setText(request.question);
                    date.setText("التاريخ: " + dateLabel(request.createdAt));
                    renderDetailStatus(request.status);
                    answer.setText(request.answer);
                    existingAudioUrl = request.answerAudioUrl;
                    renderAudioAttachment();
                    setPublishButtonText(publish, "answered".equals(request.status));
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

        publish.setOnClickListener(v -> publishAnswer(requestId, answer.getText().toString(), publish, audioUploadProgress));
        recordAudio.setOnClickListener(v -> { if (mediaRecorder == null) beginAudioRecording(); else finishAudioRecording(); });
        pickAudio.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/*");
            startActivityForResult(intent, AUDIO_PICK_REQUEST);
        });
        clearAudio.setOnClickListener(v -> {
            selectedAudioUri = null; selectedAudioName = ""; selectedAudioMime = "";
            if (recordedAudioFile != null) { recordedAudioFile.delete(); recordedAudioFile = null; }
            existingAudioUrl = ""; clearAudioRequested = true; renderAudioAttachment();
        });
        audioAttachmentStatus.setOnClickListener(v -> {
            if (existingAudioUrl != null && !existingAudioUrl.isBlank() && selectedAudioUri == null) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(existingAudioUrl))); }
                catch (Exception ignored) { Toast.makeText(this, "تعذر فتح التسجيل.", Toast.LENGTH_SHORT).show(); }
            }
        });
        archive.setOnClickListener(v -> archiveQuestion(requestId,
                currentRequest != null && "archived".equals(currentRequest.status)));
        delete.setOnClickListener(v -> confirmDeleteQuestion(requestId));
    }

    private void archiveQuestion(String requestId, boolean restore) {
        networkExecutor.execute(() -> {
            try {
                apiClient.archiveQuestion(sessionToken, requestId, restore);
                runOnUiThread(() -> {
                    invalidateRequestCache();
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
                            invalidateRequestCache();
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

    private void publishAnswer(String requestId, String answer, Button publishButton, ProgressBar uploadProgress) {
        String value = answer.trim();
        if (mediaRecorder != null) { Toast.makeText(this, "أوقف التسجيل أولاً.", Toast.LENGTH_SHORT).show(); return; }
        if (value.length() < 2 && selectedAudioUri == null && (existingAudioUrl == null || existingAudioUrl.isBlank() || clearAudioRequested)) {
            Toast.makeText(this, "اكتب الإجابة أو أرفق تسجيلاً صوتياً.", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean hasAudioUpload = selectedAudioUri != null;
        publishButton.setEnabled(false);
        if (hasAudioUpload) {
            uploadProgress.setProgress(0);
            uploadProgress.setVisibility(View.VISIBLE);
            audioAttachmentStatus.setText("جارٍ تجهيز التسجيل للرفع…");
        }
        networkExecutor.execute(() -> {
            try {
                String audioUrl = clearAudioRequested ? null : existingAudioUrl;
                if (selectedAudioUri != null) {
                    byte[] audio = readSelectedAudio(selectedAudioUri);
                    audioUrl = apiClient.uploadAnswerAudio(sessionToken, requestId, audio, selectedAudioName,
                            selectedAudioMime, (uploaded, total) -> runOnUiThread(() -> {
                                if (uploadProgress.getVisibility() == View.VISIBLE) {
                                    uploadProgress.setProgress((int) (uploaded * 100L / total));
                                    audioAttachmentStatus.setText("جارٍ رفع التسجيل إلى Cloudflare R2… "
                                            + (uploaded * 100L / total) + "٪ (" + android.text.format.Formatter.formatShortFileSize(this, uploaded)
                                            + " / " + android.text.format.Formatter.formatShortFileSize(this, total) + ")");
                                }
                            }));
                    existingAudioUrl = audioUrl;
                    selectedAudioUri = null;
                    selectedAudioName = "";
                }
                apiClient.answerQuestion(sessionToken, requestId, value, audioUrl);
                runOnUiThread(() -> {
                    invalidateRequestCache();
                    if (detailStatus != null) renderDetailStatus("answered");
                    Toast.makeText(this, "تم نشر الإجابة.", Toast.LENGTH_SHORT).show();
                    clearAudioRequested = false;
                    uploadProgress.setVisibility(View.GONE);
                    publishButton.setEnabled(true);
                    renderAudioAttachment();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    uploadProgress.setVisibility(View.GONE);
                    publishButton.setEnabled(true);
                    if (handleExpiredSession(error)) return;
                    Toast.makeText(this, error instanceof SiteApiClient.ApiException ? error.getMessage() : "تعذر نشر الإجابة أو رفع التسجيل. حاول مرة أخرى.", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void beginAudioRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            awaitingRecordPermission = true;
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST);
            return;
        }
        try {
            File folder = new File(getCacheDir(), "answer-audio");
            if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("تعذر إنشاء ملف التسجيل");
            recordedAudioFile = File.createTempFile("answer-", ".m4a", folder);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) mediaRecorder = new MediaRecorder(this);
            else mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setAudioEncodingBitRate(96000);
            mediaRecorder.setMaxDuration(5 * 60 * 1000);
            mediaRecorder.setMaxFileSize(MAX_AUDIO_BYTES);
            mediaRecorder.setOutputFile(recordedAudioFile.getAbsolutePath());
            mediaRecorder.setOnInfoListener((recorder, what, extra) -> { if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) finishAudioRecording(); });
            mediaRecorder.prepare();
            mediaRecorder.start();
            recordAudioButton.setText("إيقاف التسجيل");
            audioAttachmentStatus.setText("جارٍ تسجيل الإجابة… الحد 5 دقائق و20 ميجابايت");
        } catch (Exception error) {
            releaseRecorder();
            if (recordedAudioFile != null) { recordedAudioFile.delete(); recordedAudioFile = null; }
            if (recordAudioButton != null) recordAudioButton.setText("تسجيل صوتي");
            if (audioAttachmentStatus != null) audioAttachmentStatus.setText("لا يوجد تسجيل صوتي مرفق");
            Toast.makeText(this, "تعذر بدء التسجيل. تحقق من إذن الميكروفون.", Toast.LENGTH_LONG).show();
        }
    }

    private void finishAudioRecording() {
        if (mediaRecorder == null) return;
        try { mediaRecorder.stop(); } catch (RuntimeException ignored) { if (recordedAudioFile != null) recordedAudioFile.delete(); recordedAudioFile = null; }
        releaseRecorder();
        if (recordAudioButton != null) recordAudioButton.setText("تسجيل صوتي");
        if (recordedAudioFile != null && recordedAudioFile.exists() && recordedAudioFile.length() > 0 && recordedAudioFile.length() <= MAX_AUDIO_BYTES) {
            selectedAudioUri = Uri.fromFile(recordedAudioFile);
            selectedAudioName = recordedAudioFile.getName();
            selectedAudioMime = "audio/mp4";
            existingAudioUrl = ""; clearAudioRequested = false;
        }
        renderAudioAttachment();
    }

    private void releaseRecorder() {
        if (mediaRecorder != null) { try { mediaRecorder.reset(); } catch (Exception ignored) {} try { mediaRecorder.release(); } catch (Exception ignored) {} mediaRecorder = null; }
    }

    private void renderAudioAttachment() {
        if (audioAttachmentStatus == null) return;
        if (selectedAudioUri != null) audioAttachmentStatus.setText("التسجيل المرفق: " + selectedAudioName + " • " + selectedAudioMime);
        else if (existingAudioUrl != null && !existingAudioUrl.isBlank() && !clearAudioRequested) audioAttachmentStatus.setText("يوجد تسجيل صوتي منشور — اضغط للمعاينة");
        else audioAttachmentStatus.setText("لا يوجد تسجيل صوتي مرفق");
    }

    private byte[] readSelectedAudio(Uri uri) throws Exception {
        try (InputStream input = "file".equals(uri.getScheme()) ? new FileInputStream(new File(uri.getPath())) : getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalArgumentException("تعذر قراءة ملف الصوت.");
            byte[] buffer = new byte[8192]; int count; long total = 0;
            while ((count = input.read(buffer)) != -1) { total += count; if (total > MAX_AUDIO_BYTES) throw new IllegalArgumentException("الحد الأقصى لملف الصوت 20 ميجابايت."); output.write(buffer, 0, count); }
            if (total == 0) throw new IllegalArgumentException("ملف الصوت فارغ.");
            return output.toByteArray();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != AUDIO_PICK_REQUEST || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = "audio";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) { int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (index >= 0) name = cursor.getString(index); }
        } catch (Exception ignored) {}
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!AUDIO_EXTENSIONS.contains(ext)) { Toast.makeText(this, "الصيغ المقبولة: MP3، M4A، AAC، WAV، OGG، OPUS، FLAC، 3GP فقط.", Toast.LENGTH_LONG).show(); return; }
        String mime = getContentResolver().getType(uri);
        if (mime == null || !mime.toLowerCase(Locale.ROOT).startsWith("audio/")) mime = audioMimeForExtension(ext);
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            byte[] buffer = new byte[8192]; long total = 0; int count;
            while (input != null && (count = input.read(buffer)) != -1) { total += count; if (total > MAX_AUDIO_BYTES) throw new IllegalArgumentException("الحد الأقصى لملف الصوت 20 ميجابايت."); }
        } catch (Exception error) { Toast.makeText(this, error.getMessage() == null ? "تعذر قراءة الملف الصوتي." : error.getMessage(), Toast.LENGTH_LONG).show(); return; }
        selectedAudioUri = uri; selectedAudioName = name; selectedAudioMime = mime;
        existingAudioUrl = ""; clearAudioRequested = false; renderAudioAttachment();
    }

    private String audioMimeForExtension(String ext) {
        switch (ext) {
            case "mp3": return "audio/mpeg"; case "m4a": return "audio/mp4"; case "aac": return "audio/aac";
            case "wav": return "audio/wav"; case "ogg": case "opus": return "audio/ogg"; case "flac": return "audio/flac";
            case "3gp": return "audio/3gpp"; default: return "application/octet-stream";
        }
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
        if (detailStatus == null) return;
        detailStatus.setText(statusLabel(status));
        if ("pending".equals(status)) {
            detailStatus.setBackgroundResource(R.drawable.pending_badge_background);
            detailStatus.setTextColor(0xFF7A5313);
        } else {
            detailStatus.setBackgroundResource(R.drawable.filter_chip_active);
            detailStatus.setTextColor(Color.WHITE);
        }
    }

    private void setPublishButtonText(Button button, boolean editing) {
        String label = editing ? "✓  حفظ تعديل الإجابة" : "✓  نشر الإجابة";
        SpannableString styled = new SpannableString(label);
        styled.setSpan(new ForegroundColorSpan(getColorCompat(R.color.site_gold)), 0, 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setText(styled);
    }

    private void invalidateRequestCache() {
        getSharedPreferences("request_cache", MODE_PRIVATE).edit().clear().apply();
    }

    private String statusLabel(String status) {
        if ("answered".equals(status)) return "تمت الإجابة";
        if ("archived".equals(status)) return "مؤرشف";
        return "بانتظار الإجابة";
    }

    private int getColorCompat(int colorRes) {
        return ContextCompat.getColor(this, colorRes);
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
        if (requestCode == AUDIO_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && awaitingRecordPermission) beginAudioRecording();
            else Toast.makeText(this, "يلزم السماح بالميكروفون لتسجيل الإجابة الصوتية.", Toast.LENGTH_LONG).show();
            awaitingRecordPermission = false;
            return;
        }
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST && grantResults.length > 0
                && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "الإشعارات متوقفة. يمكنك تفعيلها من إعدادات التطبيق.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        if (mediaRecorder != null) { try { mediaRecorder.stop(); } catch (Exception ignored) {} releaseRecorder(); }
        networkExecutor.shutdownNow();
        super.onDestroy();
    }
}
