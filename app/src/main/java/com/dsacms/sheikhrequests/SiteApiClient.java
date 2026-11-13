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

    public JSONObject answerQuestion(String sessionToken, String id, String answer) throws Exception {
        return request("PATCH", "/questions/sheikh/" + id + "/answer", sessionToken,
                new JSONObject().put("answer_text", answer)).getJSONObject("json");
    }

    public void registerPushToken(String sessionToken, String token) throws Exception {
        request("POST", "/questions/sheikh/push-token", sessionToken,
                new JSONObject().put("registration_token", token));
    }

    public void unregisterPushToken(String sessionToken, String token) throws Exception {
        request("DELETE", "/questions/sheikh/push-token", sessionToken,
                new JSONObject().put("registration_token", token));
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
        public final String category;
        public final String status;
        public final String createdAt;

        private ContactRequest(String id, String name, String phone, String question, String answer, String category, String status, String createdAt) {
            this.id = id;
            this.name = name;
            this.phone = phone;
            this.question = question;
            this.answer = answer;
            this.category = category;
            this.status = status;
            this.createdAt = createdAt;
        }

        static ContactRequest from(JSONObject json) {
            return new ContactRequest(json.optString("id", ""), json.optString("asker_name", "زائر"),
                    "", json.optString("question_text", json.optString("question", "")),
                    json.optString("answer_text", json.optString("answer", "")), json.optString("category", "عام"),
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
