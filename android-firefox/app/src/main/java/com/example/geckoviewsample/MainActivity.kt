package com.example.geckoviewsample

import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import org.json.JSONException
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtension.MessageDelegate
import org.mozilla.geckoview.WebExtension.MessageSender
import java.text.SimpleDateFormat
import java.util.*

// Data class to hold web metrics
data class WebMetricsData(
    val timestamp: String,
    val url: String,
    val metrics: Map<String, Any>
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            GeckoViewScreen()
        }
    }
}

const val TAG = "GeckoViewSample"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeckoViewScreen() {
    val mContext = LocalContext.current
    val extensionLocation = "resource://android/assets/messaging/"
    val mainHandler = android.os.Handler(Looper.getMainLooper())

    // State for storing metrics data
    var metricsDataList by remember { mutableStateOf<List<WebMetricsData>>(emptyList()) }
    var showMetricsDialog by remember { mutableStateOf(false) }

    // Initialize GeckoView settings
    val settings = GeckoRuntimeSettings.Builder()
        .consoleOutput(true)
        .debugLogging(true)
        .javaScriptEnabled(true)
        .build()

    val runtime = remember {
        GeckoRuntime.create(mContext, settings)
    }

    val session = remember {
        GeckoSession().apply {
            open(runtime)
            loadUri("https://google.com")
        }
    }

    val messageDelegate: MessageDelegate = object : MessageDelegate {
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: MessageSender
        ): GeckoResult<Any>? {
            if (message is JSONObject) {
                try {
                    val type = message.getString("type")
                    val value = message.getString("value")

                    Log.v(TAG, "=== Received Message ===")
                    Log.v(TAG, "Type: $type")
                    Log.v(TAG, "Value: $value")

                    when (type) {
                        "WebMetrics" -> {
                            // Parse the metrics JSON
                            val metricsJson = JSONObject(value)
                            val timestamp = metricsJson.optString("timestamp", "")
                            val url = metricsJson.optString("url", "")

                            // Extract all metrics into a map
                            val metricsMap = mutableMapOf<String, Any>()

                            // Parse loading performance metrics
                            val loadingPerf = metricsJson.optJSONObject("loadingPerformance")
                            loadingPerf?.let {
                                Log.v(TAG, "=== Loading Performance Metrics ===")
                                it.keys().forEach { key ->
                                    val metricValue = it.get(key)
                                    metricsMap["Loading: $key"] = metricValue
                                    Log.v(TAG, "$key: $metricValue")
                                }
                            }

                            // Parse core web vitals
                            val coreWebVitals = metricsJson.optJSONObject("coreWebVitals")
                            coreWebVitals?.let {
                                Log.v(TAG, "=== Core Web Vitals ===")
                                it.keys().forEach { key ->
                                    val metricValue = it.get(key)
                                    metricsMap["Vitals: $key"] = metricValue
                                    Log.v(TAG, "$key: $metricValue")
                                }
                            }

                            // Parse resource metrics
                            val resourceMetrics = metricsJson.optJSONObject("resourceMetrics")
                            resourceMetrics?.let {
                                Log.v(TAG, "=== Resource Metrics ===")
                                it.keys().forEach { key ->
                                    if (key != "slowestResources") {
                                        val metricValue = it.get(key)
                                        metricsMap["Resource: $key"] = metricValue
                                        Log.v(TAG, "$key: $metricValue")
                                    }
                                }
                            }

                            // Parse additional metrics
                            val additionalMetrics = metricsJson.optJSONObject("additionalMetrics")
                            additionalMetrics?.let {
                                Log.v(TAG, "=== Additional Metrics ===")

                                // Memory usage
                                val memoryUsage = it.optJSONObject("memoryUsage")
                                memoryUsage?.let { mem ->
                                    val usedMB = mem.optLong("usedJSHeapSize", 0) / 1024 / 1024
                                    val totalMB = mem.optLong("totalJSHeapSize", 0) / 1024 / 1024
                                    metricsMap["Memory: Used Heap"] = "${usedMB}MB"
                                    metricsMap["Memory: Total Heap"] = "${totalMB}MB"
                                    Log.v(TAG, "Memory Used: ${usedMB}MB, Total: ${totalMB}MB")
                                }

                                // Network quality
                                val networkQuality = it.optJSONObject("networkQuality")
                                networkQuality?.let { net ->
                                    val effectiveType = net.optString("effectiveType", "unknown")
                                    val downlink = net.optDouble("downlink", 0.0)
                                    val rtt = net.optInt("rtt", 0)
                                    metricsMap["Network: Type"] = effectiveType
                                    metricsMap["Network: Downlink"] = "${downlink}Mbps"
                                    metricsMap["Network: RTT"] = "${rtt}ms"
                                    Log.v(TAG, "Network: $effectiveType, Downlink: ${downlink}Mbps, RTT: ${rtt}ms")
                                }

                                // Speed Index and CPU
                                val speedIndex = it.optInt("speedIndex", 0)
                                val cpuUtilization = it.optInt("cpuUtilization", 0)
                                metricsMap["Performance: Speed Index"] = speedIndex
                                metricsMap["Performance: CPU Utilization"] = "$cpuUtilization%"
                                Log.v(TAG, "Speed Index: $speedIndex, CPU: $cpuUtilization%")
                            }

                            // Add to metrics list on main thread
                            mainHandler.post {
                                val newMetrics = WebMetricsData(timestamp, url, metricsMap)
                                metricsDataList = metricsDataList + newMetrics
                            }
                        }
                        "StartUp" -> {
                            Log.v(TAG, "Extension started: $value")
                        }
                        else -> {
                            Log.v(TAG, "Unknown message type: $type")
                        }
                    }
                } catch (ex: JSONException) {
                    Log.e(TAG, "Error processing message", ex)
                }
            }
            return null
        }
    }

    runtime.webExtensionController
        .ensureBuiltIn(extensionLocation, "messaging@example.com")
        .accept(
            { extension: WebExtension? ->
                if (extension != null) {
                    Log.d(TAG, "Extension registered successfully")
                    mainHandler.post {
                        session.webExtensionController
                            .setMessageDelegate(extension, messageDelegate, "browser")
                    }
                }
            },
            { e: Throwable? ->
                Log.e(TAG, "Error registering extension", e)
            }
        )

    Box(modifier = Modifier.fillMaxSize()) {
        // GeckoView
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                GeckoView(context).apply {
                    setSession(session)
                }
            }
        )

        // FAB Button
        FloatingActionButton(
            onClick = {
                if (metricsDataList.isNotEmpty()) {
                    showMetricsDialog = true
                } else {
                    Toast.makeText(
                        mContext,
                        "No metrics data collected yet. Please wait for page to load.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Text("📊", fontSize = 24.sp)
        }
    }

    // Metrics Dialog
    if (showMetricsDialog) {
        MetricsDialog(
            metricsDataList = metricsDataList,
            onDismiss = { showMetricsDialog = false }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            session.close()
            runtime.shutdown()
        }
    }
}

@Composable
fun MetricsDialog(
    metricsDataList: List<WebMetricsData>,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Web Metrics",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 8.dp))

                // Metrics List
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(metricsDataList) { metricsData ->
                        MetricsCard(metricsData)
                    }
                }
            }
        }
    }
}

@Composable
fun MetricsCard(metricsData: WebMetricsData) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // URL and Timestamp
            Text(
                text = metricsData.url.take(50) + if (metricsData.url.length > 50) "..." else "",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = formatTimestamp(metricsData.timestamp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Metrics Grid
            metricsData.metrics.forEach { (key, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = key,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatMetricValue(value),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = getMetricColor(key, value)
                    )
                }
            }
        }
    }
}

fun formatTimestamp(timestamp: String): String {
    return try {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        val outputFormat = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
        val date = inputFormat.parse(timestamp.substringBefore("."))
        outputFormat.format(date ?: Date())
    } catch (e: Exception) {
        timestamp
    }
}

fun formatMetricValue(value: Any): String {
    return when (value) {
        is Number -> {
            val num = value.toDouble()
            when {
                num >= 1000000 -> String.format("%.1fM", num / 1000000)
                num >= 1000 -> String.format("%.1fK", num / 1000)
                num % 1 == 0.0 -> num.toInt().toString()
                else -> String.format("%.2f", num)
            }
        }
        is String -> value
        null -> "N/A"
        else -> value.toString()
    }
}

@Composable
fun getMetricColor(key: String, value: Any): Color {
    // Color code metrics based on performance thresholds
    return when {
        key.contains("FCP", ignoreCase = true) ||
                key.contains("LCP", ignoreCase = true) ||
                key.contains("TTFB", ignoreCase = true) -> {
            val numValue = (value as? Number)?.toDouble() ?: 0.0
            when {
                numValue < 1000 -> Color(0xFF4CAF50) // Green - Good
                numValue < 3000 -> Color(0xFFFF9800) // Orange - Needs Improvement
                else -> Color(0xFFF44336) // Red - Poor
            }
        }
        key.contains("CLS", ignoreCase = true) -> {
            val numValue = (value as? Number)?.toDouble() ?: 0.0
            when {
                numValue < 0.1 -> Color(0xFF4CAF50) // Green - Good
                numValue < 0.25 -> Color(0xFFFF9800) // Orange - Needs Improvement
                else -> Color(0xFFF44336) // Red - Poor
            }
        }
        key.contains("CPU", ignoreCase = true) -> {
            val numValue = (value as? Number)?.toDouble() ?: 0.0
            when {
                numValue < 50 -> Color(0xFF4CAF50) // Green - Good
                numValue < 80 -> Color(0xFFFF9800) // Orange - Moderate
                else -> Color(0xFFF44336) // Red - High
            }
        }
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}