package com.example.geolink;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public class LoginActivity extends AppCompatActivity {
    private static final String PREFS = "geolink_prefs";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        TextInputEditText inputNickname = findViewById(R.id.inputNickname);
        TextInputEditText inputRoom = findViewById(R.id.inputRoom);
        TextInputEditText inputRoomPassword = findViewById(R.id.inputRoomPassword);
        MaterialButton btnContinue = findViewById(R.id.btnContinue);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedName = prefs.getString("nickname", "");
        String savedRoom = prefs.getString("room", "");
        String savedRoomPassword = prefs.getString("room_password", "");
        if (!savedName.isEmpty()) {
            inputNickname.setText(savedName);
        }
        if (!savedRoom.isEmpty()) {
            inputRoom.setText(savedRoom);
        }
        if (!savedRoomPassword.isEmpty()) {
            inputRoomPassword.setText(savedRoomPassword);
        }

        btnContinue.setOnClickListener(v -> {
            String nickname = normalizeUserText(inputNickname.getText() == null ? "" : inputNickname.getText().toString());
            String room = normalizeRoomName(inputRoom.getText() == null ? "" : inputRoom.getText().toString());
            String roomPassword = normalizeUserText(inputRoomPassword.getText() == null ? "" : inputRoomPassword.getText().toString());

            if (TextUtils.isEmpty(nickname) || TextUtils.isEmpty(room) || TextUtils.isEmpty(roomPassword)) {
                Toast.makeText(this, R.string.fill_all_fields, Toast.LENGTH_SHORT).show();
                return;
            }

            if (roomPassword.length() < 4) {
                Toast.makeText(this, R.string.room_password_too_short, Toast.LENGTH_SHORT).show();
                return;
            }

            btnContinue.setEnabled(false);
            continueToMap(prefs, nickname, room, roomPassword);
        });
    }

    private String normalizeUserText(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).trim();
        return normalized.replaceAll("\\s+", " ");
    }

    private String normalizeRoomName(String value) {
        return normalizeUserText(value).toLowerCase(Locale.ROOT);
    }

    private void continueToMap(
            SharedPreferences prefs,
            String nickname,
            String room,
            String roomPassword
    ) {
        String roomSecret = sha256(room + "|" + roomPassword);

        prefs.edit()
                .putString("nickname", nickname)
                .putString("room", room)
                .putString("room_password", roomPassword)
                .apply();

        Intent intent = new Intent(LoginActivity.this, MapActivity.class);
        intent.putExtra("nickname", nickname);
        intent.putExtra("room", room);
        intent.putExtra("room_secret", roomSecret);
        startActivity(intent);
        finish();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format(Locale.US, "%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            return value;
        }
    }
}
