package com.kaelus.unify3sdk.example

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kaelus.unify3sdk.ConnectionResult
import com.kaelus.unify3sdk.Instrument
import com.kaelus.unify3sdk.LogLevel
import com.kaelus.unify3sdk.Unify
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class ReturnLossTestActivity : AppCompatActivity() {

    private lateinit var unify: Unify
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var statusText: TextView

    private var discoveredInstrument: Instrument? = null
    private var connectedSerialNumber: String? = null
    private var logJob: Job? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            appendStatus("Permission results: $results")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_return_loss_test)

        unify = Unify.getInstance(applicationContext)
        unify.setLogLevels(LogLevel.INFO, LogLevel.ERROR)

        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        appendStatus("Return Loss Test example")
        appendStatus("Flow: Discover -> Connect -> Run Test")

        findViewById<Button>(R.id.backToExamplesButton).setOnClickListener {
            lifecycleScope.launch {
                runCatching {
                    connectedSerialNumber?.let { serialNumber ->
                        disconnectConnectedInstrument(unify, serialNumber, ::appendStatus)
                        connectedSerialNumber = null
                    }
                    finish()
                }.onFailure { appendStatus("Disconnect failed: ${it.message}") }
            }
        }

        findViewById<Button>(R.id.discoverButton).setOnClickListener {
            clearStatus()
            lifecycleScope.launch {
                runCatching {
                    appendStatus("Starting discovery (up to 60s)...")
                    discoveredInstrument = discoverFirstSupportedInstrument(unify, ::appendStatus)
                    appendStatus("Discovered ${discoveredInstrument?.type} ${discoveredInstrument?.serialNumber}")
                }.onFailure { appendStatus("Discover failed: ${it.message}") }
            }
        }

        findViewById<Button>(R.id.connectButton).setOnClickListener {
            clearStatus()
            lifecycleScope.launch {
                runCatching {
                    val instrument = requireNotNull(discoveredInstrument) { "Discover first" }
                    appendStatus("Connecting to ${instrument.serialNumber}...")
                    val result = unify.connectInstrument(instrument.serialNumber)
                    check(result == ConnectionResult.SUCCESS) { "Connect result: $result" }
                    connectedSerialNumber = instrument.serialNumber
                    appendStatus("Connected ${instrument.serialNumber}")
                }.onFailure { appendStatus("Connect failed: ${it.message}") }
            }
        }

        findViewById<Button>(R.id.runTestButton).setOnClickListener {
            clearStatus()
            lifecycleScope.launch {
                runCatching {
                    runReturnLossFrequencyTestExample()
                }.onFailure {
                    progressBar.resetProgress()
                    appendStatus("Run test failed: ${it.message}")
                }
            }
        }

        startLogStream()
        requestRuntimePermissionsIfNeeded(this, permissionLauncher, ::appendStatus)
    }

    private fun startLogStream() {
        if (logJob?.isActive == true) {
            return
        }

        // Read the SDK log stream on a background coroutine so logging can
        // continue while the rest of the example discovers, connects, and
        // runs the measurement.
        logJob = lifecycleScope.launch {
            unify.getLogs().collect { log ->
                appendStatus("[${log.time}] ${log.level}/${log.logger}: ${log.message}")
            }
        }
    }

    private suspend fun runReturnLossFrequencyTestExample() {
        val serialNumber = requireNotNull(connectedSerialNumber) { "Connect first" }

        kotlinx.coroutines.coroutineScope {
            // Configure one return loss versus frequency trace before starting
            // the test. The SDK validates that request against the connected
            // instrument and returns the effective trace configuration.
            val (instrumentDefinition, traceConfiguration) =
                configureReturnLossFrequencyTrace(unify, serialNumber)

            appendStatus(
                "Configured return loss trace for ${instrumentDefinition.model} (${traceConfiguration.trace.frequencyRangeHz.start}..${traceConfiguration.trace.frequencyRangeHz.end} Hz)"
            )

            // Batched test results arrive on a separate SDK stream after the
            // test finishes, so start waiting for the first result before the
            // measurement begins.
            val batchedResultDeferred = async {
                withTimeout(BATCHED_RESULT_TIMEOUT_MS) {
                    unify.getBatchedTestResults().first()
                }
            }

            var lastReportedPercent = -1
            appendStatus("Running test...")
            // RunTest emits progress updates while the instrument executes the
            // configured measurement.
            unify.runTest(checkRl = true).collect { progressUpdate ->
                if (progressUpdate.traceProgress < 0) {
                    if (lastReportedPercent != Int.MIN_VALUE) {
                        progressBar.showIndeterminateProgress()
                        lastReportedPercent = Int.MIN_VALUE
                    }
                } else {
                    val percent = (progressUpdate.traceProgress * 100).toInt().coerceIn(0, 100)
                    if (percent != lastReportedPercent) {
                        progressBar.showProgressPercent(percent)
                        lastReportedPercent = percent
                    }
                }
            }

            // Once RunTest completes, the pending batched result contains the
            // trace points returned by the SDK for the configured test.
            val result = batchedResultDeferred.await()
            progressBar.resetProgress()
            appendStatus("Test complete. Trace ${result.traceIndex} returned ${result.tracePoints.size} points")
        }
    }

    private fun appendStatus(message: String) {
        statusText.append(message)
        statusText.append("\n\n")
    }

    private fun clearStatus() {
        progressBar.resetProgress()
        statusText.text = ""
    }

    override fun onDestroy() {
        logJob?.cancel()
        super.onDestroy()
    }
}
