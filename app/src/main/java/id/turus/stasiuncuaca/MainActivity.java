package id.turus.stasiuncuaca;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "thingspeak_config";
    private static final String DEFAULT_CHANNEL = "";
    private static final String DEFAULT_READ_KEY = "";
    private static final String DEFAULT_TITLE = "STASIUN CUACA";
    private static final String DEFAULT_AI_MODEL = "gpt-6-luna";
    private static final long REFRESH_MS = 30_000L;
    private static final long WEATHER_REFRESH_MS = 10 * 60_000L;
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private android.content.SharedPreferences prefs;

    private TextView titleView, statusChip, lastAccess, deviceDate, deviceClock, dataTime,
            channelView, finalStatus, aiAdvice, aiStatus;
    private TextView weatherStatus, weatherLocation, weatherTemp, weatherFeels, weatherDew, weatherWind, weatherWindDir, weatherRain,
            weatherCloud, weatherPressure, weatherHumidity, weatherUv, weatherVisibility, weatherEt0, weatherCondition,
            weatherSunrise, weatherSunset, weatherAlerts, weatherForecast, weatherUpdated;
    private TextView[] fieldLabels = new TextView[8];
    private TextView[] fieldValues = new TextView[8];
    private TextView[] fieldUnits = new TextView[8];
    private String[] latestValues = new String[8];
    private String latestCreatedAt = "";
    private boolean requestRunning = false;
    private boolean aiRunning = false;
    private long lastAccessEpoch = 0L;
    private long lastWeatherEpoch = 0L;

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            updateClock();
            main.postDelayed(this, 1000L);
        }
    };

    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            loadThingSpeak(false);
            main.postDelayed(this, REFRESH_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        bindViews();
        bindWeatherViews();
        buildFieldCards();
        findViewById(R.id.settings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.refresh).setOnClickListener(v -> loadThingSpeak(true));
        findViewById(R.id.downloadCsv).setOnClickListener(v -> startActivity(new Intent(this, CsvDownloadActivity.class)));
        findViewById(R.id.aiButton).setOnClickListener(v -> requestAiAdvice());
        findViewById(R.id.weatherRefresh).setOnClickListener(v -> loadOpenWeather(true));
        updateHeaderFromConfig();
        updateFieldLabels();
        updateClock();
        loadCachedData();
        loadOpenWeather(false);
    }

    @Override protected void onResume() {
        super.onResume();
        updateHeaderFromConfig();
        updateFieldLabels();
        loadCachedData();
        loadThingSpeak(false);
        main.removeCallbacks(clockTick);
        main.removeCallbacks(refreshTick);
        main.post(clockTick);
        main.postDelayed(refreshTick, REFRESH_MS);
        loadOpenWeather(false);
    }

    @Override protected void onPause() {
        super.onPause();
        main.removeCallbacks(clockTick);
        main.removeCallbacks(refreshTick);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        net.shutdownNow();
    }

    private void bindViews() {
        titleView = findViewById(R.id.title);
        statusChip = findViewById(R.id.statusChip);
        lastAccess = findViewById(R.id.lastAccess);
        deviceDate = findViewById(R.id.deviceDate);
        deviceClock = findViewById(R.id.deviceClock);
        dataTime = findViewById(R.id.dataTime);
        channelView = findViewById(R.id.channelView);
        finalStatus = findViewById(R.id.finalStatus);
        aiAdvice = findViewById(R.id.aiAdvice);
        aiStatus = findViewById(R.id.aiStatus);
        findViewById(R.id.aiButton).setEnabled(true);
    }

    private void bindWeatherViews() {
        weatherStatus = findViewById(R.id.weatherStatus);
        weatherLocation = findViewById(R.id.weatherLocation);
        weatherTemp = findViewById(R.id.weatherTemp);
        weatherFeels = findViewById(R.id.weatherFeels);
        weatherDew = findViewById(R.id.weatherDew);
        weatherWind = findViewById(R.id.weatherWind);
        weatherWindDir = findViewById(R.id.weatherWindDir);
        weatherRain = findViewById(R.id.weatherRain);
        weatherCloud = findViewById(R.id.weatherCloud);
        weatherPressure = findViewById(R.id.weatherPressure);
        weatherHumidity = findViewById(R.id.weatherHumidity);
        weatherUv = findViewById(R.id.weatherUv);
        weatherVisibility = findViewById(R.id.weatherVisibility);
        weatherEt0 = findViewById(R.id.weatherEt0);
        weatherCondition = findViewById(R.id.weatherCondition);
        weatherSunrise = findViewById(R.id.weatherSunrise);
        weatherSunset = findViewById(R.id.weatherSunset);
        weatherAlerts = findViewById(R.id.weatherAlerts);
        weatherForecast = findViewById(R.id.weatherForecast);
        weatherUpdated = findViewById(R.id.weatherUpdated);
    }

    private void loadOpenWeather(boolean manual) {
        String key = prefs.getString("openweather_key", "").trim();
        if (key.isEmpty()) {
            weatherStatus.setText("API KEY BELUM DIISI");
            weatherStatus.setTextColor(Color.rgb(243,182,74));
            weatherLocation.setText("Masukkan OpenWeather One Call API 4.0 API Key pada Pengaturan.");
            return;
        }
        long now = System.currentTimeMillis();
        if (!manual && now - lastWeatherEpoch < WEATHER_REFRESH_MS) return;
        lastWeatherEpoch = now;
        if (manual) weatherStatus.setText("MENGAMBIL DATA OPENWEATHER 4.0...");
        net.execute(() -> {
            try {
                Location loc = getBestLocation();
                if (loc == null) {
                    main.post(() -> { weatherStatus.setText("GPS BELUM TERSEDIA"); weatherLocation.setText("Aktifkan lokasi HP lalu tekan PERBARUI."); });
                    return;
                }
                String params = "lat=" + loc.getLatitude() + "&lon=" + loc.getLongitude() + "&units=metric&lang=id&appid=" + URLEncoder.encode(key, "UTF-8");
                JSONObject currentResponse = getJson("https://api.openweathermap.org/data/4.0/onecall/current?" + params);
                JSONObject dailyResponse = getJson("https://api.openweathermap.org/data/4.0/onecall/timeline/1day?" + params);
                JSONArray currentData = currentResponse.optJSONArray("data");
                JSONArray dailyData = dailyResponse.optJSONArray("data");
                JSONObject cur = currentData == null ? null : currentData.optJSONObject(0);
                if (cur == null) throw new Exception("OpenWeather 4.0 tidak mengembalikan data current");
                double lat = currentResponse.optDouble("lat", loc.getLatitude());
                double lon = currentResponse.optDouble("lon", loc.getLongitude());
                String place = "GPS " + String.format(Locale.US, "%.5f, %.5f", lat, lon);
                double temp = cur.optDouble("temp", Double.NaN);
                double feels = cur.optDouble("feels_like", Double.NaN);
                double dew = cur.optDouble("dew_point", Double.NaN);
                double rh = cur.optDouble("humidity", Double.NaN);
                double pressure = cur.optDouble("pressure", Double.NaN);
                double cloud = cur.optDouble("clouds", Double.NaN);
                double uv = cur.optDouble("uvi", Double.NaN);
                double vis = cur.optDouble("visibility", Double.NaN);
                double ws = cur.optDouble("wind_speed", Double.NaN);
                double wg = cur.optDouble("wind_gust", Double.NaN);
                double wd = cur.optDouble("wind_deg", Double.NaN);
                JSONObject rainObj = cur.optJSONObject("rain");
                double rain = rainObj == null ? 0 : rainObj.optDouble("1h", 0);
                JSONObject snowObj = cur.optJSONObject("snow");
                double snow = snowObj == null ? 0 : snowObj.optDouble("1h", 0);
                JSONArray weatherArr = cur.optJSONArray("weather");
                JSONObject wx = weatherArr == null ? null : weatherArr.optJSONObject(0);
                String condition = wx == null ? "--" : wx.optString("description", wx.optString("main", "--"));
                String forecast = buildForecast4(dailyData);
                double elevation = getElevationFromOpenMeteo(lat, lon);
                if (!Double.isFinite(elevation) || elevation < -500 || elevation > 9000) {
                    elevation = prefs.getFloat("last_elevation_m", Float.NaN);
                }
                if (Double.isFinite(elevation)) {
                    prefs.edit().putFloat("last_elevation_m", (float)elevation).apply();
                }
                double et0 = calculateDailyEt0_4(dailyData, lat, Double.isFinite(elevation) ? elevation : 0);
                String windText = fmt(ws,1)+" m/s ("+fmt(ws*3.6,1)+" km/j)" + (Double.isFinite(wg)?" • gust "+fmt(wg,1)+" m/s":"");
                String dir = Double.isFinite(wd) ? fmt(wd,0)+"° "+windDirection(wd) : "--";
                String rainText = fmt(rain,1)+" mm/jam" + (snow > 0 ? " • salju "+fmt(snow,1)+" mm/jam" : "");
                String sunrise = formatEpoch(cur.optLong("sunrise",0));
                String sunset = formatEpoch(cur.optLong("sunset",0));
                JSONArray alerts = cur.optJSONArray("alerts");
                String alertText = alerts == null || alerts.length()==0 ? "Tidak ada ID peringatan aktif" : alerts.length()+" peringatan aktif";
                String updated = java.time.ZonedDateTime.now(WIB).format(DateTimeFormatter.ofPattern("dd/MM HH:mm:ss", Locale.US));
                main.post(() -> {
                    weatherStatus.setText("ONLINE • OPENWEATHER ONE CALL 4.0"); weatherStatus.setTextColor(Color.rgb(69,212,131));
                    weatherLocation.setText(place + " • elevasi DEM " + (Double.isFinite(elevation) ? fmt(elevation,0) + " mdpl" : "--") + " • GPS altitude HP diabaikan");
                    weatherTemp.setText("Suhu "+fmt(temp,1)+" °C"); weatherFeels.setText("Terasa "+fmt(feels,1)+" °C"); weatherDew.setText("Titik embun "+fmt(dew,1)+" °C");
                    weatherHumidity.setText("RH "+fmt(rh,0)+" %"); weatherPressure.setText("Tekanan "+fmt(pressure,0)+" hPa"); weatherCloud.setText("Awan "+fmt(cloud,0)+" %");
                    weatherUv.setText("UV "+fmt(uv,1)); weatherVisibility.setText("Visibilitas "+fmt(vis/1000.0,1)+" km");
                    weatherWind.setText("Angin "+windText); weatherWindDir.setText("Arah "+dir); weatherRain.setText("Hujan "+rainText);
                    weatherEt0.setText("ET₀ "+(Double.isFinite(et0) ? fmt(et0,2)+" mm/hari" : "--")); weatherCondition.setText("Kondisi: "+condition);
                    weatherSunrise.setText("Terbit "+sunrise); weatherSunset.setText("Terbenam "+sunset); weatherAlerts.setText("Peringatan: "+alertText);
                    weatherForecast.setText("Prakiraan harian: "+forecast); weatherUpdated.setText("Pembaruan: "+updated+" • sumber OpenWeather 4.0");
                });
            } catch (Exception ex) {
                main.post(() -> { weatherStatus.setText("OPENWEATHER GAGAL"); weatherStatus.setTextColor(Color.rgb(255,107,107)); weatherLocation.setText(ex.getMessage() == null ? "Tidak dapat mengambil data." : ex.getMessage()); });
            }
        });
    }
/**
 * HTTP GET helper for JSON APIs used by OpenWeather 4.0 and elevation services.
 * Runs on the background ExecutorService, so network I/O never blocks the UI thread.
 */
private JSONObject getJson(String urlString) throws Exception {
    HttpURLConnection connection = null;

    try {
        URL url = new URL(urlString);
        connection = (HttpURLConnection) url.openConnection();

        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");

        int responseCode = connection.getResponseCode();

        InputStream stream =
                (responseCode >= 200 && responseCode < 300)
                        ? connection.getInputStream()
                        : connection.getErrorStream();

        String body = readAll(stream);

        if (responseCode < 200 || responseCode >= 300) {
            String detail = body == null ? "" : body.trim();

            if (detail.length() > 500) {
                detail = detail.substring(0, 500);
            }

            throw new IOException(
                    "HTTP " + responseCode +
                    (detail.isEmpty() ? "" : " — " + detail)
            );
        }

        if (body == null || body.trim().isEmpty()) {
            throw new IOException("Respons JSON kosong.");
        }

        return new JSONObject(body);

    } finally {
        if (connection != null) {
            connection.disconnect();
        }
    }
}
    /**
     * Terrain elevation from Open-Meteo Elevation API (Copernicus GLO-90, ~90 m).
     * We intentionally do not use Location.getAltitude() because many phones return
     * 0 m or an unreliable vertical fix even when latitude/longitude are valid.
     */
    private double getElevationFromOpenMeteo(double lat, double lon) {
        try {
            String url = "https://api.open-meteo.com/v1/elevation?latitude="
                    + URLEncoder.encode(String.format(Locale.US, "%.6f", lat), "UTF-8")
                    + "&longitude="
                    + URLEncoder.encode(String.format(Locale.US, "%.6f", lon), "UTF-8");
            JSONObject o = getJson(url);
            JSONArray a = o.optJSONArray("elevation");
            if (a == null || a.length() == 0) return Double.NaN;
            return a.optDouble(0, Double.NaN);
        } catch (Exception ignored) {
            return Double.NaN;
        }
    }

    private String formatEpoch(long epoch) {
        if (epoch <= 0) return "--";
        return Instant.ofEpochSecond(epoch).atZone(WIB).format(DateTimeFormatter.ofPattern("HH:mm", Locale.US));
    }

    private Location getBestLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 501); return null;
        }
        LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE); Location best=null;
        try { if(lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) best=lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch(Exception ignored) {}
        try { Location n=lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); if(best==null || (n!=null && n.getTime()>best.getTime())) best=n; } catch(Exception ignored) {}
        return best;
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){ super.onRequestPermissionsResult(requestCode,permissions,grantResults); if(requestCode==501) main.postDelayed(()->loadOpenWeather(true),800); }

    private static String fmt(double v,int d){ return Double.isFinite(v)?String.format(Locale.US,"%."+d+"f",v):"--"; }
    private static String windDirection(double d){ String[] a={"N","NE","E","SE","S","SW","W","NW"}; return a[(int)Math.floor((d+22.5)/45.0)%8]; }

    private String buildForecast4(JSONArray a){
        if(a==null||a.length()==0)return "data prakiraan harian tidak tersedia.";
        StringBuilder b=new StringBuilder();
        for(int i=0;i<Math.min(5,a.length());i++){
            JSONObject x=a.optJSONObject(i); if(x==null)continue;
            long t=x.optLong("dt",0); JSONObject temp=x.optJSONObject("temp");
            double tmin=temp==null?Double.NaN:temp.optDouble("min",Double.NaN), tmax=temp==null?Double.NaN:temp.optDouble("max",Double.NaN);
            int pop=(int)Math.round(x.optDouble("pop",0)*100);
            JSONArray wa=x.optJSONArray("weather"); JSONObject w=wa==null?null:wa.optJSONObject(0);
            String desc=w==null?"":w.optString("description","");
            if(i>0)b.append("  |  ");
            String day=t>0?Instant.ofEpochSecond(t).atZone(WIB).format(DateTimeFormatter.ofPattern("EEE dd/MM",new Locale("id","ID"))):"hari";
            b.append(day).append(" ").append(fmt(tmin,0)).append("–").append(fmt(tmax,0)).append("°C").append(" ").append(pop).append("% hujan").append(desc.isEmpty()?"":" "+desc);
        }
        return b.toString();
    }

    private double calculateDailyEt0_4(JSONArray a,double lat,double altitude){
        try{
            if(a==null||a.length()==0)return Double.NaN; JSONObject d=a.optJSONObject(0); if(d==null)return Double.NaN;
            JSONObject t=d.optJSONObject("temp"); if(t==null)return Double.NaN;
            double tmin=t.optDouble("min",Double.NaN), tmax=t.optDouble("max",Double.NaN), tday=t.optDouble("day",Double.NaN);
            if(!Double.isFinite(tday))tday=(tmin+tmax)/2.0;
            double rh=d.optDouble("humidity",Double.NaN); double u=d.optDouble("wind_speed",2.0); double cloud=d.optDouble("clouds",50);
            if(!Double.isFinite(tmin)||!Double.isFinite(tmax)||!Double.isFinite(tday)||!Double.isFinite(rh))return Double.NaN;
            long dt=d.optLong("dt",System.currentTimeMillis()/1000); int J=Instant.ofEpochSecond(dt).atZone(WIB).getDayOfYear();
            double phi=Math.toRadians(lat), dr=1+0.033*Math.cos(2*Math.PI*J/365.0), delta=0.409*Math.sin(2*Math.PI*J/365.0-1.39);
            double ws=Math.acos(Math.max(-1,Math.min(1,-Math.tan(phi)*Math.tan(delta))));
            double ra=(24*60/Math.PI)*0.0820*dr*(ws*Math.sin(phi)*Math.sin(delta)+Math.cos(phi)*Math.cos(delta)*Math.sin(ws));
            double rso=(0.75+2e-5*Math.max(0,altitude))*ra; double rs=Math.max(0,rso*(1-0.75*Math.pow(cloud/100.0,3)));
            double rnl=4.903e-9*((Math.pow(tmax+273.16,4)+Math.pow(tmin+273.16,4))/2)*(0.34-0.14*Math.sqrt(Math.max(0.05,rh/100.0)))*(1.35*Math.min(1,rs/Math.max(0.01,rso))-0.35);
            double rn=0.77*rs-rnl, es=(sat(tmax)+sat(tmin))/2, ea=sat(tday)*rh/100.0;
            double ds=4098*sat(tday)/Math.pow(tday+237.3,2), gamma=0.000665*(101.3-0.0065*Math.max(0,altitude))/(tday+273.0);
            double et=(0.408*ds*rn+gamma*(900/(tday+273))*u*(es-ea))/(ds+gamma*(1+0.34*u)); return Math.max(0,et);
        }catch(Exception e){return Double.NaN;}
    }
    private static double sat(double t){return 0.6108*Math.exp(17.27*t/(t+237.3));}

    private void buildFieldCards() {
        GridLayout grid = findViewById(R.id.grid);
        for (int i = 0; i < 8; i++) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(13), dp(14), dp(13));
            card.setBackgroundResource(R.drawable.bg_panel);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = dp(112);
            lp.columnSpec = GridLayout.spec(i % 2, 1f);
            lp.rowSpec = GridLayout.spec(i / 2);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            card.setLayoutParams(lp);

            TextView label = new TextView(this);
            label.setText("FIELD " + (i + 1));
            label.setTextColor(Color.rgb(41, 198, 199));
            label.setTextSize(10);
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            label.setLetterSpacing(.08f);
            fieldLabels[i] = label;

            TextView value = new TextView(this);
            value.setText("--");
            value.setTextColor(Color.WHITE);
            value.setTextSize(24);
            value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            value.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-1, 0, 1f);
            value.setLayoutParams(vlp);

            TextView foot = new TextView(this);
            foot.setText("");
            foot.setTextColor(Color.rgb(157,176,188));
            foot.setTextSize(10);

            card.addView(label);
            card.addView(value);
            card.addView(foot);
            grid.addView(card);
            fieldValues[i] = value;
            fieldUnits[i] = foot;
        }
    }

    private void updateHeaderFromConfig() {
        String ch = prefs.getString("channel", DEFAULT_CHANNEL);
        channelView.setText(ch == null || ch.trim().isEmpty() ? "CHANNEL: --" : "CHANNEL: " + ch.trim());
        String title = prefs.getString("app_title", DEFAULT_TITLE).trim();
        titleView.setText(title.isEmpty() ? DEFAULT_TITLE : title);
    }

    private void updateFieldLabels() {
        for (int i = 0; i < 8; i++) {
            String name = getDisplayFieldName(i);
            String unit = prefs.getString("field_unit_" + (i + 1), "").trim();
            fieldLabels[i].setText(name.toUpperCase(new Locale("id", "ID")));
            fieldUnits[i].setText(unit);
        }
        applyValues(latestValues);
    }

    private String getDisplayFieldName(int i) {
        String custom = prefs.getString("field_name_" + (i + 1), "").trim();
        if (!custom.isEmpty()) return custom;
        String ts = prefs.getString("ts_field_name_" + (i + 1), "").trim();
        return ts.isEmpty() ? "FIELD " + (i + 1) : ts;
    }

    private void updateClock() {
        var now = java.time.ZonedDateTime.now(WIB);
        deviceDate.setText(capitalize(now.format(DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy", new Locale("id", "ID")))));
        deviceClock.setText(now.format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)) + " WIB");
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void loadThingSpeak(boolean manual) {
        if (requestRunning) return;
        String channel = prefs.getString("channel", DEFAULT_CHANNEL).trim();
        String key = prefs.getString("read_key", DEFAULT_READ_KEY).trim();
        if (channel.isEmpty()) {
            setUiStatus("KONFIGURASI", Color.rgb(243,182,74), "Masukkan Channel ID pada Pengaturan.");
            return;
        }
        if (manual) finalStatus.setText("Mengambil data dari ThingSpeak...");
        requestRunning = true;
        net.execute(() -> {
            HttpURLConnection c = null;
            try {
                StringBuilder url = new StringBuilder("https://api.thingspeak.com/channels/")
                        .append(URLEncoder.encode(channel, "UTF-8"))
                        .append("/feeds/last.json?timezone=Asia%2FJakarta&status=true");
                if (!key.isEmpty()) url.append("&api_key=").append(URLEncoder.encode(key, "UTF-8"));
                c = (HttpURLConnection) new URL(url.toString()).openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(7000);
                c.setReadTimeout(7000);
                c.setUseCaches(false);
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                String body = readAll(c.getInputStream());
                JSONObject obj = new JSONObject(body);
                lastAccessEpoch = System.currentTimeMillis();
                String[] vals = new String[8];
                for (int i = 0; i < 8; i++) vals[i] = obj.optString("field" + (i + 1), "");
                String createdAt = obj.optString("created_at", "");
                String thingStatus = obj.optString("status", "");
                latestValues = vals;
                latestCreatedAt = createdAt;
                prefs.edit()
                        .putString("cache_json", obj.toString())
                        .putLong("last_access", lastAccessEpoch)
                        .apply();

                boolean needMetadata = !channel.equals(prefs.getString("field_meta_channel", ""));
                if (needMetadata) {
                    try { fetchAndCacheChannelMetadata(channel, key); } catch (Exception ignored) { }
                }

                runOnUiThread(() -> {
                    updateFieldLabels();
                    dataTime.setText("Data ThingSpeak: " + formatThingSpeakTime(createdAt));
                    lastAccess.setText("Akses terakhir: " + formatLocal(lastAccessEpoch));
                    setUiStatus("ONLINE • DATA TERSEDIA", Color.rgb(69,212,131), thingStatus.isEmpty() ? "Data ThingSpeak berhasil dibaca." : thingStatus);
                    requestRunning = false;
                });
            } catch (Exception ex) {
                long cacheAccess = prefs.getLong("last_access", 0L);
                JSONObject cached = null;
                try { String s = prefs.getString("cache_json", ""); if (!s.isEmpty()) cached = new JSONObject(s); } catch (Exception ignored) {}
                JSONObject finalCached = cached;
                runOnUiThread(() -> {
                    if (finalCached != null) {
                        String[] vals = new String[8];
                        for (int i = 0; i < 8; i++) vals[i] = finalCached.optString("field" + (i + 1), "");
                        latestValues = vals;
                        latestCreatedAt = finalCached.optString("created_at", "");
                        updateFieldLabels();
                        dataTime.setText("Data ThingSpeak: " + formatThingSpeakTime(latestCreatedAt));
                        lastAccess.setText(cacheAccess > 0 ? "Akses terakhir: " + formatLocal(cacheAccess) : "Akses terakhir: --");
                        setUiStatus("OFFLINE • CACHE", Color.rgb(243,182,74), "Koneksi gagal. Menampilkan data terakhir yang tersimpan.");
                    } else {
                        setUiStatus("OFFLINE", Color.rgb(255,107,107), "Gagal mengakses ThingSpeak: " + ex.getMessage());
                    }
                    requestRunning = false;
                });
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    private void fetchAndCacheChannelMetadata(String channel, String key) throws Exception {
        StringBuilder url = new StringBuilder("https://api.thingspeak.com/channels/")
                .append(URLEncoder.encode(channel, "UTF-8"))
                .append(".json");
        if (!key.isEmpty()) url.append("?api_key=").append(URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url.toString()).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(7000);
            c.setReadTimeout(7000);
            c.setUseCaches(false);
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            JSONObject channelObj = new JSONObject(readAll(c.getInputStream()));
            android.content.SharedPreferences.Editor e = prefs.edit();
            for (int i = 0; i < 8; i++) {
                String n = channelObj.optString("field" + (i + 1), "").trim();
                e.putString("ts_field_name_" + (i + 1), n);
            }
            e.putString("field_meta_channel", channel);
            e.apply();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void loadCachedData() {
        long last = prefs.getLong("last_access", 0L);
        String cached = prefs.getString("cache_json", "");
        if (last > 0) lastAccess.setText("Akses terakhir: " + formatLocal(last));
        if (cached.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(cached);
            String[] vals = new String[8];
            for (int i = 0; i < 8; i++) vals[i] = o.optString("field" + (i + 1), "");
            latestValues = vals;
            latestCreatedAt = o.optString("created_at", "");
            updateFieldLabels();
            dataTime.setText("Data ThingSpeak: " + formatThingSpeakTime(latestCreatedAt));
            setUiStatus("CACHE", Color.rgb(243,182,74), "Menampilkan data terakhir sampai koneksi diperbarui.");
        } catch (Exception ignored) { }
    }

    private void applyValues(String[] vals) {
        if (vals == null) return;
        int decimals = getDecimals();
        for (int i = 0; i < 8; i++) {
            String v = i < vals.length ? vals[i] : "";
            fieldValues[i].setText(formatDisplayValue(v, decimals));
        }
    }

    private String formatDisplayValue(String raw, int decimals) {
        if (raw == null || raw.trim().isEmpty()) return "--";
        String s = raw.trim();
        try {
            double d = Double.parseDouble(s);
            NumberFormat nf = NumberFormat.getNumberInstance(new Locale("id", "ID"));
            nf.setGroupingUsed(false);
            nf.setMinimumFractionDigits(decimals);
            nf.setMaximumFractionDigits(decimals);
            return nf.format(d);
        } catch (Exception ignored) {
            return s;
        }
    }

    private int getDecimals() {
        int d = prefs.getInt("display_decimals", 2);
        return Math.max(0, Math.min(2, d));
    }

    private void setUiStatus(String chip, int color, String msg) {
        statusChip.setText(chip);
        statusChip.setTextColor(Color.WHITE);
        statusChip.setBackgroundTintList(android.content.res.ColorStateList.valueOf(darken(color)));
        finalStatus.setText(msg);
    }

    private void requestAiAdvice() {
        if (aiRunning) return;
        String apiKey = prefs.getString("ai_api_key", "").trim();
        if (apiKey.isEmpty()) {
            aiAdvice.setText("Masukkan API key AI pada Pengaturan terlebih dahulu.");
            aiStatus.setText("AI belum dikonfigurasi");
            return;
        }
        String channel = prefs.getString("channel", "").trim();
        if (channel.isEmpty()) {
            aiAdvice.setText("Atur Channel ID terlebih dahulu agar data cuaca dapat dibaca.");
            return;
        }
        aiRunning = true;
        aiStatus.setText("AI sedang membaca data pengukuran...");
        findViewById(R.id.aiButton).setEnabled(false);
        final String[] snapshot = latestValues.clone();
        final String createdAt = latestCreatedAt;
        final String title = prefs.getString("app_title", DEFAULT_TITLE);
        final String crop = prefs.getString("crop", "Tanaman pertanian").trim();
        final String model = prefs.getString("ai_model", DEFAULT_AI_MODEL).trim().isEmpty()
                ? DEFAULT_AI_MODEL : prefs.getString("ai_model", DEFAULT_AI_MODEL).trim();

        net.execute(() -> {
            try {
                String advice = callOpenAI(apiKey, model, title, channel, crop, createdAt, snapshot);
                runOnUiThread(() -> {
                    aiAdvice.setText(advice);
                    aiStatus.setText("Saran dibuat dari data terakhir • " + formatThingSpeakTime(createdAt));
                    aiRunning = false;
                    findViewById(R.id.aiButton).setEnabled(true);
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    aiAdvice.setText("Saran AI gagal dibuat. Periksa koneksi internet, API key, dan nama model AI pada Pengaturan.\n\nPesan: " + safeMessage(ex));
                    aiStatus.setText("AI tidak tersedia");
                    aiRunning = false;
                    findViewById(R.id.aiButton).setEnabled(true);
                });
            }
        });
    }

    private String callOpenAI(String apiKey, String model, String title, String channel,
                              String crop, String createdAt, String[] vals) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("model", model);
        payload.put("instructions",
                "Anda adalah asisten agronomi untuk membantu petani mengambil tindakan lapang. " +
                "Gunakan bahasa Indonesia yang sangat mudah dipahami. Hindari singkatan dan istilah teknis yang tidak perlu. " +
                "Jangan mengarang data yang tidak diberikan. Jika suatu kesimpulan membutuhkan data yang tidak tersedia, katakan dengan jelas. " +
                "Berikan saran praktis berdasarkan data cuaca yang tersedia, bukan diagnosis penyakit yang pasti. " +
                "Prioritaskan tindakan yang bisa dilakukan petani sekarang, kemudian 6-24 jam ke depan, dan apa yang perlu dipantau. " +
                "Bila data belum cukup, minta pengecekan lapangan secara singkat. " +
                "Jawaban maksimal sekitar 8 poin pendek, tanpa pembukaan panjang. " +
                "Bila menggunakan istilah teknis yang penting, langsung jelaskan dengan kata sederhana.");

        StringBuilder input = new StringBuilder();
        input.append("Konteks stasiun: ").append(title).append("\n");
        input.append("Channel ThingSpeak: ").append(channel).append("\n");
        input.append("Komoditas/tanaman: ").append(crop.isEmpty() ? "Tanaman pertanian" : crop).append("\n");
        input.append("Waktu data: ").append(formatThingSpeakTime(createdAt)).append("\n");
        input.append("Data terbaru:\n");
        for (int i = 0; i < 8; i++) {
            input.append(i + 1).append(". ").append(getDisplayFieldName(i)).append(" = ")
                    .append(formatDisplayValue(vals[i], getDecimals()));
            String unit = prefs.getString("field_unit_" + (i + 1), "").trim();
            if (!unit.isEmpty()) input.append(" ").append(unit);
            input.append("\n");
        }
        input.append("\nBuat rekomendasi tindakan petani berdasarkan data di atas. Jangan mengganti atau mengarang angka.");
        payload.put("input", input.toString());
        payload.put("max_output_tokens", 700);

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://api.openai.com/v1/responses").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(12000);
            c.setReadTimeout(30000);
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                String err = readAll(c.getErrorStream());
                throw new Exception("HTTP " + code + (err.isEmpty() ? "" : " — " + extractApiError(err)));
            }
            JSONObject response = new JSONObject(readAll(c.getInputStream()));
            String direct = response.optString("output_text", "").trim();
            if (!direct.isEmpty()) return direct;
            JSONArray output = response.optJSONArray("output");
            if (output != null) {
                StringBuilder result = new StringBuilder();
                for (int i = 0; i < output.length(); i++) {
                    JSONObject item = output.optJSONObject(i);
                    if (item == null) continue;
                    JSONArray content = item.optJSONArray("content");
                    if (content == null) continue;
                    for (int j = 0; j < content.length(); j++) {
                        JSONObject part = content.optJSONObject(j);
                        if (part == null) continue;
                        String type = part.optString("type", "");
                        if ("output_text".equals(type)) {
                            String t = part.optString("text", "").trim();
                            if (!t.isEmpty()) {
                                if (result.length() > 0) result.append("\n");
                                result.append(t);
                            }
                        }
                    }
                }
                if (result.length() > 0) return result.toString();
            }
            throw new Exception("Respons AI tidak berisi teks saran.");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String extractApiError(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject e = o.optJSONObject("error");
            if (e != null) return e.optString("message", json);
        } catch (Exception ignored) { }
        return json.length() > 300 ? json.substring(0, 300) : json;
    }

    private String safeMessage(Throwable ex) {
        String m = ex == null ? "Kesalahan tidak diketahui" : ex.getMessage();
        return m == null || m.isEmpty() ? "Kesalahan tidak diketahui" : m;
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private int darken(int c) {
        float factor = .72f;
        return Color.rgb((int)(Color.red(c)*factor), (int)(Color.green(c)*factor), (int)(Color.blue(c)*factor));
    }

    private String formatThingSpeakTime(String utc) {
        if (utc == null || utc.isEmpty()) return "--";
        try {
            return Instant.parse(utc).atZone(WIB).format(DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy HH:mm:ss", new Locale("id", "ID"))) + " WIB";
        } catch (Exception e) { return utc; }
    }

    private String formatLocal(long millis) {
        if (millis <= 0) return "--";
        return Instant.ofEpochMilli(millis).atZone(WIB).format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss", new Locale("id", "ID"))) + " WIB";
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
