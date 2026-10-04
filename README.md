# STASIUN CUACA DESA TURUS

Aplikasi Android ringan/native untuk membaca data terbaru dan mengunduh histori data ThingSpeak.

## Fitur Dashboard
- Judul STASIUN CUACA DESA TURUS.
- Channel ID dapat diubah.
- Read API Key dapat diubah dan ditampilkan/disembunyikan.
- Membaca `feeds/last.json` dari ThingSpeak.
- 8 field = 8 kotak data.
- Hari, tanggal, dan jam WIB berjalan real-time.
- Waktu data terakhir dari ThingSpeak.
- Waktu terakhir aplikasi mengakses ThingSpeak.
- Status ONLINE/OFFLINE/CACHE di bagian bawah.
- Cache data terakhir untuk kondisi jaringan putus.
- Refresh manual dan refresh otomatis setiap 30 detik.
- Tanpa library UI pihak ketiga.

## Export CSV ThingSpeak

Menu **UNDUH DATA CSV** memiliki dua pilihan:

### 1. RENTANG WAKTU
Pengguna memilih tanggal mulai dan tanggal akhir. Aplikasi mengambil semua entry dalam rentang tersebut dan menggabungkannya menjadi satu file CSV.

Permintaan menggunakan endpoint:
`https://api.thingspeak.com/channels/<CHANNEL_ID>/feeds.csv`

dengan parameter `start`, `end`, `results=8000`, `timezone=Asia/Jakarta`, dan `api_key` untuk channel privat.

### 2. SELURUH HISTORI
Mode ini dirancang untuk mengambil histori ThingSpeak **lebih dari 8.000 entry** sampai data terbaru, tanpa berhenti pada batch pertama.

ThingSpeak mendokumentasikan batas maksimum **8.000 entry per request**. Karena itu aplikasi:
1. Memulai dari rentang waktu awal yang aman.
2. Meminta data dengan `results=8000`.
3. Bila respons berisi kurang dari 8.000 entry, batch ditulis ke file.
4. Bila respons tepat 8.000 entry, aplikasi menganggap rentang tersebut dapat terpotong oleh batas API, lalu membelah rentang waktu menjadi dua.
5. Pemecahan dilakukan berulang sampai setiap batch aman untuk diekspor.
6. Boundary antar-batch sengaja sedikit overlap dan `entry_id` yang sudah muncul disaring melalui cache ID terbatas agar tidak terjadi duplikasi.
7. Semua batch ditulis langsung ke file sementara, sehingga seluruh histori **tidak ditampung sebagai byte array besar di RAM**.
8. Setelah selesai, aplikasi meminta lokasi penyimpanan melalui Android **Create Document** dan menyimpan satu file CSV.

Nama file mode seluruh histori:
`STASIUN-CUACA-DESA-TURUS_SELURUH-HISTORI.csv`

Nama file mode rentang:
`STASIUN-CUACA-DESA-TURUS_YYYYMMDD_YYYYMMDD.csv`

## Catatan
- Untuk channel privat, aplikasi membutuhkan **Read API Key channel**, bukan Write API Key.
- Jika Channel ID/Read API Key salah, ThingSpeak dapat menolak akses.
- Jika interval sangat padat sampai 8.000 entry berada dalam rentang yang lebih kecil daripada resolusi waktu API (detik), aplikasi berhenti dengan pesan agar rentang tanggal dipersempit.
- Mode seluruh histori menggunakan batas awal `2000-01-01`. Ini dimaksudkan untuk mencakup histori channel modern tanpa membutuhkan User API Key untuk membaca waktu pembuatan channel.
- File sementara berada di cache aplikasi dan dihapus setelah berhasil disimpan.

## Build
Gunakan Android Studio dengan Android SDK Platform 37, Android Gradle Plugin 9.4.0, Gradle 9.8.0, dan JDK 17. Jalankan `assembleDebug` untuk membuat APK debug.

## Referensi API
Dokumentasi resmi ThingSpeak/MathWorks:
- Read Data: `/channels/<channel_id>/feeds.<format>` dengan `results` maksimum 8.000 dan parameter `start`/`end`.
- Read Settings: `/channels/<channel_id>.<format>`.


## Penyesuaian v1.2

- Judul default dashboard menjadi **STASIUN CUACA** dan dapat diubah dari Pengaturan.
- Nama 8 field otomatis mengikuti nama field pada pengaturan channel ThingSpeak. Nama tampilan dapat dioverride per field tanpa mengubah data sumber.
- Satuan setiap field dapat diatur sendiri di aplikasi.
- Jumlah angka di layar dapat dipilih 0, 1, atau 2 angka di belakang koma; nilai CSV tetap tidak diubah.
- Ditambahkan **Saran AI** berbasis data pengukuran terbaru. AI dipanggil manual agar tidak berjalan setiap 30 detik. API key AI dimasukkan pengguna melalui Pengaturan.
- Prompt AI memprioritaskan bahasa Indonesia yang mudah dipahami, tindakan praktis petani, dan menghindari singkatan/istilah teknis yang tidak perlu.
- Ditambahkan pilihan komoditas/tanaman untuk memberi konteks pada saran AI.

### Catatan AI

Integrasi menggunakan OpenAI Responses API. OpenAI menyatakan Responses API adalah jalur API baru untuk integrasi respons model. API key bersifat rahasia; aplikasi ini menyimpannya di perangkat agar pengguna dapat menggunakannya tanpa menaruh key di source code. Untuk distribusi aplikasi ke banyak pengguna, arsitektur yang lebih aman adalah memakai server perantara sehingga API key tidak berada di APK.

## Info Aplikasi
- Versi aplikasi yang ditampilkan: **1.5**.
- Pembuat: **Gani Cahyo H**.
- **Agroteknologi-Universitas Sebelas Maret**.


## v1.5 — OpenWeather 4.0 + Elevasi DEM Otomatis + Build Modern
- Integrasi OpenWeather One Call untuk data cuaca makro.
- GPS HP otomatis digunakan sebagai koordinat lokasi.
- Data: suhu, RH, tekanan, angin, arah angin, gust, hujan, awan, prakiraan hourly.
- Estimasi ET₀ harian berbasis data meteorologi OpenWeather dan pendekatan FAO-56.
- Ketinggian lokasi tidak lagi memakai altitude mentah GPS HP. Latitude/longitude tetap diambil dari GPS HP, lalu elevasi terrain otomatis diambil dari Open-Meteo Elevation API berbasis Copernicus DEM GLO-90 (~90 m).
- Jika layanan elevasi sementara gagal, aplikasi menggunakan elevasi terakhir yang tersimpan; nilai `0 m` dari altitude GPS HP tidak dijadikan elevasi lokasi.
- Nilai elevasi DEM digunakan dalam perhitungan ET₀.
- OpenWeather API Key diatur dari Settings dan tidak ditanam di source code.
- ThingSpeak, CSV histori, AI dan konfigurasi sebelumnya dipertahankan.
- APK dibangun melalui GitHub Actions dengan AGP 9.4.0, Gradle 9.8.0, JDK 17, Android SDK 37/Build Tools 36.0.0, dan Node.js 24 LTS.


### Build modern v1.4
- Android Gradle Plugin **9.1.1**.
- Gradle **9.3.1**.
- Android SDK compile/target **37**.
- Android Build Tools **36.0.0**.
- GitHub Actions menggunakan **JDK 17**, yaitu JDK minimum dan default resmi untuk AGP 9.1.1.
- Node.js **24.21.0 LTS** disiapkan di CI; aplikasi native Java ini tidak bergantung pada Node untuk kompilasi APK.
- OpenWeather dimigrasikan dari One Call 3.0 ke **One Call API 4.0**. OpenWeather saat ini merekomendasikan 4.0 untuk integrasi baru.
- Data makro: suhu, terasa, RH, titik embun, tekanan, UV, awan, visibilitas, angin, arah, gust, hujan, kondisi cuaca, sunrise, sunset, peringatan, prakiraan harian dan ET₀ estimasi.
- GPS HP tetap menjadi sumber koordinat.



### Build/CI v1.4
- **AGP 9.4.0** (rilis September 2026).
- **Gradle 9.8.0**.
- **compileSdk/targetSdk 37**.
- **Build Tools 36.0.0**.
- **JDK 17** sebagai versi minimum/default resmi AGP 9.4.
- **Node.js 24.21.0 LTS** tersedia pada GitHub Actions untuk tooling masa depan; aplikasi native Java ini tidak bergantung pada Node untuk proses Android build.
- CI menghasilkan **debug dan release unsigned APK**.

### OpenWeather v4.0
OpenWeather 3.0 pada paket sebelumnya sudah tidak tepat untuk integrasi baru. Paket ini memakai **One Call API 4.0**, dengan endpoint current dan timeline 1-day. Data yang ditampilkan mencakup suhu, terasa, RH, titik embun, tekanan, UV, awan, visibilitas, kecepatan angin, arah angin, gust, hujan, kondisi, sunrise, sunset, ID peringatan dan prakiraan harian. ET₀ dihitung sebagai estimasi agrometeorologi dari data prakiraan, lokasi GPS dan elevasi DEM dari Open-Meteo; altitude GPS HP tidak digunakan. One Call 4.0 adalah produk terpisah dan memerlukan akses/subscription One Call yang sesuai pada akun OpenWeather.


### Elevasi otomatis
Aplikasi mengambil latitude/longitude dari HP hanya sebagai koordinat lokasi. Ketinggian terrain diperoleh dari **Open-Meteo Elevation API**, yang menggunakan Copernicus DEM GLO-90 dengan resolusi sekitar 90 meter. Sumber: Open-Meteo Elevation API. Attribution: Copernicus Programme / Open-Meteo.
