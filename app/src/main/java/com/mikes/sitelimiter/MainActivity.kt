package com.mikes.sitelimiter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Toast
import android.widget.EditText
import android.widget.LinearLayout
import android.text.InputType
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.google.android.material.switchmaterial.SwitchMaterial
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
        binding.btnCancelPendingChanges.setOnClickListener {
            prefs.cancelPendingChanges()
            renderRules()
            renderBrowsers()
            renderPending()
            binding.inputResetHour.setText(prefs.resetHour().toString())
        }
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
        renderPending()
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
            row.btnEditRule.visibility = View.GONE
            row.btnResetUsage.visibility = android.view.View.GONE
            row.btnDeleteRule.visibility = android.view.View.GONE
            container.addView(row.root)
            return
        }

        for (rule in rules) {
            val row = RowRuleBinding.inflate(LayoutInflater.from(this), container, false)
            val state = prefs.snapshot()
            val used = state.usage[rule.domain] ?: 0

            row.ruleDomain.text = rule.domain
            row.ruleUsage.text = buildString {
                append(BlockActivity.formatMinutes(used))
                append(" of ")
                append(BlockActivity.formatMinutes(rule.limitSeconds))
                append(" today · ")
                append(if (rule.mode == RuleMode.HARD) "Hard limit" else "Nudge")
                if (rule.domain in state.locked) append(" · locked until reset")
                if (state.pendingRules.containsKey(rule.domain)) append(" · change scheduled")
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
                else (used.toLong() * 100 / rule.limitSeconds).coerceIn(0, 100).toInt()

            row.btnEditRule.setOnClickListener { editRule(rule) }
            row.btnResetUsage.isEnabled = rule.domain !in state.locked
            row.btnResetUsage.setOnClickListener { showChange(prefs.resetUsage(rule.domain)) }
            row.btnDeleteRule.text = if (rule.domain in state.locked) "Schedule delete" else "Delete"
            row.btnDeleteRule.setOnClickListener { showChange(prefs.deleteRule(rule.domain)) }
            container.addView(row.root)
        }
    }

    private fun addRule() {
        val domain = Prefs.normalizeDomain(binding.inputDomain.text?.toString().orEmpty())
        if (domain == null) {
            Toast.makeText(this, "That does not look like a domain", Toast.LENGTH_SHORT).show()
            return
        }
        val limitSeconds = BudgetLogic.parseLimitSeconds(binding.inputMinutes.text?.toString().orEmpty())
        if (limitSeconds == null) {
            Toast.makeText(this, "Enter whole minutes from 0 to ${BudgetLogic.MAX_MINUTES}", Toast.LENGTH_SHORT).show()
            return
        }
        val mode = if (binding.inputHardLimit.isChecked) RuleMode.HARD else RuleMode.NUDGE
        val result = prefs.upsertRule(domain, limitSeconds, mode)
        binding.inputDomain.setText("")
        binding.inputMinutes.setText("")
        binding.inputHardLimit.isChecked = false
        showChange(result)
    }

    // ---------------- browsers ----------------

    private fun renderBrowsers() {
        val container = binding.browsersContainer
        container.removeAllViews()

        val state = prefs.snapshot()
        val selected = (state.pendingBrowsers ?: state.browsers).toMutableSet()
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
                val result = prefs.saveBrowsers(selected.toSet())
                if (result == ChangeResult.QUEUED) Toast.makeText(this, "Browser removal scheduled for the next reset", Toast.LENGTH_SHORT).show()
                renderBrowsersWarning()
                renderPending()
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
        showChange(prefs.saveResetHour(hour))
    }
    private fun showChange(result: ChangeResult) {
        val message = when (result) {
            ChangeResult.UPDATED -> "Saved"
            ChangeResult.QUEUED -> "Change scheduled for the next budget reset"
            ChangeResult.REJECTED -> "This hard limit stays locked until the next budget reset"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        renderRules()
        renderBrowsersWarning()
        renderPending()
    }

    private fun editRule(rule: Rule) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        val minutes = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Minutes per day"
            setText((rule.limitSeconds / 60).toString())
        }
        val hard = SwitchMaterial(this).apply {
            text = "Hard limit — no extensions"
            isChecked = rule.mode == RuleMode.HARD
        }
        form.addView(minutes)
        form.addView(hard)
        val dialog = AlertDialog.Builder(this).setTitle(rule.domain).setView(form)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val limitSeconds = BudgetLogic.parseLimitSeconds(minutes.text.toString())
                if (limitSeconds == null) {
                    minutes.error = "Enter whole minutes from 0 to ${BudgetLogic.MAX_MINUTES}"
                } else {
                    showChange(prefs.upsertRule(rule.domain, limitSeconds, if (hard.isChecked) RuleMode.HARD else RuleMode.NUDGE))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun renderPending() {
        val s = prefs.snapshot()
        binding.pendingChangesText.visibility = if (s.hasPending) View.VISIBLE else View.GONE
        binding.btnCancelPendingChanges.visibility = if (s.hasPending) View.VISIBLE else View.GONE
        if (!s.hasPending) return
        val reset = Instant.ofEpochMilli(s.endsAt).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("EEE HH:mm"))
        binding.pendingChangesText.text = buildString {
            append("Scheduled for $reset:\n")
            s.pendingRules.forEach { (domain, rule) ->
                append(if (rule == null) "Delete $domain" else "$domain: ${rule.limitSeconds / 60}m, ${if (rule.mode == RuleMode.HARD) "hard limit" else "nudge"}")
                append('\n')
            }
            if (s.pendingBrowsers != null) append("Update browser selection\n")
            s.pendingResetHour?.let { append("Reset hour: $it:00\n") }
        }.trimEnd()
    }

}
