package com.example.blindpeople.ui

sealed class AppUiState {
    data object Idle : AppUiState()
    data class Running(
        val status: String,
        val audioEnabled: Boolean,
    ) : AppUiState()

    data class Error(
        val message: String,
        val recoverable: Boolean = true,
    ) : AppUiState()
}

