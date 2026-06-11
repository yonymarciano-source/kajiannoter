# KajianNote v4.0.0 — Changelog

## 🆕 Fitur Baru

### 🔐 Google Sign-In + Firebase Auth
- Login dengan akun Google (satu tap)
- Mode guest tersedia (tanpa akun, tier FREE)
- User profile tersimpan di Firestore

### 📁 Sistem Folder / Kategori
- Buat folder kajian (Kajian Fiqih, Tafsir Al-Baqarah, dll)
- Pindahkan catatan antar folder
- Filter notes berdasarkan folder
- Emoji + warna per folder

### ⭐ Sistem Tier & Premium Gate
- FREE: maksimal 10 catatan, Groq transcription
- PREMIUM (Rp 49.000 sekali bayar): unlimited catatan + export PDF/Word
- SUBSCRIBER (Rp 15.000/bulan): semua fitur + diarization + terjemah Arab

### 💳 Paywall UI
- Tampil otomatis saat user mencapai batas atau akses fitur premium
- Dua pilihan: one-time purchase atau langganan bulanan
- Tombol "Sudah bayar? Verifikasi" untuk refresh tier dari Firestore

### 🎨 UI Refresh — Islami Palette
- Palet warna baru: Teal + Deep Navy + Gold aksen
- Lebih kalem dan elegan, cocok untuk konten islami
- Warna tier badge: Gold (Premium), Indigo (Subscriber)

## 🔧 Teknis
- Database Room v6 dengan migration dari v5
- UserManager singleton untuk auth + tier management
- RecordViewModel extended: folder + tier support
- Firebase BOM 33.1.0 + Google Sign-In 21.2.0

## 📋 Yang Harus Dilakukan Sebelum Build
1. Tambahkan google-services.json dari Firebase Console
2. Ganti link WhatsApp di PaywallActivity dengan nomor kamu
3. Setup Firestore rules (lihat SETUP_FIREBASE.md)
4. Tambahkan SHA-1 fingerprint ke Firebase project

## 🔜 Next: v4.1
- Terjemahan Arab → Indonesia inline
- Folder selector saat simpan catatan
- User profile screen
