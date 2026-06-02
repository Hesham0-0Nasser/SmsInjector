package com.smsinjector

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.smsinjector.databinding.ActivityMainBinding
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val threads = mutableListOf<Injector.SmsThread>()
    private val cal = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -1)
        set(Calendar.HOUR_OF_DAY, 14)
        set(Calendar.MINUTE, 23)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateDateTimeButtons()
        setupListeners()
        initRootAndLoad()
    }

    private fun setupListeners() {
        binding.rgMode.setOnCheckedChangeListener { _, id ->
            val existing = (id == R.id.rbExisting)
            binding.layoutNewSender.visibility = if (existing) View.GONE  else View.VISIBLE
            binding.layoutExisting.visibility  = if (existing) View.VISIBLE else View.GONE
        }

        binding.btnDate.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                cal.set(y, m, d); updateDateTimeButtons()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        binding.btnTime.setOnClickListener {
            TimePickerDialog(this, { _, h, min ->
                cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min); updateDateTimeButtons()
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }

        binding.btnInject.setOnClickListener { onInject() }
    }

    private fun updateDateTimeButtons() {
        binding.btnDate.text = "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        binding.btnTime.text = "%02d:%02d".format(
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }

    private fun initRootAndLoad() {
        binding.tvStatus.text = "Requesting root access…"
        binding.btnInject.isEnabled = false

        lifecycleScope.launch {
            // Step 1 — request root (triggers the Magisk "Grant" dialog)
            // Shell.rootAccess() triggers the Magisk "Grant" dialog properly
            val hasRoot = withContext(Dispatchers.IO) { Shell.rootAccess() }
            if (!hasRoot) {
                binding.tvStatus.text = "Root denied — open Magisk, grant root to SMS Injector, then reopen the app"
                return@launch
            }

            // Step 2 — detect sqlite3 + SMS app
            binding.tvStatus.text = "Detecting tools…"
            val info = withContext(Dispatchers.IO) { Injector.detectTools() }
            if (info.error != null) {
                binding.tvStatus.text = "Error: ${info.error}"
                return@launch
            }

            binding.tvStatus.text = "${info.pkg.substringAfterLast(".")}  ·  ${info.sq3.substringAfterLast("/")}"
            binding.btnInject.isEnabled = true

            // Step 3 — load thread list
            val loaded = withContext(Dispatchers.IO) { Injector.loadThreads() }
            threads.clear()
            threads.addAll(loaded)
            binding.spinnerThreads.adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                threads
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            binding.tvThreadCount.text = "Pick thread  (${threads.size} found)"
        }
    }

    private fun onInject() {
        val body = binding.etBody.text.toString().trim()
        if (body.isEmpty()) { toast("Message body is empty"); return }

        val isExisting  = binding.rbExisting.isChecked
        val senderInput = binding.etSender.text.toString().trim()
        if (!isExisting && senderInput.isEmpty()) { toast("Enter a sender name"); return }

        // Capture UI values on main thread before switching to IO
        val selectedPos = binding.spinnerThreads.selectedItemPosition
        val tsMs        = cal.timeInMillis

        binding.btnInject.isEnabled = false
        binding.btnInject.text      = "Injecting…"
        binding.tvResult.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (isExisting) {
                    val thread = threads.getOrNull(selectedPos)
                        ?: return@withContext Injector.InjectResult(false, "No thread selected")
                    Injector.inject(thread.address, body, tsMs, thread.id)
                } else {
                    val threadId = Injector.getOrCreateThreadId(senderInput)
                        ?: return@withContext Injector.InjectResult(false,
                            "Could not create thread for '$senderInput'.\nMake sure root is working.")
                    Injector.inject(senderInput, body, tsMs, threadId)
                }
            }

            binding.tvResult.visibility = View.VISIBLE
            binding.tvResult.text = result.message
            binding.tvResult.setBackgroundColor(
                Color.parseColor(if (result.ok) "#0d2b0d" else "#2b0d0d"))
            binding.tvResult.setTextColor(
                Color.parseColor(if (result.ok) "#66bb6a" else "#ef5350"))

            binding.btnInject.isEnabled = true
            binding.btnInject.text      = "Inject SMS"
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
