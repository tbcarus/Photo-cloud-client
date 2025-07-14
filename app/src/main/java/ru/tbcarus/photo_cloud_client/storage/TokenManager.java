package ru.tbcarus.photo_cloud_client.storage;

import android.content.Context;
import android.content.SharedPreferences;

import ru.tbcarus.photo_cloud_client.api.models.AuthResponse;

public class TokenManager {
    private SharedPreferences prefs;
    private static final String PREFS_NAME = "secure_prefs";

    public TokenManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void saveTokens(AuthResponse response) {
        prefs.edit()
                .putString("access", response.getAccessToken())
                .putString("refresh", response.getRefreshToken())
                .apply();
    }

    public String getAccessToken() {
        return prefs.getString("access", null);
    }

    public String getRefreshToken() {
        return prefs.getString("refresh", null);
    }
}
