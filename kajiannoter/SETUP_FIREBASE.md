# Setup Firebase untuk KajianNote v4.0

## Langkah 1 — Buat Project Firebase
1. Buka https://console.firebase.google.com
2. Klik "Add project" → nama: "KajianNote"
3. Disable Google Analytics (opsional)
4. Klik "Create project"

## Langkah 2 — Tambah Android App
1. Klik icon Android di dashboard
2. Package name: `com.kajian.note`
3. App nickname: "KajianNote"
4. Download **google-services.json**
5. Pindahkan ke folder `app/` (timpa file template)

## Langkah 3 — Enable Authentication
1. Firebase Console → Build → Authentication
2. Klik "Get started"
3. Sign-in method → Google → Enable
4. Support email → isi email kamu
5. Save

## Langkah 4 — Enable Firestore
1. Firebase Console → Build → Firestore Database
2. Klik "Create database"
3. Mode: **Production** (aman)
4. Location: **asia-southeast1** (Singapore, paling dekat Indonesia)
5. Done

## Langkah 5 — Set Firestore Rules
Di Firestore → Rules, paste ini:
```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{userId} {
      allow read, write: if request.auth != null && request.auth.uid == userId;
    }
  }
}
```

## Langkah 6 — Tambah SHA-1 (wajib untuk Google Sign-In)
Di Android Studio:
1. Gradle panel → app → Tasks → android → signingReport
2. Copy SHA-1
3. Firebase Console → Project Settings → Your Apps → Add fingerprint
4. Paste SHA-1 → Save

## Langkah 7 — Build & Test
1. Buka project di Android Studio
2. Build → Run
3. Test Google Sign-In

## Struktur Firestore
```
users/
  {uid}/
    tier: "FREE" | "PREMIUM" | "SUBSCRIBER"
    subscriptionExpiry: timestamp (ms)
    email: string
    displayName: string
    createdAt: timestamp
    updatedAt: timestamp
```

## Update Tier Manual (sebelum Midtrans terintegrasi)
Untuk upgrade user setelah bayar, buka Firestore → users → cari uid → edit field `tier`.

Untuk SUBSCRIBER, tambahkan field:
- `tier`: "SUBSCRIBER"  
- `subscriptionExpiry`: timestamp 30 hari dari sekarang (ms)
