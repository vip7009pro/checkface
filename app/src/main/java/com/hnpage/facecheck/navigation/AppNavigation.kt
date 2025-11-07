package com.hnpage.facecheck.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.hnpage.facecheck.screens.MyScreen
import com.hnpage.facecheck.screens.RecognitionScreen
import com.hnpage.facecheck.screens.RegistrationScreen

val LocalNavController = staticCompositionLocalOf<NavHostController> {
    error("No NavController provided")
}
object AppDestinations {
    const val REGISTRATION_ROUTE = "registration"
    const val RECOGNITION_ROUTE = "recognition"
    const val MYSCREEN_ROUTE = "myscreen"
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    CompositionLocalProvider(LocalNavController provides navController) {
        NavHost(
            navController = navController,
            startDestination = AppDestinations.RECOGNITION_ROUTE
        ) {
            composable(AppDestinations.REGISTRATION_ROUTE) {
                // Sẽ thêm màn hình đăng ký ở đây
                RegistrationScreen()
            }
            composable(AppDestinations.RECOGNITION_ROUTE) {
                // Sẽ thêm màn hình nhận diện ở đây
                RecognitionScreen()
            }
            composable(AppDestinations.MYSCREEN_ROUTE) {
                MyScreen()
            }
        }
    }
}
