package com.hnpage.facecheck.viewmodels

import androidx.lifecycle.ViewModel
import com.hnpage.facecheck.AppState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor() : ViewModel() {
    private val _state = MutableStateFlow(AppState())

    val state: StateFlow<AppState> = _state.asStateFlow()

    fun updateData(newData: String) {
        _state.value = _state.value.copy(data = newData)
    }
}