package com.example.data.importer

sealed interface ImportState {
    data object Idle : ImportState
    data class Loading(val progressMessage: String) : ImportState
    data class Success(val summary: ImportSummary, val details: String) : ImportState
    data class Error(val message: String) : ImportState
}
