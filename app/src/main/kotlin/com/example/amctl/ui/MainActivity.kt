package com.example.amctl.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.example.amctl.data.model.AppLanguage
import com.example.amctl.data.model.AppThemeMode
import com.example.amctl.data.repository.SettingsRepository
import com.example.amctl.ui.screens.HomeScreen
import com.example.amctl.ui.theme.AmctlTheme
import com.example.amctl.ui.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            val serverConfig by viewModel.serverConfig.collectAsState()

            AmctlTheme(
                darkTheme = serverConfig.appThemeMode == AppThemeMode.DARK,
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
