package com.mikes.sitelimiter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mikes.sitelimiter.databinding.ActivityMainBinding
import com.mikes.sitelimiter.databinding.RowBrowserBinding
import com.mikes.sitelimiter.databinding.RowRuleBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    /** Resolved once per resume; enumerating packages is not free. */
    private var installedBrowsers: List<Browsers.BrowserApp> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(
                this,
                "Find \"Site Limiter watcher\" under Installed apps / Downloaded apps",
                Toast.LENGTH_LONG,
            ).show()
        }

        binding.btnOverlay.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        }

        binding.btnAdd.setOnClickListener { addRule() }
        binding.btnSaveResetHour.setOnClickListener { saveResetHour() }
    }

    override fun onResume() {
        super.onResume()
        prefs.pruneOldUsage()
        installedBrowsers = Browsers.installed(this)
        renderStatus()
        renderRules()
        renderBrowsers()
        renderBrowsersWarning()
        binding.inputResetHour.setText(prefs.resetHour().toString())
    }

    // ---------------- setup status ----------------

    private fun renderStatus() {
        val serviceOn = UrlWatcherService.isEnabled(this)
        binding.statusAccessibility.text = if (serviceOn) {
            "1. Accessibility service: ON — reading the URL bar of the browsers below."
        } else {
            "1. Accessibility service: OFF — nothing is being tracked. This is the permission " +
                "that lets the app see which site your browser is showing."
        }

        val overlayOn = Settings.canDrawOverlays(this)
        binding.statusOverlay.text = if (overlayOn) {
            "2. Display over other apps: ON."
        } else {
            "2. Display over other apps: OFF — required, or Android will not let the block " +
                "screen open from the background. Without it you just get sent to the home screen."
        }
    }

    // ---------------- limits ----------------

    private fun renderRules() {
        val container = binding.rulesContainer
        container.removeAllViews()
        val rules = prefs.rules()

        if (rules.isEmpty()) {
            val row = RowRuleBinding.inflate(LayoutInflater.from(this), container, false)
            row.ruleDomain.text = "No limits yet"
            row.ruleUsage.text = "Add one below, e.g. reddit.com / 30"
            row.ruleProgress.progress = 0
            row.btnResetUsage.visibility = android.view.View.GONE
            row.btnDeleteRule.visibility = android.view.View.GONE
            container.addView(row.root)
            return
        }

        for (rule in rules) {
            val row = RowRuleBinding.inflate(LayoutInflater.from(this), container, false)
            val used = prefs.usedSeconds(rule.domain)

            row.ruleDomain.text = rule.domain
            row.ruleUsage.text = buildString {
                append(BlockActivity.formatMinutes(used))
                append(" of ")
                append(BlockActivity.formatMinutes(rule.limitSeconds))
                append(" today")
                when {
                    prefs.isOffToday(rule.domain) -> append("  ·  off for today")
                    System.currentTimeMillis() < prefs.snoozeUntil(rule.domain) -> {
                        val left = (prefs.snoozeUntil(rule.domain) - System.currentTimeMillis()) / 60_000 + 1
                        append("  ·  snoozed ${left}m")
                    }
                    used >= rule.limitSeconds -> append("  ·  blocking")
                }
            }
            row.ruleProgress.progress =
                if (rule.limitSeconds <= 0) 100
                else (used * 100 / rule.limitSeconds).coerceIn(0, 100)

            row.btnResetUsage.setOnClickListener {
                prefs.resetUsage(rule.domain)
                renderRules()
            }
            row.btnDeleteRule.setOnClickListener {
                prefs.deleteRule(rule.domain)
                renderRules()
            }
            container.addView(row.root)
        }
    }

    private fun addRule() {
        val domain = Prefs.normalizeDomain(binding.inputDomain.text?.toString().orEmpty())
        if (domain == null) {
            Toast.makeText(this, "That does not look like a domain", Toast.LENGTH_SHORT).show()
            return
        }
        val minutes = binding.inputMinutes.text?.toString()?.trim()?.toIntOrNull()
        if (minutes == null || minutes < 0) {
            Toast.makeText(this, "Enter a whole number of minutes", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.upsertRule(domain, minutes * 60)
        binding.inputDomain.setText("")
        binding.inputMinutes.setText("")
        renderRules()
        Toast.makeText(this, "$domain limited to ${minutes}m/day", Toast.LENGTH_SHORT).show()
    }

    // ---------------- browsers ----------------

    private fun renderBrowsers() {
        val container = binding.browsersContainer
        container.removeAllViews()

        val selected = prefs.browsers().toMutableSet()
        val installed = installedBrowsers

        if (installed.isEmpty()) {
            val row = RowBrowserBinding.inflate(LayoutInflater.from(this), container, false)
            row.browserCheck.text = "No browsers found"
            row.browserCheck.isEnabled = false
            container.addView(row.root)
            return
        }

        for (app in installed) {
            val row = RowBrowserBinding.inflate(LayoutInflater.from(this), container, false)
            row.browserCheck.text = "${app.label}  (${app.pkg})"
            row.browserCheck.isChecked = app.pkg in selected
            row.browserCheck.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(app.pkg) else selected.remove(app.pkg)
                prefs.saveBrowsers(selected)
                renderBrowsersWarning()
            }
            container.addView(row.root)
        }
    }

    /** Unticking everything silently disables all tracking, so say so. */
    private fun renderBrowsersWarning() {
        val watched = prefs.browsers().intersect(installedBrowsers.map { it.pkg }.toSet())
        binding.browsersWarning.text = when {
            installedBrowsers.isEmpty() -> "No browsers detected on this device."
            watched.isEmpty() ->
                "Nothing is being watched. Tick at least one browser or no time is counted."
            else -> "Watching ${watched.size} of ${installedBrowsers.size} installed browsers."
        }
    }

    // ---------------- day boundary ----------------

    private fun saveResetHour() {
        val hour = binding.inputResetHour.text?.toString()?.trim()?.toIntOrNull()
        if (hour == null || hour !in 0..23) {
            Toast.makeText(this, "Enter an hour from 0 to 23", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.saveResetHour(hour)
        renderRules()
        Toast.makeText(this, "Budgets reset at ${hour}:00", Toast.LENGTH_SHORT).show()
    }
}
