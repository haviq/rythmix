# Rich Music

**Pemutar musik web gratis** bergaya Spotify, katalog [YouTube Music](https://music.youtube.com). Tanpa akun.

- **Website:** [richmusic.vercel.app](https://richmusic.vercel.app)
- **Repo:** [github.com/ramax100/YT-Music-Mod](https://github.com/ramax100/YT-Music-Mod)
- **Telegram:** [t.me/ChRichStore](https://t.me/ChRichStore)

Project ini **gratis** dan **bebas dipakai**. Fork, ubah, deploy sendiri, atau bagikan — silakan.

---

## Tentang

Rich Music adalah pemutar musik di browser. Cari lagu, buka album dan artis, buat playlist, lihat lirik, atur antrian — semuanya tanpa daftar akun.

Library (favorit, playlist, riwayat, statistik) tersimpan di perangkatmu. Audio diputar lewat pemutar resmi YouTube.

Tidak berafiliasi dengan YouTube, Google, atau Spotify.

---

## Channel Telegram

Update, info fitur, dan komunitas:

### [t.me/ChRichStore](https://t.me/ChRichStore)

Silakan join.

---

## Cara memakai website

1. Buka **[richmusic.vercel.app](https://richmusic.vercel.app)**
2. Cari lagu, atau pilih dari Home / Charts / Browse all
3. Lagu pertama langsung play. Kalau klik lagu lain, Now Playing menampilkan lagu baru — tekan **Play** untuk mengganti putaran
4. Ikon hati = favorit. **Playlist** = simpan ke folder. Di halaman album/artis, **Save** masuk tab Saved
5. Pindah HP? Library → **Backup**, di perangkat baru **Restore**

### Desktop / PC

Di laptop atau komputer, Rich Music langsung siap. Buka situsnya, pilih lagu, dan putar — tidak perlu pengaturan tambahan.

### Putar di latar belakang (Android)

Musik tetap jalan saat layar terkunci atau pindah aplikasi, **tanpa mode desktop**.

Buka [richmusic.vercel.app](https://richmusic.vercel.app) di **[Brave Browser](https://play.google.com/store/apps/details?id=com.brave.browser)** — putar lagu, lalu keluar dari tab atau kunci HP. Audio tetap berlanjut.

Di Chrome, aktifkan **⋮ → Situs desktop** jika ingin hasil serupa.

---

## Fitur

### Home
- Sapaan sesuai waktu dan tanggal
- Recently played
- Mix for you — rekomendasi dari favorit & riwayat
- Liked songs, playlist lokal, item Saved
- Rak YouTube Music
- Carousel geser; di desktop ada panah

### Search
- Saran otomatis saat mengetik
- Filter: All, Songs, Videos, Albums, Artists, Playlists
- Top result sebagai kartu besar
- Hasil dikelompokkan (lagu, album, artis, playlist)
- Riwayat pencarian
- Browse all — mood & genre

### Charts
- Tangga lagu, playlist genre, artis teratas

### Library
Tanpa login, tersimpan di perangkat ini.

| Tab | Isi |
| --- | --- |
| Playlists | Playlist buatanmu + kartu Liked Songs |
| Favorites | Lagu yang di-heart, Play all / Shuffle |
| Saved | Album, playlist, artis yang di-Save |
| History | Yang baru diputar |
| Stats | Total putar, menit, top artis, lagu terbanyak |

- New playlist
- Import dari link YouTube Music (playlist, album, artis, lagu)
- Backup / Restore file JSON
- Rename, hapus, urutkan lagu (panah atau drag di desktop)

### Player
- Streaming YouTube IFrame (audio YouTube Music)
- Quality di menu ⋮ — bisa dinaikkan ke YouTube max
- Preview lagu lain tanpa memutus yang sedang play
- Shuffle & Repeat (mati / semua / satu)
- Kecepatan 0.5×–2×
- Antrian: Your queue dulu, lalu radio. Tersimpan saat refresh
- Play next / Add to queue
- Related: lagu, album, playlist, artis
- Lirik sinkron — tap baris untuk loncat
- Share (menu HP atau salin tautan)
- Download MP3
- SponsorBlock — skip intro/sponsor (bisa dimatikan)
- Sleep timer
- Widget mengambang + Picture-in-Picture
- Mode gelap / terang
- Nama artis bisa diklik ke halaman artis

### Pintasan keyboard

| Tombol | Aksi |
| --- | --- |
| `Space` | Play / Pause |
| `Shift` + `→` | Berikutnya |
| `Shift` + `←` | Sebelumnya |
| `Esc` | Tutup Now Playing |
| `L` | Ganti tema |
| `P` | Widget |

---

## Menjalankan di komputer sendiri

Perlu [Node.js](https://nodejs.org) 18+ (disarankan 20).

```bash
git clone https://github.com/ramax100/YT-Music-Mod.git
cd YT-Music-Mod
npm install
npm start
```

Buka **http://localhost:3000**

---

## Deploy ke Vercel

```bash
npm i -g vercel
cd YT-Music-Mod
vercel login
vercel --prod
```

Atau di dashboard Vercel: **Import Git Repository** → pilih `ramax100/YT-Music-Mod` → Deploy.

---

## Isi repo

```
YT-Music-Mod/
├── public/           # website (HTML, CSS, JS, logo)
├── server.js         # API: YouTube Music, lirik, download
├── api/index.js      # entry Vercel
├── vercel.json
├── package.json
└── README.md
```

---

## Lisensi

**Gratis. Bebas dipakai.**

Jalankan, bagikan, ubah, dan deploy ulang sesukamu. Tidak ada biaya.

---

**[Buka Rich Music](https://richmusic.vercel.app)** · **[Join Telegram](https://t.me/ChRichStore)** · **[GitHub](https://github.com/ramax100/YT-Music-Mod)**
