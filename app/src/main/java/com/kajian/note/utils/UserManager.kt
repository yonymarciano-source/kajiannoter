package com.kajian.note.utils

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * UserManager — handle Firebase Auth + Firestore user profile & premium status.
 *
 * Tier system:
 *   FREE       → max 10 catatan, Groq transcription only
 *   PREMIUM    → unlimited catatan, PDF/Word export
 *   SUBSCRIBER → semua fitur + diarization + terjemahan Arab
 */
object UserManager {

    enum class Tier { FREE, PREMIUM, SUBSCRIBER }

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    val currentUser: FirebaseUser? get() = auth.currentUser
    val isLoggedIn: Boolean get() = currentUser != null

    // Cache lokal supaya tidak perlu hit Firestore tiap saat
    private var cachedTier: Tier = Tier.FREE
    private var tierFetchedAt: Long = 0L
    private val CACHE_TTL = 5 * 60 * 1000L // 5 menit

    fun getUserId(): String = currentUser?.uid ?: ""
    fun getUserName(): String = currentUser?.displayName ?: "Penuntut Ilmu"
    fun getUserEmail(): String = currentUser?.email ?: ""
    fun getUserPhotoUrl(): String = currentUser?.photoUrl?.toString() ?: ""

    /**
     * Ambil tier dari Firestore, dengan cache 5 menit.
     */
    suspend fun getTier(forceRefresh: Boolean = false): Tier {
        if (!isLoggedIn) return Tier.FREE
        val now = System.currentTimeMillis()
        if (!forceRefresh && now - tierFetchedAt < CACHE_TTL) return cachedTier

        return try {
            val doc = db.collection("users").document(getUserId()).get().await()
            val tierStr = doc.getString("tier") ?: "FREE"
            // Support both number and string type for subscriptionExpiry
            val expiry = doc.getLong("subscriptionExpiry")
                ?: doc.getString("subscriptionExpiry")?.toLongOrNull()
                ?: 0L

            cachedTier = when {
                tierStr == "SUBSCRIBER" && expiry > now -> Tier.SUBSCRIBER
                tierStr == "SUBSCRIBER" && expiry == 0L -> Tier.SUBSCRIBER // no expiry = lifetime
                tierStr == "PREMIUM" -> Tier.PREMIUM
                else -> Tier.FREE
            }
            tierFetchedAt = now
            cachedTier
        } catch (e: Exception) {
            cachedTier // return cached on error
        }
    }

    fun getCachedTier(): Tier = cachedTier

    /**
     * Cek apakah user bisa tambah catatan baru.
     * Free user: max 10 catatan.
     */
    suspend fun canAddNote(currentCount: Int): Boolean {
        val tier = getTier()
        return when (tier) {
            Tier.FREE -> currentCount < 10
            Tier.PREMIUM, Tier.SUBSCRIBER -> true
        }
    }

    fun canExportAdvanced(): Boolean = cachedTier != Tier.FREE
    fun canUseDiarization(): Boolean = cachedTier == Tier.SUBSCRIBER
    fun canUseArabicTranslation(): Boolean = cachedTier == Tier.SUBSCRIBER

    /**
     * Update tier setelah user bayar. Dipanggil dari payment callback.
     */
    suspend fun upgradeTier(tier: Tier, durationDays: Int = 0) {
        if (!isLoggedIn) return
        val expiry = if (durationDays > 0)
            System.currentTimeMillis() + (durationDays * 24 * 60 * 60 * 1000L)
        else 0L

        val data = mutableMapOf<String, Any>(
            "tier" to tier.name,
            "email" to getUserEmail(),
            "displayName" to getUserName(),
            "updatedAt" to System.currentTimeMillis()
        )
        if (expiry > 0) data["subscriptionExpiry"] = expiry

        db.collection("users").document(getUserId()).set(data).await()
        cachedTier = tier
        tierFetchedAt = System.currentTimeMillis()
    }

    /**
     * Buat/update user document saat pertama login.
     */
    suspend fun ensureUserDocument() {
        if (!isLoggedIn) return
        try {
            val ref = db.collection("users").document(getUserId())
            val snap = ref.get().await()
            if (!snap.exists()) {
                ref.set(mapOf(
                    "email" to getUserEmail(),
                    "displayName" to getUserName(),
                    "tier" to "FREE",
                    "createdAt" to System.currentTimeMillis()
                )).await()
            }
            // Refresh tier cache
            getTier(forceRefresh = true)
        } catch (e: Exception) {
            // Gagal silent — user tetap bisa pakai app sebagai FREE
        }
    }

    fun signOut() {
        auth.signOut()
        cachedTier = Tier.FREE
        tierFetchedAt = 0L
    }

    // ── Shared Prefs fallback (untuk offline / sebelum Firebase ready) ────────

    private const val PREFS = "kajian_user_prefs"

    fun saveLocalTier(ctx: Context, tier: Tier) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("local_tier", tier.name).apply()
    }

    fun getLocalTier(ctx: Context): Tier {
        val str = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("local_tier", "FREE") ?: "FREE"
        return try { Tier.valueOf(str) } catch (e: Exception) { Tier.FREE }
    }
}
