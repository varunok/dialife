package com.lumenarcanus.dialife

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.theme.DiaLifeTheme
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.core.ui.unitsOf
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.feature.alarms.AlarmLogScreen
import com.lumenarcanus.dialife.feature.connect.ConnectRoute
import com.lumenarcanus.dialife.feature.connect.ConnectViewModel
import com.lumenarcanus.dialife.feature.data.DataRoute
import com.lumenarcanus.dialife.feature.home.BackgroundSetup
import com.lumenarcanus.dialife.feature.home.HomeScreen
import com.lumenarcanus.dialife.feature.home.HomeViewModel
import com.lumenarcanus.dialife.live.LiveService
import com.lumenarcanus.dialife.navigation.Destination
import kotlinx.coroutines.launch

// AppCompatActivity (not plain ComponentActivity) so the in-app language works on Android 12 too.
class MainActivity : AppCompatActivity() {
    private val vm: AppViewModel by viewModels { viewModelFactory { initializer { AppViewModel(sessionRepository) } } }
    private val connectVm: ConnectViewModel by viewModels { viewModelFactory { initializer { ConnectViewModel(sessionRepository) } } }

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
                val pending by vm.pending.collectAsStateWithLifecycle()
                pending?.let { p ->
                    AlertDialog(
                        onDismissRequest = vm::cancelReplace,
                        title = { Text(stringResource(R.string.replace_title)) },
                        text = {
                            Text(
                                if (p.incoming.isNullOrBlank()) {
                                    stringResource(R.string.replace_body_unnamed, p.current)
                                } else {
                                    stringResource(R.string.replace_body, p.current, p.incoming)
                                }
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                vm.confirmReplace()?.let(connectVm::onLink)
                            }) { Text(stringResource(R.string.replace_confirm)) }
                        },
                        dismissButton = { TextButton(onClick = vm::cancelReplace) { Text(stringResource(R.string.replace_keep)) } }
                    )
                }
                when (val s = session) {
                    Session.Loading -> Box(Modifier.fillMaxSize().background(DiaTheme.colors.background))
                    Session.Disconnected -> ConnectRoute(connectVm)
                    is Session.Connected -> Connected(s.credentials)
                }
            }
        }
    }

    /** The app once connected: one back-stack entry per screen, home at the bottom. */
    @Composable
    private fun Connected(creds: Credentials) {
        val nav = rememberNavController()
        fun open(d: Destination) = nav.navigate(d) { launchSingleTop = true }
        val back: () -> Unit = { if (nav.previousBackStackEntry != null) nav.popBackStack() }
        // "I'm on it" from the alarm screen or a notification lands here, over home
        LaunchedEffect(openHandling) {
            if (openHandling != 0L) {
                nav.navigate(Destination.Handling(openHandling)) {
                    popUpTo<Destination.Home>()
                    launchSingleTop = true
                }
                openHandling = 0L
            }
        }
        // screens switch at once, as they always have
        NavHost(
            nav,
            startDestination = Destination.Home,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None }
        ) {
            composable<Destination.Home> { Home(creds, onOpen = ::open) }
            composable<Destination.Meal>(typeMap = Destination.Meal.typeMap) { entry ->
                val m = entry.toRoute<Destination.Meal>()
                com.lumenarcanus.dialife.feature.meal.MealRoute(
                    editingId = m.editingId,
                    entry = m.entry,
                    initialTime = m.initialTime,
                    forLow = m.forLow,
                    questionId = m.questionId,
                    onDone = back
                )
            }
            composable<Destination.DoseSettings> { com.lumenarcanus.dialife.feature.settings.DoseSettingsRoute(onBack = back) }
            composable<Destination.Stats> { com.lumenarcanus.dialife.feature.stats.StatsRoute(onBack = back) }
            composable<Destination.Settings> {
                var setup by remember { mutableStateOf(backgroundSetup()) }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { setup = backgroundSetup() }
                val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    setup =
                        backgroundSetup()
                }
                com.lumenarcanus.dialife.feature.settings.SettingsRoute(
                    creds = creds,
                    setup = setup,
                    onFixNotifications = { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    onFixBattery = ::askBatteryExemption,
                    onFixFullScreen = ::askFullScreen,
                    onDisconnected = { nav.popBackStack(Destination.Home, inclusive = false) },
                    onOpenDose = { open(Destination.DoseSettings) },
                    onBack = back
                )
            }
            composable<Destination.Handling> { entry ->
                com.lumenarcanus.dialife.feature.alarms.HandlingRoute(
                    alarmId = entry.toRoute<Destination.Handling>().alarmId,
                    onBack = back,
                    onOpenMeal = { open(Destination.Meal(forLow = true)) }
                )
            }
            composable<Destination.Data> { entry ->
                DataRoute(
                    spanHours = entry.toRoute<Destination.Data>().spanHours,
                    onBack = back,
                    onOpenMeal = { tr -> open(Destination.Meal(editingId = tr.id, entry = tr)) }
                )
            }
            composable<Destination.WebLogin> { com.lumenarcanus.dialife.feature.weblogin.WebLoginRoute(onBack = back) }
            composable<Destination.Log> {
                AlarmLogScreen(
                    creds = creds,
                    units = unitsOf(liveRepository.live.value.state?.displayUnits ?: creds.displayUnits),
                    onBack = back
                )
            }
        }
    }

    @Composable
    private fun Home(creds: Credentials, onOpen: (Destination) -> Unit) {
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
            alarmsSection = { com.lumenarcanus.dialife.feature.alarms.AlarmsSection(onOpenHandling = { id -> openHandling = id }) },
            onRange = home::setRange,
            onOpenStats = { onOpen(Destination.Stats) },
            onOpenLog = { onOpen(Destination.Log) },
            onOpenData = { onOpen(Destination.Data(ui.rangeHours)) },
            onOpenWebLogin = { onOpen(Destination.WebLogin) },
            onOpenMeal = { tr, q ->
                onOpen(Destination.Meal(editingId = tr?.id, entry = tr, initialTime = q?.from, questionId = q?.id ?: 0L))
            },
            plannedSection = { com.lumenarcanus.dialife.feature.planned.PlannedSection() },
            questionsSection = { onLog -> com.lumenarcanus.dialife.feature.questions.QuestionsSection(onLog) },
            watchSection = { com.lumenarcanus.dialife.feature.questions.WatchSection() },
            waitSection = { com.lumenarcanus.dialife.feature.wait.WaitSection() },
            onOpenSettings = { onOpen(Destination.Settings) },
            onRetry = liveRepository::reconnectNow,
            onSaveTreatment = liveRepository::saveTreatment,
            onDeleteTreatment = liveRepository::deleteTreatment,
            onAnswerQuestion = { id, answer, tr ->
                lifecycleScope.launch {
                    questionAnswers.answer(id, answer, tr)
                        .onFailure { Toast.makeText(this@MainActivity, R.string.q_failed, Toast.LENGTH_LONG).show() }
                }
            },
            onDismissQuestion = { id ->
                lifecycleScope.launch {
                    questionAnswers.dismiss(id).onFailure {
                        Toast.makeText(this@MainActivity, R.string.q_failed, Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDisconnect = vm::disconnect,
            onAnnounceInsulin = insulinIntents::announce,
            foods = foodRepository
        )
    }

    private fun backgroundSetup() = BackgroundSetup(
        notifications = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED,
        battery = !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
        // Android 14+ grants full-screen alarms only to apps the user allowed
        fullScreen = Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    )

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
        if (data.scheme == "dialife" && !vm.holdLink(data.toString())) connectVm.onLink(data.toString())
    }
}
