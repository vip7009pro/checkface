package com.hnpage.facecheck.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.hnpage.facecheck.viewmodels.AppViewModel

@Composable
fun MyScreen(viewModel: AppViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = state.data,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.pointerInput(Unit) {
                // Simulate a click to update data
                viewModel.updateData("Hello, Đại ca!")
            }
        )
    }
    Text(
        text = state.data,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.pointerInput(Unit) {
            // Simulate a click to update data
            viewModel.updateData("Hello, Đại ca!")
        }
    )
}