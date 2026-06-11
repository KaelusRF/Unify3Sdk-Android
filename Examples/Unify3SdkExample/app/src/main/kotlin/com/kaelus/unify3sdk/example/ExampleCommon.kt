package com.kaelus.unify3sdk.example

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.kaelus.unify3sdk.CaaInstrumentDefinition
import com.kaelus.unify3sdk.CaaReturnLossFrequencyTrace
import com.kaelus.unify3sdk.CaaReturnLossFrequencyTraceConfigurationResult
import com.kaelus.unify3sdk.CaaReturnLossFrequencyTraceFormat
import com.kaelus.unify3sdk.Cardinality
import com.kaelus.unify3sdk.Instrument
import com.kaelus.unify3sdk.InstrumentType
import com.kaelus.unify3sdk.Test
import com.kaelus.unify3sdk.Unify
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal const val DISCOVERY_TIMEOUT_MS = 60_000L
internal const val BATCHED_RESULT_TIMEOUT_MS = 20_000L

internal fun LinearProgressIndicator.showIndeterminateProgress() {
    isVisible = true
    if (!isIndeterminate) {
        isIndeterminate = true
    }
}

internal fun LinearProgressIndicator.showProgressPercent(percent: Int) {
    val safePercent = percent.coerceIn(0, 100)
    if (isIndeterminate) {
        isIndeterminate = false
    }
    isVisible = true
    setProgressCompat(safePercent, false)
}

internal fun LinearProgressIndicator.resetProgress() {
    if (isIndeterminate) {
        isIndeterminate = false
    }
    setProgressCompat(0, false)
    isVisible = false
}

internal fun requestRuntimePermissionsIfNeeded(
    activity: Activity,
    permissionLauncher: ActivityResultLauncher<Array<String>>,
    appendStatus: (String) -> Unit,
) {
    // Bluetooth discovery requires different runtime permissions depending on
    // the Android version. Older releases gate scanning behind location,
    // while Android 12+ splits that access into explicit Bluetooth permissions.
    val permissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
    }

    val missing = permissions.filter {
        ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
    }

    if (missing.isNotEmpty()) {
        appendStatus("Requesting runtime permissions...")
        permissionLauncher.launch(missing.toTypedArray())
    } else {
        appendStatus("All required permissions already granted")
    }
}

internal suspend fun discoverFirstSupportedInstrument(
    unify: Unify,
    appendStatus: (String) -> Unit,
): Instrument =
    withTimeout(DISCOVERY_TIMEOUT_MS) {
        kotlinx.coroutines.coroutineScope {
            var discovered: Instrument? = null
            // RunBluetoothScan keeps the radio scan alive in the background
            // while RunInstrumentDiscovery emits the current set of instruments
            // visible to the SDK.
            val scanJob = launch {
                runCatching { unify.runBluetoothScan() }
            }

            try {
                unify.runInstrumentDiscovery().first { instruments ->
                    if (instruments.isNotEmpty()) {
                        val seen = instruments.joinToString { "${it.type}:${it.serialNumber}" }
                        appendStatus("Seen instruments: $seen")
                    }

                    val supported = instruments.firstOrNull {
                        it.type == InstrumentType.IWA
                    }
                    if (supported != null) {
                        discovered = supported
                    }
                    supported != null
                }

                requireNotNull(discovered)
            } finally {
                // StopBluetoothScan is what tells the SDK to end Bluetooth
                // discovery cleanly once this example has found an instrument
                // or timed out waiting for one.
                runCatching { unify.stopBluetoothScan() }
                scanJob.cancelAndJoin()
            }
        }
    }

internal suspend fun configureReturnLossFrequencyTrace(
    unify: Unify,
    serialNumber: String,
): Pair<CaaInstrumentDefinition, CaaReturnLossFrequencyTraceConfigurationResult> {
    val instrumentDefinition =
        unify.getInstrumentDefinition(serialNumber) as? CaaInstrumentDefinition
            ?: error("Expected a CAA instrument definition")

    // Read the connected instrument definition first so the example can build
    // a trace request that uses limits and model information valid for the
    // specific hardware that is connected.

    // Configure a single complex return loss trace before running a test or
    // calibration. This lets the SDK validate the request and return the trace
    // configuration details, including whether an OSL calibration is required.
    val testConfigurationResult = unify.configureTest(
        Test(
            traces = listOf(
                CaaReturnLossFrequencyTrace(
                    format = CaaReturnLossFrequencyTraceFormat.COMPLEX,
                    instrumentSerialNumber = serialNumber,
                    instrumentModel = instrumentDefinition.model,
                    frequencyRangeHz = instrumentDefinition.frequencyRangeHz,
                    numberOfPoints = 401,
                    durationS = 60.0,
                )
            ),
            cardinality = Cardinality.SINGLE,
        )
    )

    // The example expects the SDK to accept the trace definition as-is.
    check(testConfigurationResult.valid) { "Test configuration was invalid" }

    val traceConfiguration =
        testConfigurationResult.traceConfigurationResults
            .filterIsInstance<CaaReturnLossFrequencyTraceConfigurationResult>()
            .firstOrNull()
            ?: error("Missing return loss trace configuration")

    return instrumentDefinition to traceConfiguration
}

internal suspend fun disconnectConnectedInstrument(
    unify: Unify,
    serialNumber: String,
    appendStatus: (String) -> Unit,
) {
    appendStatus("Disconnecting $serialNumber...")
    unify.disconnectInstrument(serialNumber)
    appendStatus("Disconnected $serialNumber")
}
