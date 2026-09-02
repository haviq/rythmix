# Rythmix Music

**Pemutar musik web gratis** dengan tampilan bergaya Spotify dan katalog berbasis YouTube Music. Tanpa akun.

- **Website:** https://rythmix-puce.vercel.app/
- **Repository:** https://github.com/haviq/rythmix
- **Telegram:** https://t.me/haviq

Project ini gratis dan bebas digunakan. Kamu dapat menjalankan, mengubah, melakukan deploy, atau membagikannya sesuai dengan ketentuan lisensi project.

---

## Tentang

Rythmix Music adalah pemutar musik berbasis web yang memungkinkan kamu mencari dan memutar musik langsung dari browser.

Tersedia berbagai fitur seperti pencarian lagu, album dan artis, playlist, favorit, riwayat pemutaran, lirik, antrian lagu, serta berbagai pengaturan pemutar.

Library seperti favorit, playlist, riwayat, dan statistik disimpan secara lokal di perangkat pengguna sehingga tidak membutuhkan akun.

Audio diputar menggunakan pemutar YouTube.

> Rythmix Music tidak berafiliasi dengan YouTube, Google, maupun Spotify.

---

## Fitur

### Home

- Sapaan berdasarkan waktu dan tanggal
- Recently played
- Mix for you
- Liked songs
- Playlist lokal
- Item yang disimpan
- Rak konten YouTube Music
- Carousel yang dapat digeser
- Navigasi carousel dengan tombol pada desktop

### Search

- Saran pencarian otomatis
- Filter berdasarkan:
  - All
  - Songs
  - Videos
  - Albums
  - Artists
  - Playlists
- Top result
- Hasil pencarian yang dikelompokkan
- Riwayat pencarian
- Browse all
- Mood & genre

### Charts

- Tangga lagu
- Playlist berdasarkan genre
- Artis teratas

### Library

Tidak membutuhkan login. Data library disimpan secara lokal pada perangkat.

| Tab | Isi |
| --- | --- |
| Playlists | Playlist yang dibuat pengguna dan Liked Songs |
| Favorites | Lagu yang disukai |
| Saved | Album, playlist, dan artis yang disimpan |
| History | Riwayat lagu yang diputar |
| Stats | Statistik pemutaran dan artis teratas |

Fitur library:

- Membuat playlist baru
- Import dari link YouTube Music
- Backup library
- Restore library
- Rename playlist
- Menghapus playlist
- Mengurutkan lagu
- Drag & drop pada desktop

### Player

- Streaming melalui YouTube IFrame
- Pengaturan kualitas
- Preview lagu
- Shuffle
- Repeat
- Kecepatan pemutaran 0.5×–2×
- Antrian lagu
- Play next
- Add to queue
- Related songs
- Album, playlist, dan artis terkait
- Lirik sinkron
- Share
- Download MP3
- SponsorBlock
- Sleep timer
- Floating player
- Picture-in-Picture
- Mode gelap / terang
- Navigasi langsung ke halaman artis

---

## Pintasan Keyboard

| Tombol | Aksi |
| --- | --- |
| `Space` | Play / Pause |
| `Shift` + `→` | Lagu berikutnya |
| `Shift` + `←` | Lagu sebelumnya |
| `Esc` | Tutup Now Playing |
| `L` | Ganti tema |
| `P` | Buka widget player |

---

## Menjalankan Secara Lokal

### Persyaratan

- Node.js 18 atau lebih baru
- Node.js 20 direkomendasikan

Clone repository:

```bash
git clone https://github.com/haviq/rythmix.git
cd rythmix
