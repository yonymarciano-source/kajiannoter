package com.kajian.note.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AppCompatActivity
import com.kajian.note.KajianApp
import com.kajian.note.R
import com.kajian.note.databinding.ActivitySplashBinding
import com.kajian.note.ui.onboarding.OnboardingActivity
import com.kajian.note.utils.PreferencesManager

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(KajianApp.applyLocale(base, PreferencesManager(base).getAppLanguage()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Animate logo
        val fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in_up)
        val fadeInSlow = AnimationUtils.loadAnimation(this, R.anim.fade_in_slow)
        b.ivLogo.startAnimation(fadeIn)
        b.tvAppName.startAnimation(fadeIn)
        b.tvTagline.startAnimation(fadeInSlow)

        val prefs = PreferencesManager(this)

        // Navigate after 2 seconds
        b.root.postDelayed({
            val intent = if (prefs.isFirstLaunch()) {
                Intent(this, OnboardingActivity::class.java)
            } else {
                Intent(this, MainActivity::class.java)
            }
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 2000)
    }
}
