package com.memohai.autofish.data.model

enum class AppLanguage {
    SYSTEM,
    ENGLISH,
    CHINESE,
}

enum class AppThemeMode {
    LIGHT,
    DARK,
}

data class ServerConfig(
    val bindingAddress: BindingAddress = BindingAddress.ALL_INTERFACES,
    val autoStartOnBoot: Boolean = false,
    val servicePort: Int = DEFAULT_PORT,
    val serviceBearerToken: String = "",
    val serviceOverlayVisible: Boolean = false,
    val serviceRefVisible: Boolean = false,
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val appThemeMode: AppThemeMode = AppThemeMode.LIGHT,
) {
    companion object {
        const val DEFAULT_PORT = 8081
        const val MIN_PORT = 1
        const val MAX_PORT = 65535
    }
}
