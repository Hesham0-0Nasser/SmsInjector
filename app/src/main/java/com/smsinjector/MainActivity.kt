package com.smsinjector

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
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
    private val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Default time to 14:23 yesterday
        cal.set(Calendar.HOUR_OF_DAY, 14)
        cal.set(Calendar.MINUTE, 23)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        updateDateTimeButtons()

        setupUI()
        initRoot()
    }

    private fun setupUI() {
        // Mode toggle
        binding.rgMode.setOnCheckedChangeListener { _, id ->
            val existing = id == R.id.rbExisting
            binding.layoutNewSender.visibility = if (existing) View.GONE else View.VISIBLE
            binding.layoutExisting.visibility  = if (existing) View.VISIBLE else View.GONE
        }

        // Date picker
        binding.btnDate.setOnClickListener {
            DatePickerDialog(this,
                { _, y, m, d -> cal.set(y, m, d); updateDateTimeButtons() },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        // Time picker
        binding.btnTime.setOnClickListener {
            TimePickerDialog(this,
                { _, h, min -> cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min); updateDateTimeButtons() },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                true
            ).show()
        }

        // Inject
        binding.btnInject.setOnClickListener { onInject() }
    }

    private fun updateDateTimeButtons() {
        binding.btnDate.text = "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
        binding.btnTime.text = "%02d:%02d".format(
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE)
        )
    }

    private fun initRoot() {
        binding.tvStatus.text = "Checking root…"
        binding.btnInject.isEnabled = false

        lifecycleScope.launch {
            val hasRoot = withContext(Dispatchers.IO) { Shell.rootAccess() }
            if (!hasRoot) {
                binding.tvStatus.text = "Root access denied — grant root to this app in Magisk"
                return@launch
            }

            val info = withContext(Dispatchers.IO) { Injector.detectTools() }
            if (info.error != null) {
                binding.tvStatus.text = "Error: ${info.error}"
                return@launch
            }

            binding.tvStatus.text = "${info.pkg}  ·  ${info.sq3.substringAfterLast("/")}"
            binding.btnInject.isEnabled = true

            // Load threads
            val loaded = withContext(Dispatchers.IO) { Injector.loadThreads() }
            threads.clear()
            threads.addAll(loaded)
            val adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                threads
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            binding.spinnerThreads.adapter = adapter
            binding.tvThreadCount.text = "Pick thread  (${threads.size} found)"
        }
    }

    private fun onInject() {
        val body = binding.etBody.text.toString().trim()
        if (body.isEmpty()) { toast("Message body is empty"); return }

        val isExisting = binding.rbExisting.isChecked
        val senderInput = binding.etSender.text.toString().trim()
        if (!isExisting && senderInput.isEmpty()) { toast("Enter a sender name"); return }

        binding.btnInject.isEnabled = false
        binding.btnInject.text = "Injecting…"
        binding.tvResult.visibility = View.GONE

        val tsMs = cal.timeInMillis

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (isExisting) {
                    val thread = threads.getOrNull(binding.spinnerThreads.selectedItemPosition)
                        ?: return@withContext Injector.InjectResult(false, "No thread selected")
                    Injector.inject(thread.address, body, tsMs, thread.id)
                } else {
                    val threadId = Injector.getOrCreateThreadId(senderInput)
                        ?: return@withContext Injector.InjectResult(
                            false, "Could not create thread for '$senderInput'")
                    Injector.inject(senderInput, body, tsMs, threadId)
                }
            }

            binding.tvResult.visibility = View.VISIBLE
            binding.tvResult.text = result.message
            if (result.ok) {
                binding.tvResult.setBackgroundColor(Color.parseColor("#0d2b0d"))
                binding.tvResult.setTextColor(Color.parseColor("#66bb6a"))
            } else {
                binding.tvResult.setBackgroundColor(Color.parseColor("#2b0d0d"))
                binding.tvResult.setTextColor(Color.parseColor("#ef5350"))
            }
            binding.tvResult.scrollTo(0, 0)
            binding.btnInject.isEnabled = true
            binding.btnInject.text = "Inject SMS"
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
