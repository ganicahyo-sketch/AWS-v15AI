package id.turus.stasiuncuaca;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.DatePicker;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CSV exporter for ThingSpeak.
 *
 * Two modes:
 * 1) RENTANG WAKTU - user chooses start/end dates.
 * 2) SELURUH HISTORI - starts from a safe early date and keeps splitting
 *    any interval that reaches ThingSpeak's 8,000-result limit.
 *
 * The complete CSV is streamed to a temporary file, not held in a byte[]
 * in RAM, so long histories can be exported without requiring a huge heap.
 */
public class CsvDownloadActivity extends Activity {
    private static final String PREFS = "thingspeak_config";
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter API_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US);
    private static final DateTimeFormatter FILE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US);

    // ThingSpeak documents a maximum of 8,000 results per read request.
    private static final int MAX_RESULTS_PER_REQUEST = 8000;
    private static final int MAX_SPLIT_DEPTH = 32;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int NETWORK_RETRIES = 3;
    private static final int RECENT_ID_CACHE_LIMIT = 50000;

    // Safe early boundary. We do not need channel User API Key just to discover
    // a channel's creation time. Any actual ThingSpeak feed older than this
    // boundary would be outside the application's supported export window.
    private static final LocalDateTime HISTORY_START =
            LocalDateTime.of(2000, 1, 1, 0, 0, 0);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private android.content.SharedPreferences prefs;

    private LocalDate startDate;
    private LocalDate endDate;

    private TextView startView;
    private TextView endView;
    private TextView statusView;
    private TextView downloadButton;
    private TextView modeRange;
    private TextView modeAllHistory;
    private LinearLayout rangePanel;
    private TextView historyInfo;

    private boolean allHistoryMode = false;
    private boolean downloading = false;
    private boolean readyToSave = false;

    private File pendingCsvFile;
    private String pendingFileName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        setContentView(R.layout.activity_csv_download);

        startView = findViewById(R.id.startDate);
        endView = findViewById(R.id.endDate);
        statusView = findViewById(R.id.csvStatus);
        downloadButton = findViewById(R.id.downloadButton);
        modeRange = findViewById(R.id.modeRange);
        modeAllHistory = findViewById(R.id.modeAllHistory);
        rangePanel = findViewById(R.id.rangePanel);
        historyInfo = findViewById(R.id.historyInfo);

        LocalDate today = LocalDate.now(WIB);
        startDate = today.withDayOfMonth(1);
        endDate = today;
        refreshDateLabels();

        modeRange.setOnClickListener(v -> setMode(false));
        modeAllHistory.setOnClickListener(v -> setMode(true));
        findViewById(R.id.startDate).setOnClickListener(v -> pickStartDate());
        findViewById(R.id.endDate).setOnClickListener(v -> pickEndDate());
        downloadButton.setOnClickListener(v -> {
            if (readyToSave) {
                openSaveDocument();
            } else {
                prepareCsv();
            }
        });
        findViewById(R.id.back).setOnClickListener(v -> finish());

        setMode(false);
    }

    private void setMode(boolean allHistory) {
        if (downloading) return;

        allHistoryMode = allHistory;
        if (allHistoryMode) {
            modeAllHistory.setBackgroundResource(R.drawable.bg_button);
            modeRange.setBackgroundResource(R.drawable.bg_edit);
            modeAllHistory.setTextColor(Color.rgb(7, 19, 31));
            modeRange.setTextColor(Color.WHITE);

            rangePanel.setVisibility(android.view.View.GONE);
            historyInfo.setText(
                    "SELURUH HISTORI: aplikasi mencari data dari awal rentang aman sampai data terbaru, "
                            + "lalu otomatis memecah permintaan bila mencapai 8.000 entry.");
            statusView.setText(
                    "Seluruh histori akan digabung menjadi satu CSV. Proses dapat berlangsung lebih lama "
                            + "untuk channel dengan banyak data.");
        } else {
            modeRange.setBackgroundResource(R.drawable.bg_button);
            modeAllHistory.setBackgroundResource(R.drawable.bg_edit);
            modeRange.setTextColor(Color.rgb(7, 19, 31));
            modeAllHistory.setTextColor(Color.WHITE);

            rangePanel.setVisibility(android.view.View.VISIBLE);
            historyInfo.setText(
                    "RENTANG WAKTU: pilih tanggal mulai dan tanggal akhir. "
                            + "Permintaan panjang juga dipecah otomatis saat diperlukan.");
            statusView.setText("Pilih tanggal kemudian unduh.");
        }
    }

    private void refreshDateLabels() {
        startView.setText(startDate.toString());
        endView.setText(endDate.toString());
    }

    private void pickStartDate() {
        showPicker(startDate, (view, y, m, d) -> {
            LocalDate selected = LocalDate.of(y, m + 1, d);
            if (selected.isAfter(endDate)) {
                Toast.makeText(
                        this,
                        "Tanggal mulai tidak boleh melewati tanggal akhir.",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }
            startDate = selected;
            refreshDateLabels();
        });
    }

    private void pickEndDate() {
        showPicker(endDate, (view, y, m, d) -> {
            LocalDate selected = LocalDate.of(y, m + 1, d);
            if (selected.isBefore(startDate)) {
                Toast.makeText(
                        this,
                        "Tanggal akhir tidak boleh sebelum tanggal mulai.",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }
            endDate = selected;
            refreshDateLabels();
        });
    }

    private void showPicker(
            LocalDate date,
            DatePickerDialog.OnDateSetListener listener
    ) {
        DatePickerDialog dialog = new DatePickerDialog(
                this,
                listener,
                date.getYear(),
                date.getMonthValue() - 1,
                date.getDayOfMonth()
        );
        dialog.show();
    }

    private void prepareCsv() {
        if (downloading) return;

        String channel = prefs.getString("channel", "").trim();
        String key = prefs.getString("read_key", "").trim();

        if (channel.isEmpty()) {
            statusView.setText(
                    "Channel ID belum diatur. Buka Pengaturan terlebih dahulu."
            );
            return;
        }

        if (!allHistoryMode && startDate.isAfter(endDate)) {
            statusView.setText("Rentang tanggal tidak valid.");
            return;
        }

        downloading = true;
        readyToSave = false;
        downloadButton.setText("MENGAMBIL DATA...");
        downloadButton.setEnabled(false);

        final LocalDateTime exportStart;
        final LocalDateTime exportEnd;
        final String fileName;

        if (allHistoryMode) {
            exportStart = HISTORY_START;
            // Use the current local device time as the upper boundary. Adding one
            // second avoids excluding an entry created in the current second.
            exportEnd = LocalDateTime.now(WIB).plusSeconds(1);
            fileName = "STASIUN-CUACA-DESA-TURUS_SELURUH-HISTORI.csv";
            statusView.setText("Menyiapkan seluruh histori ThingSpeak...");
        } else {
            exportStart = startDate.atStartOfDay();
            // The selected end date is inclusive through 23:59:59 WIB.
            exportEnd = endDate.plusDays(1).atStartOfDay().minusSeconds(1);
            fileName = "STASIUN-CUACA-DESA-TURUS_"
                    + startDate.format(FILE_FMT)
                    + "_"
                    + endDate.format(FILE_FMT)
                    + ".csv";
            statusView.setText(
                    "Mengambil data "
                            + startDate
                            + " sampai "
                            + endDate
                            + "..."
            );
        }

        net.execute(() -> {
            File temp = null;
            try {
                temp = File.createTempFile(
                        "stasiun_cuaca_turus_",
                        ".csv",
                        getCacheDir()
                );

                ExportStats stats = new ExportStats();
                final File tempFile = temp;

                try (Writer writer = Files.newBufferedWriter(
                        temp.toPath(),
                        StandardCharsets.UTF_8
                )) {
                    // UTF-8 BOM helps spreadsheet applications recognize UTF-8,
                    // while remaining valid UTF-8 CSV.
                    writer.write('\uFEFF');

                    fetchIntervalAdaptive(
                            channel,
                            key,
                            exportStart,
                            exportEnd,
                            writer,
                            stats,
                            0
                    );

                    writer.flush();
                }

                if (stats.rows <= 0 || stats.headerWritten == false) {
                    throw new Exception(
                            "Tidak ada data pada " +
                                    (allHistoryMode ? "histori channel." : "rentang tanggal tersebut.")
                    );
                }

                pendingCsvFile = tempFile;
                pendingFileName = fileName;
                readyToSave = true;

                final long totalRows = stats.rows;
                final int requests = stats.requests;

                runOnUiThread(() -> {
                    downloading = false;
                    downloadButton.setEnabled(true);
                    downloadButton.setText("SIMPAN FILE CSV");
                    statusView.setText(
                            "Data siap: "
                                    + totalRows
                                    + " baris • "
                                    + requests
                                    + " permintaan API. Pilih lokasi penyimpanan CSV."
                    );
                });

            } catch (Exception ex) {
                if (temp != null) {
                    try { temp.delete(); } catch (Exception ignored) {}
                }
                pendingCsvFile = null;
                readyToSave = false;

                final String message = safeMessage(ex);
                runOnUiThread(() -> {
                    downloading = false;
                    downloadButton.setEnabled(true);
                    downloadButton.setText("UNDUH DATA CSV");
                    statusView.setText("Gagal: " + message);
                });
            }
        });
    }

    /**
     * Reads one interval. If the response contains exactly 8,000 data rows,
     * the interval is split into two smaller intervals until each response is
     * safely below the API limit. This means long histories are not silently
     * truncated to the newest 8,000 records.
     */
    private void fetchIntervalAdaptive(
            String channel,
            String key,
            LocalDateTime start,
            LocalDateTime end,
            Writer writer,
            ExportStats stats,
            int depth
    ) throws Exception {
        if (depth > MAX_SPLIT_DEPTH) {
            throw new Exception(
                    "Rentang terlalu padat untuk dipecah lebih lanjut. "
                            + "Coba gunakan rentang tanggal yang lebih pendek."
            );
        }

        if (!start.isBefore(end)) return;

        CsvBatch batch = fetchCsvBatch(channel, key, start, end);
        stats.requests++;

        if (batch.lines.isEmpty()) {
            postProgress(stats, "Memeriksa rentang kosong...");
            return;
        }

        if (batch.lines.size() < MAX_RESULTS_PER_REQUEST) {
            appendBatch(writer, batch, stats);
            postProgress(
                    stats,
                    "Mengambil data... "
                            + stats.rows
                            + " baris • "
                            + stats.requests
                            + " permintaan"
            );
            return;
        }

        // The API returned the maximum number of rows. Do not append this
        // potentially truncated batch. Split it and fetch both halves instead.
        Duration duration = Duration.between(start, end);
        long seconds = duration.getSeconds();

        if (seconds <= 2) {
            throw new Exception(
                    "Terdapat 8.000 entry dalam interval yang sangat rapat. "
                            + "ThingSpeak membatasi 8.000 hasil per permintaan, "
                            + "dan interval ini sudah terlalu kecil untuk dipecah dengan presisi detik."
            );
        }

        long half = Math.max(1L, seconds / 2L);
        LocalDateTime mid = start.plusSeconds(half);

        postProgress(
                stats,
                "Rentang > 8.000 entry, memecah interval..."
        );

        // Both halves include the boundary. appendBatch() removes repeated
        // entry IDs, so a feed at exactly the split point is not duplicated.
        fetchIntervalAdaptive(
                channel, key, start, mid, writer, stats, depth + 1
        );
        fetchIntervalAdaptive(
                channel, key, mid, end, writer, stats, depth + 1
        );
    }

    private CsvBatch fetchCsvBatch(
            String channel,
            String key,
            LocalDateTime start,
            LocalDateTime end
    ) throws Exception {
        String startEncoded = URLEncoder.encode(
                start.format(API_FMT), "UTF-8"
        );
        String endEncoded = URLEncoder.encode(
                end.format(API_FMT), "UTF-8"
        );

        StringBuilder url = new StringBuilder(
                "https://api.thingspeak.com/channels/"
        );
        url.append(URLEncoder.encode(channel, "UTF-8"))
                .append("/feeds.csv?start=")
                .append(startEncoded)
                .append("&end=")
                .append(endEncoded)
                .append("&results=")
                .append(MAX_RESULTS_PER_REQUEST)
                .append("&timezone=Asia%2FJakarta");

        if (!key.isEmpty()) {
            url.append("&api_key=")
                    .append(URLEncoder.encode(key, "UTF-8"));
        }

        IOException lastIo = null;

        for (int attempt = 1; attempt <= NETWORK_RETRIES; attempt++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url.toString()).openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(READ_TIMEOUT_MS);
                c.setUseCaches(false);
                c.setRequestProperty("Accept", "text/csv");

                int code = c.getResponseCode();

                if (code != HttpURLConnection.HTTP_OK) {
                    String detail = readErrorBody(c);
                    if ((code == 429 || code >= 500) && attempt < NETWORK_RETRIES) {
                        backoff(attempt);
                        continue;
                    }
                    throw new Exception(
                            "HTTP " + code +
                                    (detail.isEmpty() ? "" : " • " + detail)
                    );
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                c.getInputStream(),
                                StandardCharsets.UTF_8
                        )
                )) {
                    String header = null;
                    List<String> lines = new ArrayList<>();

                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;

                        if (header == null) {
                            if ("-1".equals(line.trim())) {
                                throw new Exception(
                                        "ThingSpeak menolak akses channel. Periksa Channel ID dan Read API Key."
                                );
                            }
                            header = line;
                            continue;
                        }

                        // ThingSpeak returns "-1" when the channel is not
                        // accessible. Check it before treating the first row
                        // as valid data.
                        if ("-1".equals(line.trim())) {
                            throw new Exception(
                                    "ThingSpeak menolak akses channel. Periksa Channel ID dan Read API Key."
                            );
                        }

                        lines.add(line);
                        if (lines.size() > MAX_RESULTS_PER_REQUEST) {
                            // Defensive guard; ThingSpeak should never exceed it.
                            throw new Exception(
                                    "Respons ThingSpeak melebihi batas 8.000 entry."
                            );
                        }
                    }

                    return new CsvBatch(header, lines);
                }

            } catch (IOException io) {
                lastIo = io;
                if (attempt >= NETWORK_RETRIES) {
                    throw io;
                }
                backoff(attempt);
            } finally {
                if (c != null) c.disconnect();
            }
        }

        throw (lastIo != null)
                ? lastIo
                : new Exception("Gagal mengakses ThingSpeak.");
    }

    private void appendBatch(
            Writer writer,
            CsvBatch batch,
            ExportStats stats
    ) throws IOException {
        if (!stats.headerWritten && batch.header != null) {
            writer.write(batch.header);
            writer.write("\r\n");
            stats.headerWritten = true;
        }

        for (String line : batch.lines) {
            Long entryId = parseEntryId(line);

            // The adaptive splitter intentionally overlaps split boundaries.
            // Keep a bounded recent-ID cache so the same boundary entry is not
            // written twice, without assuming entry_id is globally monotonic
            // with created_at (back-filled ThingSpeak entries can violate that).
            if (entryId != null) {
                if (stats.recentEntryIds.contains(entryId)) {
                    continue;
                }
                stats.recentEntryIds.add(entryId);
                if (stats.recentEntryIds.size() > RECENT_ID_CACHE_LIMIT) {
                    Iterator<Long> it = stats.recentEntryIds.iterator();
                    if (it.hasNext()) {
                        it.next();
                        it.remove();
                    }
                }
            }

            writer.write(line);
            writer.write("\r\n");
            stats.rows++;
        }
    }

    private Long parseEntryId(String csvLine) {
        String col = extractCsvColumn(csvLine, 1); // created_at = 0, entry_id = 1
        if (col == null || col.isEmpty()) return null;
        try {
            return Long.parseLong(col.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Small CSV parser sufficient for extracting one column while respecting
     * double-quoted CSV fields.
     */
    private String extractCsvColumn(String line, int targetColumn) {
        boolean quoted = false;
        StringBuilder field = new StringBuilder();
        int column = 0;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);

            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
                continue;
            }

            if (ch == ',' && !quoted) {
                if (column == targetColumn) return field.toString();
                column++;
                field.setLength(0);
                continue;
            }

            if (column == targetColumn) {
                field.append(ch);
            }
        }

        return column == targetColumn ? field.toString() : null;
    }

    private String readErrorBody(HttpURLConnection c) {
        InputStream stream = null;
        try {
            stream = c.getErrorStream();
            if (stream == null) return "";

            StringBuilder b = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = r.readLine()) != null) {
                    b.append(line);
                    if (b.length() > 500) break;
                }
            }
            return b.toString().trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(400L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void postProgress(ExportStats stats, String message) {
        final long rows = stats.rows;
        final int requests = stats.requests;

        main.post(() -> statusView.setText(
                message + " • " + rows + " baris • " + requests + " permintaan"
        ));
    }

    private void openSaveDocument() {
        if (pendingCsvFile == null || !pendingCsvFile.isFile()) {
            readyToSave = false;
            downloadButton.setText("UNDUH DATA CSV");
            statusView.setText("File sementara sudah tidak tersedia. Silakan unduh ulang.");
            return;
        }

        startActivityForResult(
                new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("text/csv")
                        .putExtra(Intent.EXTRA_TITLE, pendingFileName),
                4101
        );
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != 4101 || resultCode != RESULT_OK || data == null) {
            if (requestCode == 4101 && resultCode != RESULT_OK) {
                statusView.setText(
                        "Penyimpanan dibatalkan. Tekan SIMPAN FILE CSV untuk memilih lokasi lagi."
                );
            }
            return;
        }

        Uri uri = data.getData();
        if (uri == null || pendingCsvFile == null || !pendingCsvFile.isFile()) {
            statusView.setText("Gagal: lokasi atau file CSV tidak tersedia.");
            return;
        }

        File source = pendingCsvFile;

        try (InputStream in = new FileInputStream(source);
             OutputStream out = getContentResolver().openOutputStream(uri)) {

            if (out == null) {
                throw new Exception("Lokasi penyimpanan tidak tersedia.");
            }

            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            out.flush();

            statusView.setText(
                    "CSV berhasil disimpan: " + pendingFileName
            );
            Toast.makeText(
                    this,
                    "CSV berhasil disimpan.",
                    Toast.LENGTH_SHORT
            ).show();

            try { source.delete(); } catch (Exception ignored) {}
            pendingCsvFile = null;
            pendingFileName = null;
            readyToSave = false;
            downloadButton.setText("UNDUH DATA CSV");

        } catch (Exception ex) {
            statusView.setText(
                    "Gagal menyimpan CSV: " + safeMessage(ex)
            );
        }
    }

    private String safeMessage(Exception ex) {
        String msg = ex.getMessage();
        return (msg == null || msg.trim().isEmpty())
                ? ex.getClass().getSimpleName()
                : msg;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        net.shutdownNow();

        // A prepared file is only a temporary cache. It is deleted after the
        // user saves successfully or when the activity is destroyed.
        if (pendingCsvFile != null) {
            try { pendingCsvFile.delete(); } catch (Exception ignored) {}
            pendingCsvFile = null;
        }
    }

    private static class CsvBatch {
        final String header;
        final List<String> lines;

        CsvBatch(String header, List<String> lines) {
            this.header = header;
            this.lines = lines;
        }
    }

    private static class ExportStats {
        long rows = 0;
        int requests = 0;
        boolean headerWritten = false;
        final LinkedHashSet<Long> recentEntryIds = new LinkedHashSet<>();
    }
}
