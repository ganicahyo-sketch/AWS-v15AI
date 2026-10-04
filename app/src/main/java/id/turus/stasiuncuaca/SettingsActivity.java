package id.turus.stasiuncuaca;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    private android.content.SharedPreferences prefs;
    private EditText title, channel, readKey, openWeatherKey, aiKey, aiModel, crop;
    private TextView toggleReadKey, toggleAiKey;
    private Spinner decimals;
    private EditText[] fieldNames = new EditText[8];
    private EditText[] fieldUnits = new EditText[8];

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = getSharedPreferences("thingspeak_config", MODE_PRIVATE);

        title = findViewById(R.id.appTitle);
        channel = findViewById(R.id.channel);
        readKey = findViewById(R.id.readKey);
        openWeatherKey = findViewById(R.id.openWeatherKey);
        aiKey = findViewById(R.id.aiKey);
        aiModel = findViewById(R.id.aiModel);
        crop = findViewById(R.id.crop);
        toggleReadKey = findViewById(R.id.toggleKey);
        toggleAiKey = findViewById(R.id.toggleAiKey);
        decimals = findViewById(R.id.decimals);

        title.setText(prefs.getString("app_title", "STASIUN CUACA"));
        channel.setText(prefs.getString("channel", ""));
        readKey.setText(prefs.getString("read_key", ""));
        openWeatherKey.setText(prefs.getString("openweather_key", ""));
        aiKey.setText(prefs.getString("ai_api_key", ""));
        aiModel.setText(prefs.getString("ai_model", "gpt-6-luna"));
        crop.setText(prefs.getString("crop", "Tanaman pertanian"));

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"0 angka", "1 angka", "2 angka"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        decimals.setAdapter(adapter);
        int d = Math.max(0, Math.min(2, prefs.getInt("display_decimals", 2)));
        decimals.setSelection(d);

        for (int i = 0; i < 8; i++) {
            fieldNames[i] = findViewById(getResources().getIdentifier("fieldName" + (i + 1), "id", getPackageName()));
            fieldUnits[i] = findViewById(getResources().getIdentifier("fieldUnit" + (i + 1), "id", getPackageName()));
            fieldNames[i].setText(prefs.getString("field_name_" + (i + 1), ""));
            fieldUnits[i].setText(prefs.getString("field_unit_" + (i + 1), ""));
            String autoName = prefs.getString("ts_field_name_" + (i + 1), "").trim();
            fieldNames[i].setHint(autoName.isEmpty() ? "Nama tampilan (kosong = otomatis)" : "Otomatis: " + autoName);
        }

        toggleReadKey.setOnClickListener(v -> togglePassword(readKey, toggleReadKey));
        findViewById(R.id.toggleOpenWeatherKey).setOnClickListener(v -> togglePassword(openWeatherKey, (TextView)findViewById(R.id.toggleOpenWeatherKey)));
        toggleAiKey.setOnClickListener(v -> togglePassword(aiKey, toggleAiKey));
        findViewById(R.id.save).setOnClickListener(v -> save());
        findViewById(R.id.cancel).setOnClickListener(v -> finish());
    }

    private void togglePassword(EditText field, TextView toggle) {
        boolean visible = field.getInputType() == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (visible ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        toggle.setText(visible ? "TAMPILKAN" : "SEMBUNYIKAN");
        field.setSelection(field.length());
    }

    private void save() {
        String titleValue = title.getText().toString().trim();
        String ch = channel.getText().toString().trim();
        String key = readKey.getText().toString().trim();
        String weatherKey = openWeatherKey.getText().toString().trim();
        String newAiKey = aiKey.getText().toString().trim();
        String model = aiModel.getText().toString().trim();
        String cropValue = crop.getText().toString().trim();
        if (titleValue.isEmpty()) titleValue = "STASIUN CUACA";
        if (ch.isEmpty()) {
            channel.setError("Channel ID wajib diisi");
            return;
        }
        if (model.isEmpty()) model = "gpt-6-luna";
        if (cropValue.isEmpty()) cropValue = "Tanaman pertanian";

        String oldChannel = prefs.getString("channel", "");
        android.content.SharedPreferences.Editor e = prefs.edit()
                .putString("app_title", titleValue)
                .putString("channel", ch)
                .putString("read_key", key)
                .putString("openweather_key", weatherKey)
                .putString("ai_api_key", newAiKey)
                .putString("ai_model", model)
                .putString("crop", cropValue)
                .putInt("display_decimals", decimals.getSelectedItemPosition());

        for (int i = 0; i < 8; i++) {
            e.putString("field_name_" + (i + 1), fieldNames[i].getText().toString().trim());
            e.putString("field_unit_" + (i + 1), fieldUnits[i].getText().toString().trim());
        }
        if (!ch.equals(oldChannel)) {
            e.putString("field_meta_channel", "");
            for (int i = 0; i < 8; i++) e.remove("ts_field_name_" + (i + 1));
        }
        e.apply();
        Toast.makeText(this, "Pengaturan tersimpan", Toast.LENGTH_SHORT).show();
        finish();
    }
}
