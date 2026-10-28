package com.lumenarcanus.dialife

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumenarcanus.dialife.ui.connect.ConnectScreen
import com.lumenarcanus.dialife.ui.home.HomeScreen
import com.lumenarcanus.dialife.ui.home.HomeViewModel
import com.lumenarcanus.dialife.ui.data.DataScreen
import com.lumenarcanus.dialife.data.Thresholds
import com.lumenarcanus.dialife.data.DiaLifeApi
import com.lumenarcanus.dialife.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import com.lumenarcanus.dialife.ui.home.BackgroundSetup
import com.lumenarcanus.dialife.live.LiveService
import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import com.lumenarcanus.dialife.ui.stats.StatsScreen
import com.lumenarcanus.dialife.ui.log.AlarmLogScreen
import com.lumenarcanus.dialife.ui.settings.SettingsScreen
import com.lumenarcanus.dialife.ui.handling.HandlingScreen
import com.lumenarcanus.dialife.live.ActionReceiver
import android.app.NotificationManager
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lumenarcanus.dialife.ui.home.unitsOf
import com.lumenarcanus.dialife.data.Credentials
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenarcanus.dialife.ui.theme.DiaLifeTheme
import com.lumenarcanus.dialife.ui.theme.DiaTheme

private enum class Screen { Home, Stats, Log, Settings, Handling, Data, WebLogin, Meal, DoseSettings }

// AppCompatActivity (not plain ComponentActivity) so the in-app language works on Android 12 too.
class MainActivity : AppCompatActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleLink(intent)
            intent?.getLongExtra(EXTRA_HANDLING, 0L)?.takeIf { it != 0L }?.let { openHandling = it }
        }
        setContent {
            val dark = when (com.lumenarcanus.dialife.data.Prefs.theme) {
                com.lumenarcanus.dialife.data.ThemeChoice.Light -> false
                com.lumenarcanus.dialife.data.ThemeChoice.Dark -> true
                com.lumenarcanus.dialife.data.ThemeChoice.System -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            DiaLifeTheme(dark = dark) {
                val session by vm.session.collectAsStateWithLifecycle()
                val connect by vm.connect.collectAsStateWithLifecycle()
                val pending by vm.pending.collectAsStateWithLifecycle()
                pending?.let { p ->
                    AlertDialog(
                        onDismissRequest = vm::cancelReplace,
                        title = { Text(stringResource(R.string.replace_title)) },
                        text = {
                            Text(
                                if (p.incoming.isNullOrBlank()) stringResource(R.string.replace_body_unnamed, p.current)
                                else stringResource(R.string.replace_body, p.current, p.incoming),
                            )
                        },
                        confirmButton = { TextButton(onClick = vm::confirmReplace) { Text(stringResource(R.string.replace_confirm)) } },
                        dismissButton = { TextButton(onClick = vm::cancelReplace) { Text(stringResource(R.string.replace_keep)) } },
                    )
                }
                when (val s = session) {
                    Session.Loading -> Box(Modifier.fillMaxSize().background(DiaTheme.colors.background))
                    Session.Disconnected -> ConnectScreen(
                        state = connect,
                        onScanned = vm::onLink,
                        onScannerFailed = vm::onScannerFailed,
                        onManual = vm::onManual,
                    )
                    is Session.Connected -> {
                        var screen by rememberSaveable { mutableStateOf(Screen.Home) }
                        var handling by rememberSaveable { mutableStateOf(0L) }
                        // the food screen: what it edits, and the question it answers
                        var mealEdit by rememberSaveable { mutableStateOf<String?>(null) }
                        var mealQuestion by rememberSaveable { mutableStateOf(0L) }
                        var mealFrom by rememberSaveable { mutableStateOf(0L) }
                        var mealLow by rememberSaveable { mutableStateOf(false) } // carbs for a low: no dose
                        var mealBack by rememberSaveable { mutableStateOf(Screen.Home) } // where the food screen returns
                        var mealEntry by remember { mutableStateOf<com.lumenarcanus.dialife.data.Treatment?>(null) } // an older entry, from "Data"
                        // "I'm on it" from the alarm screen or a notification lands here
                        LaunchedEffect(openHandling) {
                            if (openHandling != 0L) { handling = openHandling; screen = Screen.Handling; openHandling = 0L }
                        }
                        BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
                        when (screen) {
                            Screen.Home -> Home(s.credentials, onMeal = { tr, q ->
                                mealEdit = tr?.id; mealEntry = tr; mealQuestion = q?.id ?: 0L; mealFrom = q?.from ?: 0L; mealLow = false; mealBack = Screen.Home; screen = Screen.Meal
                            }) { screen = it }
                            Screen.Meal -> {
                                val home = viewModel { HomeViewModel(liveRepository) }
                                val ui by home.ui.collectAsStateWithLifecycle()
                                val all = ui.history?.treatments.orEmpty()
                                com.lumenarcanus.dialife.ui.meal.MealScreen(
                                    repo = liveRepository,
                                    treatments = all,
                                    dose = ui.state?.dose ?: com.lumenarcanus.dialife.data.DoseSettings(),
                                    mealGap = ui.state?.mealGapMin ?: com.lumenarcanus.dialife.data.Meals.DEFAULT_GAP_MIN,
                                    planned = ui.state?.planned.orEmpty(),
                                    units = unitsOf(ui.state?.displayUnits ?: s.credentials.displayUnits),
                                    editing = mealEdit?.let { id -> all.firstOrNull { it.id == id } ?: mealEntry?.takeIf { it.id == id } },
                                    forLow = mealLow,
                                    initialTime = mealFrom.takeIf { it > 0 },
                                    onSave = liveRepository::saveTreatment,
                                    onSaved = { food ->
                                        val q = mealQuestion
                                        if (q != 0L) lifecycleScope.launch { liveRepository.answerQuestion(q, "food", food.id).onSuccess { questionNotifier.cancel(q) } }
                                        mealEdit = null; mealEntry = null; mealQuestion = 0L; mealFrom = 0L; screen = mealBack
                                    },
                                    onClose = { mealEdit = null; mealEntry = null; mealQuestion = 0L; mealFrom = 0L; screen = mealBack },
                                )
                            }
                            Screen.DoseSettings -> {
                                val live by liveRepository.live.collectAsStateWithLifecycle()
                                com.lumenarcanus.dialife.ui.settings.DoseSettingsScreen(
                                    dose = live.state?.dose ?: com.lumenarcanus.dialife.data.DoseSettings(),
                                    units = unitsOf(live.state?.displayUnits ?: s.credentials.displayUnits),
                                    onSave = { d -> liveRepository.putDose(d) },
                                    onBack = { screen = Screen.Settings },
                                )
                            }
                            Screen.Stats -> StatsScreen(
                                creds = s.credentials,
                                units = unitsOf(liveRepository.live.value.state?.displayUnits ?: s.credentials.displayUnits),
                                onBack = { screen = Screen.Home },
                            )
                            Screen.Settings -> {
                                var setup by remember { mutableStateOf(backgroundSetup()) }
                                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { setup = backgroundSetup() }
                                val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { setup = backgroundSetup() }
                                val live by liveRepository.live.collectAsStateWithLifecycle()
                                SettingsScreen(
                                    creds = s.credentials,
                                    state = live.state,
                                    setup = setup,
                                    onFixNotifications = { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                    onFixBattery = ::askBatteryExemption,
                                    onFixFullScreen = ::askFullScreen,
                                    onTestSound = { on -> if (on) alarmController.ringer.start(-1) else alarmController.ringer.stop(-1) },
                                    onDisconnect = { screen = Screen.Home; vm.disconnect() },
                                    onOpenDose = { screen = Screen.DoseSettings },
                                    onBack = { screen = Screen.Home },
                                )
                            }
                            Screen.Handling -> {
                                val home = viewModel { HomeViewModel(liveRepository) }
                                val ui by home.ui.collectAsStateWithLifecycle()
                                HandlingScreen(
                                    ui = ui,
                                    alarmId = handling,
                                    units = unitsOf(ui.state?.displayUnits ?: s.credentials.displayUnits),
                                    onAct = ::act,
                                    onSaveTreatment = liveRepository::saveTreatment,
                                    onBack = { screen = Screen.Home },
                                    onOpenMeal = { mealEdit = null; mealEntry = null; mealQuestion = 0L; mealFrom = 0L; mealLow = true; mealBack = Screen.Handling; screen = Screen.Meal },
                                )
                            }
                            Screen.Data -> {
                                val home = viewModel { HomeViewModel(liveRepository) }
                                val ui by home.ui.collectAsStateWithLifecycle()
                                DataScreen(
                                    history = ui.history,
                                    questions = ui.state?.questions.orEmpty(),
                                    thresholds = ui.state?.thresholds ?: Thresholds(),
                                    units = unitsOf(ui.state?.displayUnits ?: s.credentials.displayUnits),
                                    spanHours = ui.rangeHours,
                                    now = ui.now,
                                    loadHistory = { from, to -> DiaLifeApi().history(s.credentials, from, to).getOrNull() },
                                    onSaveTreatment = liveRepository::saveTreatment,
                                    onBack = { screen = Screen.Home },
                                    onOpenMeal = { tr -> mealEdit = tr.id; mealEntry = tr; mealQuestion = 0L; mealFrom = 0L; mealLow = false; mealBack = Screen.Data; screen = Screen.Meal },
                                )
                            }
                            Screen.WebLogin -> com.lumenarcanus.dialife.ui.web.WebLoginScreen(
                                creds = s.credentials,
                                onBack = { screen = Screen.Home },
                            )
                            Screen.Log -> AlarmLogScreen(
                                creds = s.credentials,
                                units = unitsOf(liveRepository.live.value.state?.displayUnits ?: s.credentials.displayUnits),
                                onBack = { screen = Screen.Home },
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Home(creds: Credentials, onMeal: (com.lumenarcanus.dialife.data.Treatment?, com.lumenarcanus.dialife.data.Question?) -> Unit, onOpen: (Screen) -> Unit) {
        val home = viewModel { HomeViewModel(liveRepository) }
        LaunchedEffect(creds) {
            liveRepository.start(creds) // the screen works even if the service cannot start
            LiveService.start(this@MainActivity)
        }
        val ui by home.ui.collectAsStateWithLifecycle()
        LaunchedEffect(ui.unauthorized) { if (ui.unauthorized) vm.revoked() }

        // what still stops readings arriving in the background; re-checked on every return to the app
        var setup by remember { mutableStateOf(backgroundSetup()) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            setup = backgroundSetup()
            LiveService.start(this@MainActivity) // revives a stopped service, re-posts its notification
        }
        val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            setup = backgroundSetup()
            LiveService.start(this@MainActivity)
        }
        HomeScreen(
            ui = ui,
            fallbackUnits = unitsOf(creds.displayUnits),
            deviceName = creds.deviceName,
            setup = setup,
            onAllowNotifications = { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
            onAllowBackground = ::askBatteryExemption,
            onAllowFullScreen = ::askFullScreen,
            onAlarmAction = { action, id ->
                act(action, id)
                if (action == ActionReceiver.HANDLE) openHandling = id // took it: straight to "you are handling"
            },
            onRange = home::setRange,
            onOpenStats = { onOpen(Screen.Stats) },
            onOpenLog = { onOpen(Screen.Log) },
            onOpenData = { onOpen(Screen.Data) },
            onOpenWebLogin = { onOpen(Screen.WebLogin) },
            onOpenMeal = onMeal,
            onPlanned = { act -> planned(act) },
            onOpenSettings = { onOpen(Screen.Settings) },
            onOpenHandling = { id -> openHandling = id },
            onRetry = liveRepository::reconnectNow,
            onSaveTreatment = liveRepository::saveTreatment,
            onDeleteTreatment = liveRepository::deleteTreatment,
            onAnswerQuestion = { id, answer, tr ->
                lifecycleScope.launch {
                    liveRepository.answerQuestion(id, answer, tr)
                        .onSuccess { questionNotifier.cancel(id) }
                        .onFailure { Toast.makeText(this@MainActivity, R.string.q_failed, Toast.LENGTH_LONG).show() }
                }
            },
            onDismissQuestion = { id ->
                lifecycleScope.launch {
                    liveRepository.dismissQuestion(id).onFailure { Toast.makeText(this@MainActivity, R.string.q_failed, Toast.LENGTH_LONG).show() }
                }
            },
            onDisconnect = vm::disconnect,
        )
    }

    /** Given, changed or not given: the insulin entry first, then the dose closed on the server. */
    private fun planned(act: com.lumenarcanus.dialife.ui.meal.PlannedAction) {
        lifecycleScope.launch {
            val r = when (act) {
                is com.lumenarcanus.dialife.ui.meal.PlannedAction.Given -> {
                    if (act.isNew) liveRepository.saveTreatment(act.insulin)
                    else if (act.insulin.mealId == null) liveRepository.saveTreatment(act.insulin.copy(mealId = act.planned.mealId, insulinRole = "meal"))
                    liveRepository.plannedGiven(act.planned.id, act.insulin.id)
                }
                is com.lumenarcanus.dialife.ui.meal.PlannedAction.Change -> liveRepository.planDose(act.planned.copy(dose = act.dose))
                is com.lumenarcanus.dialife.ui.meal.PlannedAction.Skip -> liveRepository.plannedSkip(act.planned.id)
            }
            // the server sends every phone the new state itself
            r.onFailure { Toast.makeText(this@MainActivity, R.string.planned_failed, Toast.LENGTH_LONG).show() }
        }
    }

    private fun backgroundSetup() = BackgroundSetup(
        notifications = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED,
        battery = !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
        // Android 14+ grants full-screen alarms only to apps the user allowed
        fullScreen = Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
    )

    private fun act(action: String, id: Long) {
        lifecycleScope.launch {
            alarmController.act(action, id).onFailure {
                Toast.makeText(this@MainActivity, R.string.action_failed_body, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun askFullScreen() {
        startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
    }

    @SuppressLint("BatteryLife") // a family alarm relay is exactly the case this exists for
    private fun askBatteryExemption() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
        intent.getLongExtra(EXTRA_HANDLING, 0L).takeIf { it != 0L }?.let { openHandling = it }
    }

    /** An alarm someone on this phone just took: show the "you are handling" screen. */
    private var openHandling by mutableStateOf(0L)

    companion object {
        const val EXTRA_HANDLING = "handling"
    }

    /** dialife://connect?... from the phone camera or another app. */
    private fun handleLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "dialife") vm.onLink(data.toString())
    }
}
