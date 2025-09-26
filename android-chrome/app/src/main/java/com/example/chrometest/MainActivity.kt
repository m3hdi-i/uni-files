
package com.example.chrometest

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.chrometest.ui.theme.AppTheme

const val TAG = "WebViewCheck"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    EnhancedWebView(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EnhancedWebView(modifier: Modifier) {
    val webViewState = remember { mutableStateOf<WebView?>(null) }
    val metricsCollectorState = remember { mutableStateOf<WebMetricsCollector?>(null) }
    val showMetrics = remember { mutableStateOf(false) }
    val metricsData = remember { mutableStateOf<Map<String, Any>>(emptyMap()) }
    val mUrl = "https://www.google.com" // Use a standard URL

    // Check the WebView provider package
    LaunchedEffect(Unit) {
        val webViewPackageInfo = WebView.getCurrentWebViewPackage()
        if (webViewPackageInfo != null) {
            Log.d(TAG, "Provider: ${webViewPackageInfo.packageName}, Version: ${webViewPackageInfo.versionName}")
        } else {
            Log.e(TAG, "Could not determine provider.")
        }
    }

    Column(modifier = modifier) {
        // WebView takes most of the space
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setGeolocationEnabled(true)

                    // Enable debugging for WebView
                    WebView.setWebContentsDebuggingEnabled(true)

                    // Create metrics collector
                    val metricsCollector = WebMetricsCollector(this)
                    metricsCollectorState.value = metricsCollector

                    // Set custom WebViewClient for metrics collection
                    webViewClient = metricsCollector.createWebViewClient()

                    // Set WebChromeClient
                    //webChromeClient = CustomWebChromeClient()

                    val userAgent = settings.userAgentString
                    Log.d(TAG, "Configured User Agent: $userAgent")
                    if (userAgent.contains("Chrome/")) {
                        Log.i(TAG, "User Agent appears Chromium-based.")
                    } else {
                        Log.w(TAG, "User Agent does NOT contain 'Chrome/'.")
                    }

                    webViewState.value = this
                }
            },
            update = { webView ->
                webView.loadUrl(mUrl)
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        // Button to show metrics
        Button(
            onClick = {
                metricsCollectorState.value?.let {
                    metricsData.value = it.getMetricsSummary()
                    showMetrics.value = true
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text("Show Performance Metrics")
        }

        // Display metrics if available
        if (showMetrics.value) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Web Performance Metrics",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                metricsData.value.forEach { (key, value) ->
                    val displayValue = when (value) {
                        is Float -> String.format("%.2f", value)
                        is Double -> String.format("%.2f", value)
                        is Long -> if (key.contains("Time") || key.contains("Paint") || key.contains("Interactive")) "$value ms" else value.toString()
                        else -> value.toString()
                    }

                    Text(
                        "$key: $displayValue",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewState.value?.destroy()
        }
    }
}

/*class CustomWebChromeClient : WebChromeClient() {
    override fun onReceivedTitle(view: WebView?, title: String?) {
        super.onReceivedTitle(view, title)
        Log.d(TAG, "Page Title: $title")
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        Log.d(TAG, "Loading Progress: $newProgress%")
    }

    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
        consoleMessage?.let {
            Log.d(TAG, "Console: ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
        }
        return super.onConsoleMessage(consoleMessage)
    }
}*/
