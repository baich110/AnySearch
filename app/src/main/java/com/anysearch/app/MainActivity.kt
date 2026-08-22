package com.anysearch.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.anysearch.app.data.AppLogger
import com.anysearch.app.ui.AnySearchApp
import com.anysearch.app.ui.AnySearchTheme
import com.anysearch.app.ui.SearchViewModel
import com.anysearch.app.ui.SearchViewModelFactory

class MainActivity : ComponentActivity() {

    private val viewModel: SearchViewModel by viewModels {
        SearchViewModelFactory(applicationContext)
    }

    // Android 13+ 通知权限
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            // 用户拒绝，不强制，但记录
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 最先初始化日志系统（记录崩溃、设备信息）
        AppLogger.init(applicationContext)
        AppLogger.i("MainActivity", "onCreate，savedInstanceState=${savedInstanceState != null}")

        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .build()
        }

        enableEdgeToEdge()
        setContent {
            AnySearchTheme(dynamicColor = true) {
                Surface {
                    viewModel.loadVerticals()
                    AnySearchApp(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppLogger.i("MainActivity", "onResume")
        window.decorView.announceForAccessibility("搜索应用已打开")

        // 请求通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_DENIED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        AppLogger.i("MainActivity", "onPause")
    }

    override fun onStop() {
        super.onStop()
        AppLogger.i("MainActivity", "onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.i("MainActivity", "onDestroy（正常退出）")
        // 标记本次正常退出，删除崩溃标记
        AppLogger.markNormalExit()
    }
}
