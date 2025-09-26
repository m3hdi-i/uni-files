package com.example.chrometest

import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Utility class to collect and process web performance metrics from WebView
 */
class WebMetricsCollector(private val webView: WebView) {
    
    private val startTime = SystemClock.elapsedRealtime()
    private val requestStartTimes = mutableMapOf<String, Long>()
    private val requestCount = AtomicInteger(0)
    private val totalBytesReceived = AtomicLong(0)
    private val resourceLoadTimes = mutableListOf<Long>()
    private val errorCount = AtomicInteger(0)
    
    private var firstByteTime: Long = 0
    private var pageLoadTime: Long = 0
    private var firstPaintTime: Long = 0
    private var firstContentfulPaintTime: Long = 0
    private var largestContentfulPaintTime: Long = 0
    private var timeToInteractiveTime: Long = 0
    private var cumulativeLayoutShift: Double = 0.0
    private var totalBlockingTime: Long = 0
    private var firstInputDelay: Long = 0
    private var interactionToNextPaint: Long = 0
    private var dnsLookupTime: Long = 0
    private var tcpConnectionTime: Long = 0
    private var tlsNegotiationTime: Long = 0
    
    init {
        setupJavascriptInterface()
    }
    
    /**
     * Sets up the JavaScript interface to receive metrics from the web page
     */
    private fun setupJavascriptInterface() {
        webView.addJavascriptInterface(object : Any() {
            @JavascriptInterface
            fun reportMetric(metricName: String, value: String) {
                when (metricName) {
                    "FCP" -> firstContentfulPaintTime = value.toLongOrNull() ?: 0
                    "LCP" -> largestContentfulPaintTime = value.toLongOrNull() ?: 0
                    "TTI" -> timeToInteractiveTime = value.toLongOrNull() ?: 0
                    "CLS" -> cumulativeLayoutShift = value.toDoubleOrNull() ?: 0.0
                    "TBT" -> totalBlockingTime = value.toLongOrNull() ?: 0
                    "FID" -> firstInputDelay = value.toLongOrNull() ?: 0
                    "INP" -> interactionToNextPaint = value.toLongOrNull() ?: 0
                    "TTFP" -> firstPaintTime = value.toLongOrNull() ?: 0
                    "DNS" -> dnsLookupTime = value.toLongOrNull() ?: 0
                    "TCP" -> tcpConnectionTime = value.toLongOrNull() ?: 0
                    "TLS" -> tlsNegotiationTime = value.toLongOrNull() ?: 0
                }
                Log.d(TAG, "Received metric: $metricName = $value")
            }
            
            @JavascriptInterface
            fun reportPerformanceTimings(jsonTimings: String) {
                try {
                    val timings = JSONObject(jsonTimings)
                    dnsLookupTime = (timings.optLong("domainLookupEnd") - timings.optLong("domainLookupStart")).coerceAtLeast(0)
                    tcpConnectionTime = (timings.optLong("connectEnd") - timings.optLong("connectStart")).coerceAtLeast(0)
                    firstByteTime = (timings.optLong("responseStart") - timings.optLong("requestStart")).coerceAtLeast(0)
                    
                    Log.d(TAG, "DNS Lookup: $dnsLookupTime ms")
                    Log.d(TAG, "TCP Connection: $tcpConnectionTime ms")
                    Log.d(TAG, "TTFB: $firstByteTime ms")
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing performance timings: ${e.message}")
                }
            }
            
            @JavascriptInterface
            fun reportResourceTiming(duration: String) {
                val durationMs = duration.toLongOrNull() ?: 0
                if (durationMs > 0) {
                    resourceLoadTimes.add(durationMs)
                }
            }
            
            @JavascriptInterface
            fun reportMemoryInfo(usedJSHeapSize: String, totalJSHeapSize: String, jsHeapSizeLimit: String) {
                Log.d(TAG, "Memory Usage - Used: $usedJSHeapSize, Total: $totalJSHeapSize, Limit: $jsHeapSizeLimit")
            }
        }, "MetricsCollector")
    }
    
    /**
     * Creates a custom WebViewClient to track network-related metrics
     */
    fun createWebViewClient(): WebViewClient {
        return object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Reset counters for new page load
                requestCount.set(0)
                totalBytesReceived.set(0)
                resourceLoadTimes.clear()
                errorCount.set(0)
                
                // Inject performance observer scripts
                injectPerformanceMonitoringScripts(view)
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageLoadTime = SystemClock.elapsedRealtime() - startTime
                
                // Calculate mean resource load time
                val meanResourceLoadTime = if (resourceLoadTimes.isNotEmpty()) {
                    resourceLoadTimes.sum() / resourceLoadTimes.size
                } else 0
                
                // Collect final metrics
                Log.d(TAG, "Page Load Time: $pageLoadTime ms")
                Log.d(TAG, "Request Count: ${requestCount.get()}")
                Log.d(TAG, "Total Data Transferred: ${totalBytesReceived.get()} bytes")
                Log.d(TAG, "Mean Resource Load Time: $meanResourceLoadTime ms")
                Log.d(TAG, "Error Rate: ${if (requestCount.get() > 0) (errorCount.get().toFloat() / requestCount.get()) * 100 else 0}%")
                
                // Execute JavaScript to collect remaining metrics
                executeMetricsCollectionScripts(view)
            }
            
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                request?.url?.toString()?.let { url ->
                    requestStartTimes[url] = SystemClock.elapsedRealtime()
                    requestCount.incrementAndGet()
                }
                return super.shouldInterceptRequest(view, request)
            }
            
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                errorCount.incrementAndGet()
            }
            
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                super.onReceivedError(view, request, error)
                errorCount.incrementAndGet()
            }
            
            override fun onLoadResource(view: WebView?, url: String?) {
                super.onLoadResource(view, url)
                url?.let {
                    val startTime = requestStartTimes[it] ?: return
                    val loadTime = SystemClock.elapsedRealtime() - startTime
                    resourceLoadTimes.add(loadTime)
                    
                    // Estimate data size based on resource type (very rough estimation)
                    val estimatedSize = when {
                        it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) -> 200 * 1024L // 200KB for images
                        it.endsWith(".png", true) -> 100 * 1024L
                        it.endsWith(".css", true) -> 20 * 1024L
                        it.endsWith(".js", true) -> 50 * 1024L
                        else -> 10 * 1024L // Default estimation
                    }
                    totalBytesReceived.addAndGet(estimatedSize)
                    
                    requestStartTimes.remove(it)
                }
            }
        }
    }
    
    /**
     * Injects JavaScript to monitor performance metrics
     */
    private fun injectPerformanceMonitoringScripts(webView: WebView?) {
        webView?.evaluateJavascript("""
            // Performance Observer for paint metrics
            try {
                const paintObserver = new PerformanceObserver((list) => {
                    for (const entry of list.getEntries()) {
                        if (entry.name === 'first-contentful-paint') {
                            window.MetricsCollector.reportMetric('FCP', entry.startTime.toString());
                        }
                        if (entry.name === 'first-paint') {
                            window.MetricsCollector.reportMetric('TTFP', entry.startTime.toString());
                        }
                    }
                });
                paintObserver.observe({type: 'paint', buffered: true});
                
                // LCP Observer
                const lcpObserver = new PerformanceObserver((list) => {
                    const entries = list.getEntries();
                    const lastEntry = entries[entries.length - 1];
                    window.MetricsCollector.reportMetric('LCP', lastEntry.startTime.toString());
                });
                lcpObserver.observe({type: 'largest-contentful-paint', buffered: true});
                
                // Layout Shift Observer for CLS
                let cumulativeLayoutShift = 0;
                const layoutShiftObserver = new PerformanceObserver((list) => {
                    for (const entry of list.getEntries()) {
                        if (!entry.hadRecentInput) {
                            cumulativeLayoutShift += entry.value;
                            window.MetricsCollector.reportMetric('CLS', cumulativeLayoutShift.toString());
                        }
                    }
                });
                layoutShiftObserver.observe({type: 'layout-shift', buffered: true});
                
                // Resource timing for individual resources
                const resourceObserver = new PerformanceObserver((list) => {
                    for (const entry of list.getEntries()) {
                        window.MetricsCollector.reportResourceTiming(entry.duration.toString());
                    }
                });
                resourceObserver.observe({type: 'resource', buffered: true});
                
                // First Input Delay
                const fidObserver = new PerformanceObserver((list) => {
                    for (const entry of list.getEntries()) {
                        window.MetricsCollector.reportMetric('FID', entry.processingStart - entry.startTime);
                    }
                });
                fidObserver.observe({type: 'first-input', buffered: true});
                
                // Interaction to Next Paint
                const inpObserver = new PerformanceObserver((list) => {
                    for (const entry of list.getEntries()) {
                        window.MetricsCollector.reportMetric('INP', entry.duration.toString());
                    }
                });
                inpObserver.observe({type: 'event', durationThreshold: 16});
                
                // Navigation and Resource Timing
                window.addEventListener('load', () => {
                    setTimeout(() => {
                        const navEntry = performance.getEntriesByType('navigation')[0];
                        if (navEntry) {
                            window.MetricsCollector.reportPerformanceTimings(JSON.stringify({
                                domainLookupStart: navEntry.domainLookupStart,
                                domainLookupEnd: navEntry.domainLookupEnd,
                                connectStart: navEntry.connectStart,
                                connectEnd: navEntry.connectEnd,
                                requestStart: navEntry.requestStart,
                                responseStart: navEntry.responseStart
                            }));
                            
                            // Total Blocking Time approximation
                            const tbt = navEntry.domInteractive - navEntry.responseEnd;
                            window.MetricsCollector.reportMetric('TBT', tbt.toString());
                            
                            // Time to Interactive approximation
                            const tti = navEntry.domInteractive;
                            window.MetricsCollector.reportMetric('TTI', tti.toString());
                        }
                        
                        // Memory usage if available
                        if (performance.memory) {
                            window.MetricsCollector.reportMemoryInfo(
                                performance.memory.usedJSHeapSize.toString(),
                                performance.memory.totalJSHeapSize.toString(),
                                performance.memory.jsHeapSizeLimit.toString()
                            );
                        }
                    }, 0);
                });
            } catch (e) {
                console.error('Error setting up performance observers:', e);
            }
        """.trimIndent(), null)
    }
    
    /**
     * Executes additional scripts to collect metrics after page load
     */
    private fun executeMetricsCollectionScripts(webView: WebView?) {
        webView?.evaluateJavascript("""
            // CPU Utilization estimation (this is a rough approximation)
            let startTime = performance.now();
            let iterations = 0;
            
            // Run a CPU-intensive task
            while (performance.now() - startTime < 50) {
                iterations++;
            }
            
            // Calculate operations per millisecond as a CPU usage indicator
            const opsPerMs = iterations / 50;
            console.log('CPU benchmark: ' + opsPerMs + ' operations per ms');
            
            // Network quality estimation
            const connection = navigator.connection || navigator.mozConnection || 
                               navigator.webkitConnection || {};
            if (connection) {
                console.log('Network type: ' + (connection.effectiveType || 'unknown'));
                console.log('Downlink: ' + (connection.downlink || 'unknown') + ' Mbps');
                console.log('RTT: ' + (connection.rtt || 'unknown') + ' ms');
            }
            
            // Calculate Speed Index approximation
            const speedIndex = (() => {
                const navTiming = performance.getEntriesByType('navigation')[0];
                const paintTimings = performance.getEntriesByType('paint');
                
                let firstPaint = 0;
                for (const paint of paintTimings) {
                    if (paint.name === 'first-paint') {
                        firstPaint = paint.startTime;
                        break;
                    }
                }
                
                if (navTiming && firstPaint) {
                    // This is a very simplified approximation
                    return (firstPaint + navTiming.domContentLoadedEventEnd) / 2;
                }
                return 0;
            })();
            
            console.log('Speed Index approximation: ' + speedIndex + ' ms');
        """.trimIndent(), null)
    }
    
    /**
     * Returns a summary of all collected metrics
     */
    fun getMetricsSummary(): Map<String, Any> {
        return mapOf(
            "Page Load Time" to pageLoadTime,
            "DNS Lookup Time" to dnsLookupTime,
            "TCP Connection Time" to tcpConnectionTime,
            "Time to First Byte" to firstByteTime,
            "First Paint Time" to firstPaintTime,
            "First Contentful Paint" to firstContentfulPaintTime,
            "Largest Contentful Paint" to largestContentfulPaintTime,
            "Time to Interactive" to timeToInteractiveTime,
            "Total Blocking Time" to totalBlockingTime,
            "Cumulative Layout Shift" to cumulativeLayoutShift,
            "First Input Delay" to firstInputDelay,
            "Interaction to Next Paint" to interactionToNextPaint,
            "Request Count" to requestCount.get(),
            "Data Transferred" to totalBytesReceived.get(),
            "Mean Resource Load Time" to if (resourceLoadTimes.isNotEmpty()) resourceLoadTimes.sum() / resourceLoadTimes.size else 0,
            "Error Rate" to if (requestCount.get() > 0) (errorCount.get().toFloat() / requestCount.get()) * 100 else 0f
        )
    }
}