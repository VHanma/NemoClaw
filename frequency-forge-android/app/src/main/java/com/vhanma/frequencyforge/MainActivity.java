package com.vhanma.frequencyforge;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int LAYER_COUNT = 9;
    private static final int SAMPLE_RATE = 48000;
    private static final int EXPORT_REQUEST = 7001;
    private static final double TWO_PI = Math.PI * 2.0;

    private static final String[] WAVEFORMS = {
            "Sine", "Square", "Triangle", "Sawtooth", "Pulse 25%", "Absolute Sine", "Soft Square"
    };
    private static final String[] PHASES = {"0°", "45°", "90°", "180°"};
    private static final int[] PHASE_VALUES = {0, 45, 90, 180};

    private final ArrayList<LayerRow> rows = new ArrayList<>();
    private volatile LayerConfig[] liveConfigs = new LayerConfig[0];
    private volatile boolean running = false;
    private AudioTrack audioTrack;
    private Thread audioThread;

    private SharedPreferences prefs;
    private Spinner presetSpinner;
    private EditText presetName;
    private EditText durationField;
    private TextView status;
    private int pendingExportSeconds = 60;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("frequency_forge_presets", MODE_PRIVATE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        buildUi();
        refreshSnapshot();
        refreshPresetSpinner();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String value, int size) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(size);
        tv.setPadding(dp(4), dp(3), dp(4), dp(3));
        return tv;
    }

    private EditText edit(String value, String hint) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.rgb(150, 150, 160));
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setPadding(dp(6), dp(2), dp(6), dp(2));
        e.addTextChangedListener(simpleWatcher(this::refreshSnapshot));
        return e;
    }

    private TextWatcher simpleWatcher(Runnable r) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { r.run(); }
            @Override public void afterTextChanged(Editable s) {}
        };
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(10, 12, 18));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(12), dp(10), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("FREQUENCY FORGE", 24);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView sub = text("9-layer independent LEFT + RIGHT stereo generator", 14);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setTextColor(Color.rgb(175, 185, 205));
        root.addView(sub);

        TextView note = text(
                "Each channel now has its own Hz, Wave, Phase and Volume. LEFT controls affect only the left channel; RIGHT controls affect only the right channel. Phase is an absolute per-channel oscillator offset. With different left/right frequencies, their phase relationship naturally changes over time. Requested frequency text is preserved exactly in presets. Physical playback is limited by the phone's sample rate, DAC, speakers/headphones, and human hearing. Start at low volume.",
                12);
        note.setTextColor(Color.rgb(190, 190, 195));
        note.setPadding(dp(6), dp(10), dp(6), dp(10));
        root.addView(note);

        LinearLayout transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        Button play = new Button(this);
        play.setText("PLAY");
        play.setOnClickListener(v -> startAudio());
        Button stop = new Button(this);
        stop.setText("STOP");
        stop.setOnClickListener(v -> stopAudio());
        transport.addView(play, new LinearLayout.LayoutParams(0, dp(52), 1));
        transport.addView(stop, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(transport);

        status = text("Stopped", 13);
        status.setTextColor(Color.rgb(135, 220, 165));
        root.addView(status);

        for (int i = 0; i < LAYER_COUNT; i++) {
            LayerRow row = createLayer(i);
            rows.add(row);
            root.addView(row.container);
        }

        root.addView(text("PRESETS", 20));
        LinearLayout presetNameRow = new LinearLayout(this);
        presetNameRow.setOrientation(LinearLayout.HORIZONTAL);
        presetName = edit("", "Preset name");
        Button savePreset = new Button(this);
        savePreset.setText("SAVE EXACT");
        savePreset.setOnClickListener(v -> savePreset());
        presetNameRow.addView(presetName, new LinearLayout.LayoutParams(0, dp(52), 1));
        presetNameRow.addView(savePreset, new LinearLayout.LayoutParams(dp(130), dp(52)));
        root.addView(presetNameRow);

        LinearLayout loadRow = new LinearLayout(this);
        loadRow.setOrientation(LinearLayout.HORIZONTAL);
        presetSpinner = new Spinner(this);
        Button load = new Button(this);
        load.setText("LOAD");
        load.setOnClickListener(v -> loadSelectedPreset());
        loadRow.addView(presetSpinner, new LinearLayout.LayoutParams(0, dp(52), 1));
        loadRow.addView(load, new LinearLayout.LayoutParams(dp(110), dp(52)));
        root.addView(loadRow);

        root.addView(text("WAV EXPORT", 20));
        LinearLayout exportRow = new LinearLayout(this);
        exportRow.setOrientation(LinearLayout.HORIZONTAL);
        durationField = edit("60", "seconds");
        Button export = new Button(this);
        export.setText("EXPORT WAV");
        export.setOnClickListener(v -> requestWavExport());
        exportRow.addView(durationField, new LinearLayout.LayoutParams(0, dp(52), 1));
        exportRow.addView(export, new LinearLayout.LayoutParams(dp(150), dp(52)));
        root.addView(exportRow);

        setContentView(scroll);
    }

    private LayerRow createLayer(int index) {
        LayerRow r = new LayerRow();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        box.setBackgroundColor(index % 2 == 0 ? Color.rgb(20, 24, 34) : Color.rgb(25, 29, 40));
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxParams.setMargins(0, dp(8), 0, 0);
        box.setLayoutParams(boxParams);
        r.container = box;

        TextView header = text("LAYER " + (index + 1), 18);
        box.addView(header);

        LinearLayout toggles = new LinearLayout(this);
        toggles.setOrientation(LinearLayout.HORIZONTAL);
        r.enabled = new Switch(this);
        r.enabled.setText("Enabled");
        r.enabled.setTextColor(Color.WHITE);
        r.enabled.setChecked(index == 0);
        r.reverse = new Switch(this);
        r.reverse.setText("Reverse layer");
        r.reverse.setTextColor(Color.WHITE);
        r.enabled.setOnCheckedChangeListener((b, checked) -> refreshSnapshot());
        r.reverse.setOnCheckedChangeListener((b, checked) -> refreshSnapshot());
        toggles.addView(r.enabled, new LinearLayout.LayoutParams(0, dp(48), 1));
        toggles.addView(r.reverse, new LinearLayout.LayoutParams(0, dp(48), 1));
        box.addView(toggles);

        r.leftFreq = edit("432", "Left Hz");
        r.leftWaveform = spinner(WAVEFORMS);
        r.leftPhase = spinner(PHASES);
        r.leftVolLabel = text("LEFT volume: 25%", 13);
        r.leftVol = volumeSeek(25, r.leftVolLabel, "LEFT volume: ");
        attachChannelListeners(r.leftWaveform, r.leftPhase);
        box.addView(channelPanel("LEFT CHANNEL", Color.rgb(95, 205, 255),
                r.leftFreq, r.leftWaveform, r.leftPhase, r.leftVolLabel, r.leftVol));

        r.rightFreq = edit("432", "Right Hz");
        r.rightWaveform = spinner(WAVEFORMS);
        r.rightPhase = spinner(PHASES);
        r.rightVolLabel = text("RIGHT volume: 25%", 13);
        r.rightVol = volumeSeek(25, r.rightVolLabel, "RIGHT volume: ");
        attachChannelListeners(r.rightWaveform, r.rightPhase);
        box.addView(channelPanel("RIGHT CHANNEL", Color.rgb(255, 135, 180),
                r.rightFreq, r.rightWaveform, r.rightPhase, r.rightVolLabel, r.rightVol));
        return r;
    }

    private void attachChannelListeners(Spinner waveform, Spinner phase) {
        waveform.setOnItemSelectedListener(itemListener(this::refreshSnapshot));
        phase.setOnItemSelectedListener(itemListener(this::refreshSnapshot));
    }

    private LinearLayout channelPanel(String titleValue, int accentColor,
                                      EditText frequency, Spinner waveform, Spinner phase,
                                      TextView volumeLabel, SeekBar volume) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(8), dp(8), dp(8));
        panel.setBackgroundColor(Color.rgb(13, 16, 24));
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelParams.setMargins(0, dp(7), 0, dp(2));
        panel.setLayoutParams(panelParams);

        TextView channelTitle = text(titleValue, 16);
        channelTitle.setTextColor(accentColor);
        panel.addView(channelTitle);
        panel.addView(column("Frequency (Hz)", frequency));

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.addView(column("Wave", waveform), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        modes.addView(column("Phase", phase), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        panel.addView(modes);
        panel.addView(volumeLabel);
        panel.addView(volume);
        return panel;
    }

    private LinearLayout column(String label, View child) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(label, 12));
        col.addView(child, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return col;
    }

    private Spinner spinner(String[] values) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(adapter);
        return s;
    }

    private AdapterView.OnItemSelectedListener itemListener(Runnable r) {
        return new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { r.run(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
    }

    private SeekBar volumeSeek(int initial, TextView label, String prefix) {
        SeekBar s = new SeekBar(this);
        s.setMax(100);
        s.setProgress(initial);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                label.setText(prefix + progress + "%");
                refreshSnapshot();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        return s;
    }

    private void refreshSnapshot() {
        if (rows.isEmpty()) return;
        LayerConfig[] configs = new LayerConfig[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            LayerRow r = rows.get(i);
            LayerConfig c = new LayerConfig();
            c.enabled = r.enabled.isChecked();
            c.reverse = r.reverse.isChecked();
            c.leftWaveform = r.leftWaveform.getSelectedItemPosition();
            c.rightWaveform = r.rightWaveform.getSelectedItemPosition();
            c.leftPhaseDegrees = phaseDegrees(r.leftPhase);
            c.rightPhaseDegrees = phaseDegrees(r.rightPhase);
            c.leftHz = parseDouble(r.leftFreq.getText().toString());
            c.rightHz = parseDouble(r.rightFreq.getText().toString());
            c.leftVolume = r.leftVol.getProgress() / 100.0;
            c.rightVolume = r.rightVol.getProgress() / 100.0;
            configs[i] = c;
        }
        liveConfigs = configs;
    }

    private int phaseDegrees(Spinner spinner) {
        int position = Math.max(0, Math.min(PHASE_VALUES.length - 1, spinner.getSelectedItemPosition()));
        return PHASE_VALUES[position];
    }

    private double parseDouble(String value) {
        try {
            double v = Double.parseDouble(value.trim());
            return Double.isFinite(v) ? v : 0.0;
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private void startAudio() {
        if (running) return;
        refreshSnapshot();
        int min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferBytes = Math.max(min, 8192);
        try {
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferBytes)
                    .build();
            running = true;
            audioTrack.play();
            audioThread = new Thread(this::audioLoop, "FrequencyForgeAudio");
            audioThread.start();
            status.setText("Playing • independent LEFT + RIGHT engine");
        } catch (Exception e) {
            running = false;
            status.setText("Audio start failed: " + e.getMessage());
        }
    }

    private void audioLoop() {
        final int frames = 1024;
        short[] pcm = new short[frames * 2];
        double[] phaseL = new double[LAYER_COUNT];
        double[] phaseR = new double[LAYER_COUNT];
        while (running) {
            LayerConfig[] cfgs = liveConfigs;
            for (int frame = 0; frame < frames; frame++) {
                double left = 0.0, right = 0.0;
                int activeL = 0, activeR = 0;
                for (int i = 0; i < cfgs.length && i < LAYER_COUNT; i++) {
                    LayerConfig c = cfgs[i];
                    if (!c.enabled) continue;
                    double dir = c.reverse ? -1.0 : 1.0;
                    left += wave(phaseL[i] + Math.toRadians(c.leftPhaseDegrees), c.leftWaveform) * c.leftVolume;
                    right += wave(phaseR[i] + Math.toRadians(c.rightPhaseDegrees), c.rightWaveform) * c.rightVolume;
                    if (c.leftVolume > 0) activeL++;
                    if (c.rightVolume > 0) activeR++;
                    phaseL[i] = wrap(phaseL[i] + dir * TWO_PI * c.leftHz / SAMPLE_RATE);
                    phaseR[i] = wrap(phaseR[i] + dir * TWO_PI * c.rightHz / SAMPLE_RATE);
                }
                if (activeL > 1) left /= activeL;
                if (activeR > 1) right /= activeR;
                left = Math.max(-1.0, Math.min(1.0, left));
                right = Math.max(-1.0, Math.min(1.0, right));
                pcm[frame * 2] = (short) Math.round(left * 32767.0);
                pcm[frame * 2 + 1] = (short) Math.round(right * 32767.0);
            }
            AudioTrack t = audioTrack;
            if (t != null && running) {
                int wrote = t.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING);
                if (wrote < 0) break;
            }
        }
    }

    private double wrap(double phase) {
        phase %= TWO_PI;
        if (phase < 0) phase += TWO_PI;
        return phase;
    }

    private double wave(double phase, int waveform) {
        double p = wrap(phase) / TWO_PI;
        switch (waveform) {
            case 1: return p < 0.5 ? 1.0 : -1.0;
            case 2: return 1.0 - 4.0 * Math.abs(p - 0.5);
            case 3: return 2.0 * p - 1.0;
            case 4: return p < 0.25 ? 1.0 : -1.0;
            case 5: return Math.abs(Math.sin(phase)) * 2.0 - 1.0;
            case 6: return Math.tanh(3.0 * Math.sin(phase)) / Math.tanh(3.0);
            default: return Math.sin(phase);
        }
    }

    private void stopAudio() {
        running = false;
        Thread t = audioThread;
        audioThread = null;
        if (t != null) try { t.join(350); } catch (InterruptedException ignored) {}
        AudioTrack at = audioTrack;
        audioTrack = null;
        if (at != null) {
            try { at.pause(); } catch (Exception ignored) {}
            try { at.flush(); } catch (Exception ignored) {}
            try { at.stop(); } catch (Exception ignored) {}
            try { at.release(); } catch (Exception ignored) {}
        }
        if (status != null) status.setText("Stopped");
    }

    private void savePreset() {
        String name = presetName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "Enter a preset name", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject root = new JSONObject();
            root.put("version", 2);
            root.put("sampleRate", SAMPLE_RATE);
            JSONArray layers = new JSONArray();
            for (LayerRow r : rows) {
                JSONObject j = new JSONObject();
                j.put("enabled", r.enabled.isChecked());
                j.put("reverse", r.reverse.isChecked());
                j.put("leftWaveform", r.leftWaveform.getSelectedItemPosition());
                j.put("rightWaveform", r.rightWaveform.getSelectedItemPosition());
                j.put("leftPhase", r.leftPhase.getSelectedItemPosition());
                j.put("rightPhase", r.rightPhase.getSelectedItemPosition());
                j.put("leftHzText", r.leftFreq.getText().toString());
                j.put("rightHzText", r.rightFreq.getText().toString());
                j.put("leftVolume", r.leftVol.getProgress());
                j.put("rightVolume", r.rightVol.getProgress());
                layers.put(j);
            }
            root.put("layers", layers);
            Set<String> names = new HashSet<>(prefs.getStringSet("names", Collections.emptySet()));
            names.add(name);
            prefs.edit().putString("preset:" + name, root.toString()).putStringSet("names", names).apply();
            refreshPresetSpinner();
            selectPreset(name);
            status.setText("Preset saved exactly: " + name);
        } catch (Exception e) {
            status.setText("Preset save failed: " + e.getMessage());
        }
    }

    private void refreshPresetSpinner() {
        if (presetSpinner == null) return;
        ArrayList<String> names = new ArrayList<>(prefs.getStringSet("names", Collections.emptySet()));
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        if (names.isEmpty()) names.add("No saved presets");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetSpinner.setAdapter(adapter);
    }

    private void selectPreset(String name) {
        if (presetSpinner == null) return;
        for (int i = 0; i < presetSpinner.getCount(); i++) {
            if (name.equals(presetSpinner.getItemAtPosition(i))) {
                presetSpinner.setSelection(i);
                return;
            }
        }
    }

    private void loadSelectedPreset() {
        Object selected = presetSpinner.getSelectedItem();
        if (selected == null) return;
        String name = selected.toString();
        String json = prefs.getString("preset:" + name, null);
        if (json == null) {
            Toast.makeText(this, "No saved preset selected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject root = new JSONObject(json);
            int version = root.optInt("version", 1);
            JSONArray layers = root.getJSONArray("layers");
            for (int i = 0; i < rows.size() && i < layers.length(); i++) {
                JSONObject j = layers.getJSONObject(i);
                LayerRow r = rows.get(i);
                r.enabled.setChecked(j.optBoolean("enabled", false));
                r.reverse.setChecked(j.optBoolean("reverse", false));
                if (version >= 2 || j.has("leftWaveform") || j.has("rightWaveform")) {
                    r.leftWaveform.setSelection(safeSelection(j.optInt("leftWaveform", 0), WAVEFORMS.length));
                    r.rightWaveform.setSelection(safeSelection(j.optInt("rightWaveform", 0), WAVEFORMS.length));
                    r.leftPhase.setSelection(safeSelection(j.optInt("leftPhase", 0), PHASES.length));
                    r.rightPhase.setSelection(safeSelection(j.optInt("rightPhase", 0), PHASES.length));
                } else {
                    int oldWave = safeSelection(j.optInt("waveform", 0), WAVEFORMS.length);
                    int oldRightPhase = safeSelection(j.optInt("phase", 0), PHASES.length);
                    r.leftWaveform.setSelection(oldWave);
                    r.rightWaveform.setSelection(oldWave);
                    r.leftPhase.setSelection(0);
                    r.rightPhase.setSelection(oldRightPhase);
                }
                r.leftFreq.setText(j.optString("leftHzText", "432"));
                r.rightFreq.setText(j.optString("rightHzText", "432"));
                r.leftVol.setProgress(j.optInt("leftVolume", 25));
                r.rightVol.setProgress(j.optInt("rightVolume", 25));
            }
            presetName.setText(name);
            refreshSnapshot();
            status.setText(version >= 2 ? "Loaded exactly: " + name : "Loaded + migrated v1 preset: " + name);
        } catch (Exception e) {
            status.setText("Preset load failed: " + e.getMessage());
        }
    }

    private int safeSelection(int value, int length) {
        return Math.max(0, Math.min(length - 1, value));
    }

    private void requestWavExport() {
        int seconds;
        try { seconds = Integer.parseInt(durationField.getText().toString().trim()); }
        catch (Exception e) { seconds = 60; }
        seconds = Math.max(1, Math.min(600, seconds));
        pendingExportSeconds = seconds;
        refreshSnapshot();
        String base = presetName.getText().toString().trim();
        if (base.isEmpty()) base = "frequency-forge";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/wav");
        intent.putExtra(Intent.EXTRA_TITLE, base + ".wav");
        startActivityForResult(intent, EXPORT_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == EXPORT_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            LayerConfig[] snapshot = liveConfigs.clone();
            status.setText("Exporting WAV…");
            new Thread(() -> exportWav(uri, pendingExportSeconds, snapshot), "FrequencyForgeExport").start();
        }
    }

    private void exportWav(Uri uri, int seconds, LayerConfig[] cfgs) {
        long frames = (long) SAMPLE_RATE * seconds;
        long dataBytes = frames * 4L;
        if (dataBytes > 0x7FFFFFF0L) {
            runOnUiThread(() -> status.setText("Export too large"));
            return;
        }
        try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
            if (out == null) throw new IllegalStateException("Unable to open output file");
            writeWavHeader(out, (int) dataBytes);
            double[] phaseL = new double[LAYER_COUNT];
            double[] phaseR = new double[LAYER_COUNT];
            final int chunkFrames = 2048;
            ByteBuffer buf = ByteBuffer.allocate(chunkFrames * 4).order(ByteOrder.LITTLE_ENDIAN);
            long done = 0;
            while (done < frames) {
                int n = (int) Math.min(chunkFrames, frames - done);
                buf.clear();
                for (int frame = 0; frame < n; frame++) {
                    double left = 0.0, right = 0.0;
                    int activeL = 0, activeR = 0;
                    for (int i = 0; i < cfgs.length && i < LAYER_COUNT; i++) {
                        LayerConfig c = cfgs[i];
                        if (!c.enabled) continue;
                        double dir = c.reverse ? -1.0 : 1.0;
                        left += wave(phaseL[i] + Math.toRadians(c.leftPhaseDegrees), c.leftWaveform) * c.leftVolume;
                        right += wave(phaseR[i] + Math.toRadians(c.rightPhaseDegrees), c.rightWaveform) * c.rightVolume;
                        if (c.leftVolume > 0) activeL++;
                        if (c.rightVolume > 0) activeR++;
                        phaseL[i] = wrap(phaseL[i] + dir * TWO_PI * c.leftHz / SAMPLE_RATE);
                        phaseR[i] = wrap(phaseR[i] + dir * TWO_PI * c.rightHz / SAMPLE_RATE);
                    }
                    if (activeL > 1) left /= activeL;
                    if (activeR > 1) right /= activeR;
                    left = Math.max(-1.0, Math.min(1.0, left));
                    right = Math.max(-1.0, Math.min(1.0, right));
                    buf.putShort((short) Math.round(left * 32767.0));
                    buf.putShort((short) Math.round(right * 32767.0));
                }
                out.write(buf.array(), 0, n * 4);
                done += n;
            }
            out.flush();
            runOnUiThread(() -> status.setText("WAV saved • " + seconds + " seconds"));
        } catch (Exception e) {
            runOnUiThread(() -> status.setText("WAV export failed: " + e.getMessage()));
        }
    }

    private void writeWavHeader(OutputStream out, int dataBytes) throws Exception {
        int byteRate = SAMPLE_RATE * 2 * 16 / 8;
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put(new byte[]{'R','I','F','F'});
        h.putInt(36 + dataBytes);
        h.put(new byte[]{'W','A','V','E'});
        h.put(new byte[]{'f','m','t',' '});
        h.putInt(16);
        h.putShort((short) 1);
        h.putShort((short) 2);
        h.putInt(SAMPLE_RATE);
        h.putInt(byteRate);
        h.putShort((short) 4);
        h.putShort((short) 16);
        h.put(new byte[]{'d','a','t','a'});
        h.putInt(dataBytes);
        out.write(h.array());
    }

    @Override
    protected void onDestroy() {
        stopAudio();
        super.onDestroy();
    }

    private static class LayerRow {
        LinearLayout container;
        Switch enabled;
        Switch reverse;
        Spinner leftWaveform;
        Spinner rightWaveform;
        Spinner leftPhase;
        Spinner rightPhase;
        EditText leftFreq;
        EditText rightFreq;
        SeekBar leftVol;
        SeekBar rightVol;
        TextView leftVolLabel;
        TextView rightVolLabel;
    }

    private static class LayerConfig {
        boolean enabled;
        boolean reverse;
        int leftWaveform;
        int rightWaveform;
        int leftPhaseDegrees;
        int rightPhaseDegrees;
        double leftHz;
        double rightHz;
        double leftVolume;
        double rightVolume;
    }
}
