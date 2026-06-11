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
import com.kaelus.unify3sdk.OslCalibration
import com.kaelus.unify3sdk.OslCalibrationConfigurationResult
import com.kaelus.unify3sdk.OslCalibrationState
import com.kaelus.unify3sdk.Unify
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class OslCalibrationActivity : AppCompatActivity() {

    private lateinit var unify: Unify
    private lateinit var nextStepButton: Button
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var statusText: TextView

    private var discoveredInstrument: Instrument? = null
    private var connectedSerialNumber: String? = null
    private var configuredCalibration: OslCalibration? = null
    private var latestCalibrationState: OslCalibrationState? = null
    private var lastCalibrationSummary: String? = null
    private var lastStartedStepId: String? = null
    private var calibrationJob: Job? = null
    private var logJob: Job? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            appendStatus("Permission results: $results")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_osl_calibration)

        unify = Unify.getInstance(applicationContext)
        unify.setLogLevels(LogLevel.INFO, LogLevel.ERROR)

        nextStepButton = findViewById(R.id.nextStepButton)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        appendStatus("OSL Calibration example")
        appendStatus("Flow: Discover -> Connect -> Prepare -> Run Next Step")
        updateNextStepButtonText()

        findViewById<Button>(R.id.backToExamplesButton).setOnClickListener {
            lifecycleScope.launch {
                runCatching {
                    calibrationJob?.cancel()
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

        findViewById<Button>(R.id.prepareButton).setOnClickListener {
            clearStatus()
            lifecycleScope.launch {
                runCatching {
                    configuredCalibration = prepareCalibration()
                    appendStatus("Calibration ready: ${configuredCalibration?.calKitName}")
                    observeCalibrationState()
                }.onFailure { appendStatus("Prepare failed: ${it.message}") }
            }
        }

        nextStepButton.setOnClickListener {
            clearStatus()
            lifecycleScope.launch {
                runCatching {
                    runNextCalibrationStep()
                }.onFailure {
                    progressBar.resetProgress()
                    appendStatus("Step failed: ${it.message}")
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
        // runs the calibration workflow.
        logJob = lifecycleScope.launch {
            unify.getLogs().collect { log ->
                appendStatus("[${log.time}] ${log.level}/${log.logger}: ${log.message}")
            }
        }
    }

    private suspend fun prepareCalibration(): OslCalibration {
        val serialNumber = requireNotNull(connectedSerialNumber) { "Connect first" }

        lastStartedStepId = null
        latestCalibrationState = null
        lastCalibrationSummary = null
        updateNextStepButtonText()

        // Configure the same return loss trace used by the measurement example.
        // The SDK uses that trace definition to determine which OSL calibration
        // applies to the connected instrument and frequency range.
        val (instrumentDefinition, traceConfiguration) =
            configureReturnLossFrequencyTrace(unify, serialNumber)

        val calibration = traceConfiguration.calibration ?: error("No OSL calibration returned")

        // ConfigureCalibration validates the calibration request itself before
        // the step-by-step workflow begins.
        val configurationResult = unify.configureCalibration(calibration)
        check(configurationResult is OslCalibrationConfigurationResult) {
            "Expected OSL calibration configuration result"
        }

        check(configurationResult.validationErrors.isEmpty()) {
            val validationErrors = configurationResult.validationErrors.joinToString { it.type.name }
            "Calibration configuration was invalid: $validationErrors"
        }

        appendStatus(
            "Configured ${instrumentDefinition.model} for ${calibration.frequencyRangeHz.start}..${calibration.frequencyRangeHz.end} Hz"
        )

        return calibration
    }

    private fun observeCalibrationState() {
        calibrationJob?.cancel()

        calibrationJob = lifecycleScope.launch {
            // RunCalibration emits the current state of the whole calibration.
            // Each update tells the UI which step is ready, which ones are
            // complete, and which instructions should be shown next.
            unify.runCalibration().collect { state ->
                val oslState = state as? OslCalibrationState ?: return@collect
                latestCalibrationState = oslState

                val summary = oslState.steps.joinToString(separator = "\n") { step ->
                    val stepStatus = when {
                        step.complete -> "complete"
                        step.enabled -> "ready"
                        else -> "waiting"
                    }
                    "- ${step.name}: $stepStatus"
                }

                if (summary != lastCalibrationSummary) {
                    appendStatus("Calibration state:\n$summary")
                    lastCalibrationSummary = summary
                }

                updateNextStepButtonText(oslState)

                if (oslState.steps.all { it.complete }) {
                    appendStatus("Calibration complete")
                }
            }
        }
    }

    private suspend fun runNextCalibrationStep() {
        val state = requireNotNull(latestCalibrationState) { "Prepare calibration first" }
        val nextStep = state.steps.firstOrNull { it.enabled && !it.complete }
            ?: error("No enabled calibration step available")

        if (nextStep.id == lastStartedStepId) {
            appendStatus("Waiting for next calibration state update")
            return
        }

        lastStartedStepId = nextStep.id
        appendStatus("Running step: ${nextStep.name}")
        appendStatus(nextStep.instruction)
        progressBar.showIndeterminateProgress()

        // Each calibration step reports progress on its own stream, separate
        // from the broader RunCalibration state updates.
        unify.runCalibrationStep(nextStep.id).collect { progress ->
            if (progress < 0) {
                progressBar.showIndeterminateProgress()
            } else {
                progressBar.showProgressPercent((progress * 100).toInt())
            }
        }

        progressBar.resetProgress()
        appendStatus("Completed step: ${nextStep.name}")
    }

    private fun updateNextStepButtonText(state: OslCalibrationState? = latestCalibrationState) {
        val nextStepName = state?.steps?.firstOrNull { it.enabled && !it.complete }?.name
        nextStepButton.text = if (nextStepName == null) {
            getString(R.string.run_next_step)
        } else {
            getString(R.string.run_named_step, nextStepName)
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
        calibrationJob?.cancel()
        super.onDestroy()
    }
}
