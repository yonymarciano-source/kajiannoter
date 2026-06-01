package com.kajian.note.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.kajian.note.KajianApp
import com.kajian.note.R
import com.kajian.note.databinding.ActivityOnboardingBinding
import com.kajian.note.databinding.FragmentOnboardPageBinding
import com.kajian.note.ui.MainActivity
import com.kajian.note.utils.PreferencesManager

data class OnboardPage(
    val emoji: String,
    val title: String,
    val description: String,
    val bgColor: Int
)

class OnboardingActivity : AppCompatActivity() {

    private lateinit var b: ActivityOnboardingBinding

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(KajianApp.applyLocale(base, PreferencesManager(base).getAppLanguage()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(b.root)

        val pages = listOf(
            OnboardPage("🎙️", "Record Any Lecture",
                "Tap record — KajianNote listens and transcribes speech in real-time. Works for kajian, seminars, lectures, and meetings.",
                0xFF0A0E1A.toInt()),
            OnboardPage("🌐", "Groq AI Transcription",
                "High-accuracy transcription powered by Whisper large-v3-turbo via Groq. Supports Bahasa Indonesia and Arabic terms perfectly.",
                0xFF0A0E1A.toInt()),
            OnboardPage("📚", "Searchable Notes",
                "All recordings saved locally. Search by keyword, view full transcript with timestamps, share or copy with one tap.",
                0xFF0A0E1A.toInt())
        )

        val adapter = OnboardAdapter(this, pages)
        b.viewPager.adapter = adapter

        TabLayoutMediator(b.tabLayout, b.viewPager) { _, _ -> }.attach()

        b.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position == pages.size - 1) {
                    b.btnNext.text = "Get Started"
                } else {
                    b.btnNext.text = "Next →"
                }
            }
        })

        b.btnNext.setOnClickListener {
            val current = b.viewPager.currentItem
            if (current < pages.size - 1) {
                b.viewPager.currentItem = current + 1
            } else {
                goToMain()
            }
        }

        b.btnSkip.setOnClickListener { goToMain() }
    }

    private fun goToMain() {
        PreferencesManager(this).setFirstLaunchDone()
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}

class OnboardAdapter(activity: FragmentActivity, private val pages: List<OnboardPage>)
    : FragmentStateAdapter(activity) {
    override fun getItemCount() = pages.size
    override fun createFragment(pos: Int) = OnboardPageFragment.newInstance(pages[pos])
}

class OnboardPageFragment : Fragment() {

    companion object {
        fun newInstance(page: OnboardPage) = OnboardPageFragment().apply {
            arguments = Bundle().apply {
                putString("emoji", page.emoji)
                putString("title", page.title)
                putString("desc", page.description)
            }
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val b = FragmentOnboardPageBinding.inflate(i, c, false)
        b.tvEmoji.text = arguments?.getString("emoji") ?: "🎙️"
        b.tvTitle.text = arguments?.getString("title") ?: ""
        b.tvDesc.text = arguments?.getString("desc") ?: ""
        return b.root
    }
}
