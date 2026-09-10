package com.mikes.sitelimiter

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.mikes.sitelimiter.databinding.ActivityBlockBinding

/**
 * The wall you hit when a budget runs out.
 *
 * The escape hatches are real and deliberate — this is a nudge, not a lock — but they sit one
 * tap behind "…or keep going" so that closing is the path of least resistance.
 */
class BlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockBinding
    private lateinit var prefs: Prefs
    private lateinit var domain: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        domain = intent.getStringExtra(EXTRA_DOMAIN).orEmpty()
        if (domain.isBlank()) {
            // Nothing to snooze or unblock; writing prefs keyed on "" would just leave litter.
            finish()
            return
        }
        val used = intent.getIntExtra(EXTRA_USED, 0)
        val limit = intent.getIntExtra(EXTRA_LIMIT, 0)

        binding.blockDomain.text = domain
        binding.blockUsage.text = "${formatMinutes(used)} used today · limit ${formatMinutes(limit)}"

        binding.btnClose.setOnClickListener { goHome() }

        binding.btnOptions.setOnClickListener {
            binding.optionsGroup.visibility = View.VISIBLE
            binding.btnOptions.visibility = View.GONE
        }

        binding.btnSnooze5.setOnClickListener { snooze(5) }
        binding.btnSnooze15.setOnClickListener { snooze(15) }
        binding.btnOffToday.setOnClickListener {
            prefs.turnOffToday(domain)
            Toast.makeText(this, "$domain unblocked for the rest of today", Toast.LENGTH_SHORT).show()
            finish()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
    }

    private fun snooze(minutes: Int) {
        prefs.snooze(domain, minutes)
        Toast.makeText(this, "$minutes more minutes on $domain", Toast.LENGTH_SHORT).show()
        // Finishing drops back to the browser, which is still sitting behind us.
        finish()
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object {
        const val EXTRA_DOMAIN = "domain"
        const val EXTRA_USED = "used"
        const val EXTRA_LIMIT = "limit"

        fun formatMinutes(seconds: Int): String {
            val totalMinutes = seconds / 60
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
        }
    }
}
