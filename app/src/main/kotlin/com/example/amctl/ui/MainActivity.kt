package com.example.amctl.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.example.amctl.R
import com.example.amctl.data.model.AppLanguage
import com.example.amctl.data.model.AppThemeMode
import com.example.amctl.data.repository.SettingsRepository
import com.example.amctl.ui.screens.HomeScreen
import com.example.amctl.ui.theme.AmctlTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MainActivityEntryPoint {
        fun settingsRepository(): SettingsRepository
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val entryPoint =
            EntryPointAccessors.fromApplication(
                applicationContext,
                MainActivityEntryPoint::class.java,
            )
        val initialThemeMode = runBlocking {
            entryPoint.settingsRepository().getServerConfig().appThemeMode
        }
        // Switch from launch theme to a concrete light/dark theme before first content frame.
        setTheme(
            if (initialThemeMode == AppThemeMode.DARK) {
                R.style.Theme_Amctl_Dark
            } else {
                R.style.Theme_Amctl_Light
            },
        )
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            val appThemeMode by settingsRepository.serverConfig
                .map { it.appThemeMode }
                .collectAsState(initial = initialThemeMode)

            AmctlTheme(
                darkTheme = appThemeMode == AppThemeMode.DARK,
                dynamicColor = false,
            ) {
                HomeScreen()
            }
        }

        lifecycleScope.launch {
            val config = settingsRepository.getServerConfig()
            val localeTags = when (config.appLanguage) {
                AppLanguage.SYSTEM -> ""
                AppLanguage.CHINESE -> "zh"
                AppLanguage.ENGLISH -> "en"
            }
            val currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            if (currentTags != localeTags) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(localeTags))
            }
        }
    }
}
