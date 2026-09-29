package com.readiness.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readiness.app.ui.DashboardScreen
import com.readiness.app.ui.ReadinessViewModel
import com.readiness.app.work.Scheduler

class MainActivity : ComponentActivity() {

    private val vm: ReadinessViewModel by viewModels()
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        Scheduler.scheduleDaily(this)

        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            DashboardScreen(
                state = state,
                onReload = vm::refresh,
                onSaveSettings = vm::saveSettings,
                onSetCycles = vm::setCycles,
                onSetDayEntry = vm::setDayEntry,
            )
        }
    }
}
