package com.example.airgest;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import android.view.ViewGroup;

/** Per-application gesture -> action mappings. */
public class ProfilesActivity extends AppCompatActivity {

    private SettingsManager settings;
    private ViewGroup container;
    private AppProfile selected = AppProfile.GALLERY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profiles);
        Ui.applyInsets(findViewById(R.id.root));
        Ui.header(this, R.string.profiles_title);
        settings = new SettingsManager(this);
        container = findViewById(R.id.mappingContainer);

        AppProfile[] profiles = AppProfile.values();
        String[] names = new String[profiles.length];
        for (int i = 0; i < profiles.length; i++) names[i] = profiles[i].displayName;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.item_spinner, names);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        Spinner sp = findViewById(R.id.profileSpinner);
        sp.setAdapter(adapter);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                selected = profiles[pos];
                buildRows();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        findViewById(R.id.btnResetProfile).setOnClickListener(v -> {
            settings.resetProfile(selected);
            buildRows();
        });
    }

    private void buildRows() {
        container.removeAllViews();
        final GestureAction[] actions = GestureAction.values();
        String[] labels = new String[actions.length];
        for (int i = 0; i < actions.length; i++) labels[i] = actions[i].displayName;

        for (final GestureType g : GestureType.mappable()) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_mapping, container, false);
            ((TextView) row.findViewById(R.id.gestureName)).setText(g.label);
            Spinner sp = row.findViewById(R.id.actionSpinner);
            ArrayAdapter<String> ad = new ArrayAdapter<>(this, R.layout.item_spinner, labels);
            ad.setDropDownViewResource(R.layout.item_spinner_dropdown);
            sp.setAdapter(ad);
            final GestureAction current = settings.getAction(selected, g);
            sp.setSelection(current.ordinal(), false);
            sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    GestureAction chosen = actions[pos];
                    if (chosen != settings.getAction(selected, g)) settings.setAction(selected, g, chosen);
                }
                @Override public void onNothingSelected(AdapterView<?> p) { }
            });
            container.addView(row);
        }
    }
}
