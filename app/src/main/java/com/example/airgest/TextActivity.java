package com.example.airgest;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** Shows a static text screen (Privacy, Gesture Guide). */
public class TextActivity extends AppCompatActivity {
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_BODY = "body";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text);
        Ui.applyInsets(findViewById(R.id.root));
        Ui.header(this, getIntent().getIntExtra(EXTRA_TITLE, R.string.app_name));
        ((TextView) findViewById(R.id.bodyText)).setText(getIntent().getIntExtra(EXTRA_BODY, R.string.privacy_text));
    }
}
