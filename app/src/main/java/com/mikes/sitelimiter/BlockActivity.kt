package com.mikes.sitelimiter

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.mikes.sitelimiter.databinding.ActivityBlockBinding

/**
 * The wall you hit when a budget runs out.
 *
 * Nudge rules offer snoozes. Hard rules are enforced by the policy as well as the UI.
 * Always re-read current rules, including when Android reuses this singleTask activity.
 */
class BlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockBinding
    private lateinit var prefs: Prefs
    private var domain = ""
    private var host = ""
    private var closing = false
    private var optionsOpen = false
    private var browser: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            renderCurrentRule()
            if (!isFinishing) handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        readTarget(intent)

        binding.btnClose.setOnClickListener { openBlankTabAndGoHome() }

        binding.btnOptions.setOnClickListener {
            val rule = prefs.blockingRule(host)
            if (rule?.mode == RuleMode.NUDGE) optionsOpen = true
            renderCurrentRule()
        }

        binding.btnSnooze5.setOnClickListener { snooze(5) }
        binding.btnSnooze15.setOnClickListener { snooze(15) }
        binding.btnOffToday.setOnClickListener {
            val rule = prefs.blockingRule(host)
            if (rule != null && prefs.turnOffToday(rule.domain) == ChangeResult.UPDATED) {
                Toast.makeText(this, "Limit paused until the next budget day", Toast.LENGTH_SHORT).show()
            }
            optionsOpen = false
            renderCurrentRule()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = openBlankTabAndGoHome()
        })
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readTarget(intent)
        renderCurrentRule()
    }

    private fun readTarget(intent: Intent) {
        host = intent.getStringExtra(EXTRA_HOST) ?: intent.getStringExtra(EXTRA_DOMAIN).orEmpty()
        browser = intent.getStringExtra(EXTRA_BROWSER)
        closing = false
        optionsOpen = false
    }

    private fun renderCurrentRule() {
        val rule = prefs.blockingRule(host)
        if (host.isBlank() || rule == null) {
            finish()
            return
        }
        if (domain != rule.domain) optionsOpen = false
        domain = rule.domain
        val hard = rule.mode == RuleMode.HARD
        binding.blockHeadline.text = if (hard) "Daily limit reached" else "That's the budget"
        binding.blockDomain.text = domain
        binding.blockUsage.text = "${formatMinutes(prefs.usedSeconds(domain))} used today · limit ${formatMinutes(rule.limitSeconds)}" +
            if (hard) "\nAvailable again at the next budget reset." else ""
        binding.btnOptions.visibility = if (!hard && !optionsOpen) View.VISIBLE else View.GONE
        binding.optionsGroup.visibility = if (!hard && optionsOpen) View.VISIBLE else View.GONE
    }

    private fun snooze(minutes: Int) {
        val rule = prefs.blockingRule(host)
        if (rule != null && prefs.snooze(rule.domain, minutes) == ChangeResult.UPDATED) {
            Toast.makeText(this, "$minutes more minutes", Toast.LENGTH_SHORT).show()
        }
        optionsOpen = false
        renderCurrentRule()
    }

    private fun openBlankTabAndGoHome() {
        if (closing) return
        closing = true
        PrivacyLog.info(PrivacyLog.Event.BROWSER_EXIT_CLICK)
        handler.removeCallbacks(refresh)
        if (prefs.blockingRule(host) == null) {
            finish()
            return
        }
        UrlWatcherService.openBlankTabAndGoHome(this, browser?.takeIf { it in prefs.browsers() })
        finishWithoutAnimation()
    }

    @Suppress("DEPRECATION")
    private fun finishWithoutAnimation() {
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_DOMAIN = "domain"
        const val EXTRA_HOST = "host"
        const val EXTRA_USED = "used"
        const val EXTRA_LIMIT = "limit"
        internal const val EXTRA_BROWSER = "browser"

        fun formatMinutes(seconds: Int): String {
            val totalMinutes = seconds / 60
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
        }
    }
}
