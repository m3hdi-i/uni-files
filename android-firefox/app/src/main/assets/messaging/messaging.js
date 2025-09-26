console.log("Extension content script loaded");

// Send startup message
browser.runtime.sendNativeMessage("browser", {
    type: "StartUp",
    value: "Extension started with metrics collection!"
});

// Metrics collection class
class WebMetricsCollector {
    constructor() {
        this.metrics = {};
        this.resourceTimings = [];
        this.startTime = performance.now();
        this.layoutShifts = [];
        this.firstInputDelay = null;
        this.interactions = [];
        this.longTasks = [];

        // Initialize observers
        this.initializeObservers();
    }

    initializeObservers() {
        // Observe Layout Shifts for CLS
        if ('PerformanceObserver' in window) {
            try {
                // Layout Shift Observer for CLS
                const clsObserver = new PerformanceObserver((list) => {
                    for (const entry of list.getEntries()) {
                        if (!entry.hadRecentInput) {
                            this.layoutShifts.push({
                                value: entry.value,
                                startTime: entry.startTime
                            });
                        }
                    }
                });
                clsObserver.observe({ type: 'layout-shift', buffered: true });

                // LCP Observer
                const lcpObserver = new PerformanceObserver((list) => {
                    const entries = list.getEntries();
                    if (entries.length > 0) {
                        this.metrics.lcp = entries[entries.length - 1].startTime;
                    }
                });
                lcpObserver.observe({ type: 'largest-contentful-paint', buffered: true });

                // FID Observer
                const fidObserver = new PerformanceObserver((list) => {
                    const entries = list.getEntries();
                    if (entries.length > 0 && !this.firstInputDelay) {
                        const entry = entries[0];
                        this.firstInputDelay = entry.processingStart - entry.startTime;
                    }
                });
                fidObserver.observe({ type: 'first-input', buffered: true });

                // Long Tasks Observer for TBT
                const longTaskObserver = new PerformanceObserver((list) => {
                    for (const entry of list.getEntries()) {
                        this.longTasks.push({
                            duration: entry.duration,
                            startTime: entry.startTime
                        });
                    }
                });
                longTaskObserver.observe({ type: 'longtask', buffered: true });

            } catch (e) {
                console.log("Performance Observer not fully supported:", e);
            }
        }

        // Fallback: Monitor user interactions manually
        this.setupInteractionMonitoring();
    }

    setupInteractionMonitoring() {
        let interactionStart = 0;

        const handleInteractionStart = (e) => {
            interactionStart = performance.now();
        };

        const handleInteractionEnd = (e) => {
            if (interactionStart > 0) {
                const delay = performance.now() - interactionStart;
                this.interactions.push(delay);

                // Set FID if this is the first interaction
                if (this.firstInputDelay === null) {
                    this.firstInputDelay = delay;
                }

                interactionStart = 0;
            }
        };

        // Monitor various interaction types
        ['click', 'keydown', 'touchstart'].forEach(eventType => {
            document.addEventListener(eventType, handleInteractionStart, {
                once: false,
                passive: true,
                capture: true
            });
        });

        ['click', 'keyup', 'touchend'].forEach(eventType => {
            document.addEventListener(eventType, handleInteractionEnd, {
                once: false,
                passive: true,
                capture: true
            });
        });
    }

    collectAllMetrics() {
        const metrics = {
            timestamp: new Date().toISOString(),
            url: window.location.href,
            loadingPerformance: this.getLoadingPerformanceMetrics(),
            coreWebVitals: this.getCoreWebVitals(),
            resourceMetrics: this.getResourceMetrics(),
            additionalMetrics: this.getAdditionalMetrics()
        };
        return metrics;
    }

    getLoadingPerformanceMetrics() {
        const metrics = {};

        if (window.performance && window.performance.timing) {
            const timing = window.performance.timing;
            const navigationStart = timing.navigationStart || 0;

            // Page Load Time
            if (timing.loadEventEnd && timing.navigationStart) {
                metrics.pageLoadTime = timing.loadEventEnd - timing.navigationStart;
            }

            // DNS Lookup Time
            if (timing.domainLookupEnd && timing.domainLookupStart) {
                metrics.dnsLookupTime = timing.domainLookupEnd - timing.domainLookupStart;
            }

            // TCP Connection Time
            if (timing.connectEnd && timing.connectStart) {
                metrics.tcpConnectionTime = timing.connectEnd - timing.connectStart;
            }

            // TLS Negotiation Time (if HTTPS)
            if (timing.secureConnectionStart && timing.secureConnectionStart > 0) {
                metrics.tlsNegotiationTime = timing.connectEnd - timing.secureConnectionStart;
            }

            // Time to First Byte (TTFB)
            if (timing.responseStart && timing.requestStart) {
                metrics.ttfb = timing.responseStart - timing.requestStart;
            }

            // First Paint Time
            if (timing.domContentLoadedEventStart) {
                metrics.firstPaintTime = timing.domContentLoadedEventStart - navigationStart;
            }

            // Time to Interactive
            if (timing.domComplete) {
                metrics.timeToInteractive = timing.domComplete - navigationStart;
            }
        }

        // Use Navigation Timing API Level 2 if available
        if (window.performance && window.performance.getEntriesByType) {
            const navEntries = window.performance.getEntriesByType('navigation');
            if (navEntries.length > 0) {
                const navTiming = navEntries[0];

                if (navTiming.loadEventEnd) {
                    metrics.pageLoadTime = Math.round(navTiming.loadEventEnd);
                }
                if (navTiming.domainLookupEnd && navTiming.domainLookupStart) {
                    metrics.dnsLookupTime = Math.round(navTiming.domainLookupEnd - navTiming.domainLookupStart);
                }
                if (navTiming.connectEnd && navTiming.connectStart) {
                    metrics.tcpConnectionTime = Math.round(navTiming.connectEnd - navTiming.connectStart);
                }
                if (navTiming.responseStart && navTiming.requestStart) {
                    metrics.ttfb = Math.round(navTiming.responseStart - navTiming.requestStart);
                }
            }
        }

        // Calculate Total Blocking Time
        metrics.totalBlockingTime = this.calculateTotalBlockingTime();

        return metrics;
    }

    getCoreWebVitals() {
        const vitals = {};

        // First Contentful Paint (FCP)
        if (window.performance && window.performance.getEntriesByType) {
            const paintEntries = window.performance.getEntriesByType('paint');
            const fcpEntry = paintEntries.find(entry => entry.name === 'first-contentful-paint');
            if (fcpEntry) {
                vitals.fcp = Math.round(fcpEntry.startTime);
            } else {
                // Fallback: estimate from domContentLoaded
                const timing = window.performance.timing;
                if (timing && timing.domContentLoadedEventStart && timing.navigationStart) {
                    vitals.fcp = timing.domContentLoadedEventStart - timing.navigationStart;
                }
            }
        }

        // Largest Contentful Paint (LCP)
        vitals.lcp = this.getLCP();

        // Cumulative Layout Shift (CLS)
        vitals.cls = this.calculateCLS();

        // First Input Delay (FID)
        vitals.fid = this.firstInputDelay !== null ? Math.round(this.firstInputDelay) : null;

        // Interaction to Next Paint (INP)
        vitals.inp = this.calculateINP();

        return vitals;
    }

    getResourceMetrics() {
        const metrics = {};

        if (window.performance && window.performance.getEntriesByType) {
            const resourceEntries = window.performance.getEntriesByType('resource');

            metrics.requestCount = resourceEntries.length;

            let totalSize = 0;
            let totalDuration = 0;
            let errorCount = 0;
            const resourceTimings = [];

            resourceEntries.forEach(entry => {
                const size = entry.transferSize || entry.encodedBodySize || 0;
                totalSize += size;

                const duration = entry.duration || 0;
                totalDuration += duration;

                // Better error detection
                if (entry.responseEnd === 0 || (duration === 0 && entry.startTime > 0)) {
                    errorCount++;
                }

                resourceTimings.push({
                    name: entry.name.substring(0, 100),
                    type: entry.initiatorType || 'unknown',
                    duration: Math.round(duration),
                    size: size
                });
            });

            metrics.dataTransferred = totalSize;
            metrics.meanResourceLoadTime = resourceEntries.length > 0
                ? Math.round(totalDuration / resourceEntries.length)
                : 0;
            metrics.errorRate = resourceEntries.length > 0
                ? Math.round((errorCount / resourceEntries.length) * 100)
                : 0;

            // Include top 10 slowest resources
            metrics.slowestResources = resourceTimings
                .sort((a, b) => b.duration - a.duration)
                .slice(0, 10);
        }

        return metrics;
    }

    getAdditionalMetrics() {
        const metrics = {};

        // Memory Usage
        if (performance.memory) {
            metrics.memoryUsage = {
                usedJSHeapSize: performance.memory.usedJSHeapSize,
                totalJSHeapSize: performance.memory.totalJSHeapSize,
                jsHeapSizeLimit: performance.memory.jsHeapSizeLimit
            };
        }

        // Network Quality
        if (navigator.connection) {
            metrics.networkQuality = {
                effectiveType: navigator.connection.effectiveType || 'unknown',
                downlink: navigator.connection.downlink || 0,
                rtt: navigator.connection.rtt || 0,
                saveData: navigator.connection.saveData || false
            };
        }

        // Speed Index
        metrics.speedIndex = this.calculateSpeedIndex();

        // CPU Utilization (more realistic estimation)
        metrics.cpuUtilization = this.estimateCPUUtilization();

        return metrics;
    }

    // Helper methods for metrics calculation

    calculateTotalBlockingTime() {
        let tbt = 0;

        // Calculate from long tasks
        const fcp = this.metrics.fcp || 0;
        const tti = this.metrics.tti || performance.timing.domInteractive - performance.timing.navigationStart;

        this.longTasks.forEach(task => {
            if (task.startTime > fcp && task.startTime < tti && task.duration > 50) {
                tbt += (task.duration - 50);
            }
        });

        // Fallback: estimate from main thread blocking
        if (tbt === 0 && window.performance && window.performance.timing) {
            const timing = window.performance.timing;
            const parseTime = timing.domInteractive - timing.domLoading;
            if (parseTime > 50) {
                tbt = Math.min(parseTime - 50, 500); // Cap at 500ms for reasonable estimate
            }
        }

        return Math.round(tbt);
    }

    getLCP() {
        // Use observed LCP if available
        if (this.metrics.lcp) {
            return Math.round(this.metrics.lcp);
        }

        // Try to get from performance entries
        let lcp = 0;
        if (window.performance && window.performance.getEntriesByType) {
            try {
                const entries = window.performance.getEntriesByType('largest-contentful-paint');
                if (entries && entries.length > 0) {
                    lcp = Math.round(entries[entries.length - 1].startTime);
                }
            } catch (e) {
                console.log("LCP entries not available");
            }
        }

        // Fallback: estimate based on load event
        if (lcp === 0 && window.performance && window.performance.timing) {
            const timing = window.performance.timing;
            if (timing.loadEventStart && timing.navigationStart) {
                lcp = timing.loadEventStart - timing.navigationStart;
            }
        }

        return lcp;
    }

    calculateCLS() {
        // Calculate from observed layout shifts
        let cls = 0;
        let sessionValue = 0;
        let sessionEntries = [];

        this.layoutShifts.forEach(shift => {
            sessionEntries.push(shift);
            sessionValue += shift.value;

            // Check for session gap (1 second with no shifts)
            const timeSinceLastShift = sessionEntries.length > 1
                ? shift.startTime - sessionEntries[sessionEntries.length - 2].startTime
                : 0;

            if (timeSinceLastShift > 1000 || sessionEntries.length >= 5) {
                cls = Math.max(cls, sessionValue);
                sessionValue = shift.value;
                sessionEntries = [shift];
            }
        });

        cls = Math.max(cls, sessionValue);

        // Fallback: try to get from performance entries
        if (cls === 0 && window.performance && window.performance.getEntriesByType) {
            try {
                const entries = window.performance.getEntriesByType('layout-shift');
                entries.forEach(entry => {
                    if (!entry.hadRecentInput) {
                        cls += entry.value || 0;
                    }
                });
            } catch (e) {
                console.log("Layout shift entries not available");
            }
        }

        return Math.round(cls * 1000) / 1000; // Round to 3 decimal places
    }

    calculateINP() {
        // Calculate from observed interactions
        if (this.interactions.length > 0) {
            // INP is typically the 98th percentile of interactions
            const sorted = [...this.interactions].sort((a, b) => a - b);
            const index = Math.ceil(sorted.length * 0.98) - 1;
            return Math.round(sorted[Math.max(0, index)]);
        }

        // Fallback: try event timing entries
        let maxDuration = 0;
        if (window.performance && window.performance.getEntriesByType) {
            try {
                const entries = window.performance.getEntriesByType('event');
                entries.forEach(entry => {
                    if (entry.duration > maxDuration) {
                        maxDuration = entry.duration;
                    }
                });
            } catch (e) {
                // Event timing might not be supported
            }
        }

        // Return 0 if no interactions observed yet
        return Math.round(maxDuration);
    }

    calculateSpeedIndex() {
        const timing = window.performance && window.performance.timing;
        if (timing) {
            const navigationStart = timing.navigationStart || 0;
            const firstPaint = timing.domContentLoadedEventStart - navigationStart;
            const visuallyComplete = timing.loadEventEnd - navigationStart;

            // More sophisticated calculation considering progressive rendering
            const fcpTime = this.metrics.fcp || firstPaint;
            const lcpTime = this.metrics.lcp || visuallyComplete;

            // Weighted average giving more importance to earlier visual changes
            return Math.round((fcpTime * 0.4) + (lcpTime * 0.3) + (visuallyComplete * 0.3));
        }
        return 0;
    }

    estimateCPUUtilization() {
        // More realistic CPU benchmark with controlled duration
        const testDuration = 5; // 5ms test
        const startTime = performance.now();
        let operations = 0;

        // Perform lightweight operations for a fixed duration
        while (performance.now() - startTime < testDuration) {
            operations++;
            // Simple operation that won't be optimized away
            Math.sqrt(operations * 2.5);

            // Prevent infinite loop in case of timing issues
            if (operations > 100000) break;
        }

        // Normalize to percentage
        // Baseline: ~10000 operations in 5ms = 50% utilization
        const baselineOps = 10000;
        const utilization = Math.round((baselineOps / Math.max(operations, 1)) * 50);

        // Cap between 0-100
        return Math.max(0, Math.min(100, utilization));
    }
}

// Global collector instance
let globalCollector = null;

// Function to collect and send metrics
function collectAndSendMetrics() {
    // Create or reuse collector
    if (!globalCollector) {
        globalCollector = new WebMetricsCollector();
    }

    // Wait for page to stabilize and collect more data
    setTimeout(() => {
        const metrics = globalCollector.collectAllMetrics();

        // Send metrics to native app
        browser.runtime.sendNativeMessage("browser", {
            type: "WebMetrics",
            value: JSON.stringify(metrics)
        });

        console.log("Metrics collected and sent:", metrics);
    }, 3000); // Increased delay to allow more metrics to be collected
}

// Monitor page navigation
let lastUrl = window.location.href;

// Initial metrics collection
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => {
        // Initialize collector early
        globalCollector = new WebMetricsCollector();
        collectAndSendMetrics();
    });
} else {
    globalCollector = new WebMetricsCollector();
    collectAndSendMetrics();
}

// Monitor for URL changes (SPA navigation)
setInterval(() => {
    if (window.location.href !== lastUrl) {
        lastUrl = window.location.href;
        console.log("URL changed, collecting new metrics");
        // Reset collector for new page
        globalCollector = new WebMetricsCollector();
        collectAndSendMetrics();
    }
}, 1000);

// Also monitor for history changes
window.addEventListener('popstate', () => {
    console.log("History navigation detected");
    globalCollector = new WebMetricsCollector();
    collectAndSendMetrics();
});

// Monitor for page visibility changes
document.addEventListener('visibilitychange', () => {
    if (!document.hidden) {
        console.log("Page became visible, collecting metrics");
        collectAndSendMetrics();
    }
});

// Simulate user interaction after a delay for testing FID
setTimeout(() => {
    console.log("Simulating user interaction for FID measurement");
    const clickEvent = new MouseEvent('click', {
        view: window,
        bubbles: true,
        cancelable: true
    });
    document.dispatchEvent(clickEvent);
}, 5000);