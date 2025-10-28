package com.hnpage.facecheck

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi

object AppDestinations {
    const val REGISTRATION_ROUTE = "registration"
    const val RECOGNITION_ROUTE = "recognition"
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = AppDestinations.RECOGNITION_ROUTE) {
        composable(AppDestinations.REGISTRATION_ROUTE) {
            // Sẽ thêm màn hình đăng ký ở đây
            RegistrationScreen(navController)
        }
        composable(AppDestinations.RECOGNITION_ROUTE) {
            // Sẽ thêm màn hình nhận diện ở đây
             RecognitionScreen(navController)
        }
    }
}
