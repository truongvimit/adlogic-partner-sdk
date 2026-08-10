package com.itg.customactivitycrash.activity;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.itg.customactivitycrash.CustomActivityOnCrash;
import com.itg.customactivitycrash.config.ITGCrashConfig;
import com.itg.customactivityoncrash.R;


public final class ITGErrorActivity extends AppCompatActivity {

    private static final int MAX_ERROR_DETAILS_DIALOG_CHARS = 4000;

    @SuppressLint("PrivateResource")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_itg_crash);

        //Close/restart button logic:
        //If a class if set, use restart.
        //Else, use close and just finish the app.
        //It is recommended that you follow this logic if implementing a custom error activity.
        Button restartButton = findViewById(R.id.button_restart_app);

        final ITGCrashConfig config = CustomActivityOnCrash.getConfigFromIntent(getIntent());

        if (config == null) {
            //This should never happen - Just finish the activity to avoid a recursive crash.
            finish();
            return;
        }

        if (config.isShowRestartButton() && config.getRestartActivityClass() != null) {
            restartButton.setText(R.string.restart_app);
            restartButton.setOnClickListener(v -> CustomActivityOnCrash.restartApplication(ITGErrorActivity.this, config));
        } else {
            restartButton.setOnClickListener(v -> CustomActivityOnCrash.closeApplication(ITGErrorActivity.this, config));
        }

        ImageView moreInfoButton = findViewById(R.id.image_info_crash);

        if (config.isShowErrorDetails()) {
            moreInfoButton.setOnClickListener(v -> {
                String fullErrorDetails = CustomActivityOnCrash.getAllErrorDetailsFromIntent(ITGErrorActivity.this, getIntent());
                String errorDetailsForDialog = fullErrorDetails;
                if (fullErrorDetails != null && fullErrorDetails.length() > MAX_ERROR_DETAILS_DIALOG_CHARS) {
                    String suffix = "\n\n[Error details are too long. Showing the first " + MAX_ERROR_DETAILS_DIALOG_CHARS + " characters.]";
                    errorDetailsForDialog = fullErrorDetails.substring(0, MAX_ERROR_DETAILS_DIALOG_CHARS);
                    errorDetailsForDialog = errorDetailsForDialog + suffix;
                }
                AlertDialog.Builder builder = new AlertDialog.Builder(
                        ITGErrorActivity.this,
                        androidx.appcompat.R.style.Theme_AppCompat_DayNight_Dialog_Alert
                );
                AlertDialog dialog = builder
                        .setTitle(R.string.error_details_title)
                        .setMessage(errorDetailsForDialog)
                        .setPositiveButton(R.string.error_details_close, null)
                        .setNeutralButton(R.string.error_details_copy, (dialog1, which) -> copyErrorToClipboard())
                        .setNegativeButton(R.string.error_share, (dialog12, which) -> shareErrorDetails(fullErrorDetails))
                        .show();
                TextView textView = dialog.findViewById(android.R.id.message);
                if (textView != null) {
                    textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 32F);
                }
            });
        } else {
            moreInfoButton.setVisibility(View.GONE);
        }
    }

    private void copyErrorToClipboard() {
        String errorInformation = CustomActivityOnCrash.getAllErrorDetailsFromIntent(ITGErrorActivity.this, getIntent());

        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

        //Are there any devices without clipboard...?
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText(getString(R.string.error_details_clipboard_label), errorInformation);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(ITGErrorActivity.this, R.string.error_details_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void shareErrorDetails(@NonNull String errorDetails) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.error_details_title));
        shareIntent.putExtra(Intent.EXTRA_TEXT, errorDetails);
        startActivity(Intent.createChooser(shareIntent, getString(R.string.error_share)));
    }
}
