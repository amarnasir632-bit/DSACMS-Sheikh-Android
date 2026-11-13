package com.dsacms.sheikhrequests;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Calls the Cloudflare Worker API; no server credentials are shipped in the APK. */
public final class SiteApiClient {
    private static final String API_BASE = "https://www.mohamedalahadi.com/api";

    public LoginResult login(String username, String password) throws Exception {
        JSONObject body = new JSONObject().put("username", username).put("password", password);
        JSONObject json = request("POST", "/auth/login", null, body).getJSONObject("json");
        JSONObject user = json.getJSONObject("user");
        if (!"sheikh".equalsIgnoreCase(user.optString("role", ""))) {
            throw new ApiException(403, "هذا الحساب ليس حساب الشيخ.");
        }
        String token = json.optString("token", "");
        if (token.isBlank()) throw new ApiException(500, "لم يُرجع الخادم جلسة صالحة.");
        return new LoginResult(token, user.optString("name", "الشيخ"));
    }

    public JSONArray getContactRequests(String sessionToken, String status) throws Exception {
        if ("archived".equals(status)) {
            return request("GET", "/questions/sheikh/archived", sessionToken, null).getJSONArray("json");
        }
        if ("answered".equals(status) || "contacted".equals(status)) {
            return request("GET", "/questions/sheikh/answered", sessionToken, null).getJSONArray("json");
        }
        if (status == null || status.isBlank() || "all".equals(status)) {
            JSONArray pending = request("GET", "/questions/sheikh/pending", sessionToken, null).getJSONArray("json");
            JSONArray answered = request("GET", "/questions/sheikh/answered", sessionToken, null).getJSONArray("json");
            JSONArray all = new JSONArray();
            for (int i = 0; i < pending.length(); i++) all.put(pending.getJSONObject(i));
            for (int i = 0; i < answered.length(); i++) all.put(answered.getJSONObject(i));
            return all;
        }
        return request("GET", "/questions/sheikh/pending", sessionToken, null).getJSONArray("json");
    }

    public ContactRequest getContactRequest(String sessionToken, String id) throws Exception {
        JSONObject json = request("GET", "/questions/sheikh/" + id, sessionToken, null)
                .getJSONObject("json");
        return ContactRequest.from(json);
    }

    public JSONObject answerQuestion(String sessionToken, String id, String answer, String audioUrl) throws Exception {
        return request("PATCH", "/questions/sheikh/" + id + "/answer", sessionToken,
                new JSONObject().put("answer_text", answer).put("answer_audio_url", audioUrl == null ? JSONObject.NULL : audioUrl)).getJSONObject("json");
    }

    public interface UploadProgressListener { void onProgress(int uploadedBytes, int totalBytes); }

    public String uploadAnswerAudio(String sessionToken, String id, byte[] audio, String filename, String mimeType,
                                    UploadProgressListener progressListener) throws Exception {
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        HttpURLConnection connection = (HttpURLConnection) new URL(API_BASE + "/questions/sheikh/" + id + "/answer-audio?ext=" + ext).openConnection();
        connection.setRequestMethod("PUT");
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(audio.length);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", mimeType);
        connection.setRequestProperty("Authorization", "Bearer " + sessionToken);
        try {
            try (OutputStream output = connection.getOutputStream()) {
                int offset = 0;
                while (offset < audio.length) {
                    int count = Math.min(16 * 1024, audio.length - offset);
                    output.write(audio, offset, count);
                    offset += count;
                    if (progressListener != null) progressListener.onProgress(offset, audio.length);
                }
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            JSONObject response = new JSONObject(readFully(stream));
            if (code < 200 || code >= 300) throw new ApiException(code, response.optString("error", "تعذر رفع التسجيل الصوتي."));
            String fileUrl = response.optString("fileUrl", "");
            if (fileUrl.isBlank()) throw new ApiException(502, "لم يُرجع الخادم رابط التسجيل.");
            return fileUrl;
        } finally { connection.disconnect(); }
    }

    public void registerPushToken(String sessionToken, String token) throws Exception {
        request("POST", "/questions/sheikh/push-token", sessionToken,
                new JSONObject().put("registration_token", token));
    }

    public void unregisterPushToken(String sessionToken, String token) throws Exception {
        request("DELETE", "/questions/sheikh/push-token", sessionToken,
                new JSONObject().put("registration_token", token));
    }

    public void logout(String sessionToken) throws Exception {
        request("POST", "/auth/logout", sessionToken, new JSONObject());
    }

    public JSONObject testNotification(String sessionToken) throws Exception {
        return request("POST", "/questions/sheikh/test-notification", sessionToken, new JSONObject())
                .getJSONObject("json");
    }

    public void archiveQuestion(String sessionToken, String id, boolean restore) throws Exception {
        request("PATCH", "/questions/sheikh/" + id + (restore ? "/restore" : "/archive"),
                sessionToken, new JSONObject());
    }

    public void deleteQuestion(String sessionToken, String id) throws Exception {
        request("DELETE", "/questions/sheikh/" + id, sessionToken, null);
    }

    private JSONObject request(String method, String path, String sessionToken, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API_BASE + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("Accept", "application/json");
        if (sessionToken != null && !sessionToken.isBlank()) {
            connection.setRequestProperty("Authorization", "Bearer " + sessionToken);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] requestBody = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBody);
            }
        }

        try {
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            String response = readFully(stream);
            if (code < 200 || code >= 300) {
                JSONObject error = new JSONObject(response.isBlank() ? "{}" : response);
                throw new ApiException(code, error.optString("error", "تعذر إكمال الطلب."));
            }
            String payload = response.trim();
            Object json = payload.startsWith("[")
                    ? new JSONArray(payload)
                    : new JSONObject(payload.isBlank() ? "{}" : payload);
            return new JSONObject().put("json", json);
        } finally {
            connection.disconnect();
        }
    }

    private static String readFully(InputStream stream) throws Exception {
        if (stream == null) return "{}";
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    public static final class LoginResult {
        public final String token;
        public final String displayName;
        LoginResult(String token, String displayName) {
            this.token = token;
            this.displayName = displayName;
        }
    }

    public static final class ContactRequest {
        public final String id;
        public final String name;
        public final String phone;
        public final String question;
        public final String answer;
        public final String answerAudioUrl;
        public final String category;
        public final String status;
        public final String createdAt;

        private ContactRequest(String id, String name, String phone, String question, String answer, String answerAudioUrl, String category, String status, String createdAt) {
            this.id = id;
            this.name = name;
            this.phone = phone;
            this.question = question;
            this.answer = answer;
            this.answerAudioUrl = answerAudioUrl;
            this.category = category;
            this.status = status;
            this.createdAt = createdAt;
        }

        static ContactRequest from(JSONObject json) {
            return new ContactRequest(json.optString("id", ""), json.optString("asker_name", "زائر"),
                    "", json.optString("question_text", json.optString("question", "")),
                    json.optString("answer_text", json.optString("answer", "")), json.optString("answer_audio_url", ""), json.optString("category", "عام"),
                    json.optString("status", "pending"), json.optString("created_at", ""));
        }
    }

    public static final class ApiException extends Exception {
        public final int statusCode;
        ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}
