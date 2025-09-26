from playwright.sync_api import sync_playwright, Playwright
import time
import json
import statistics
from datetime import datetime
import psutil
import os


def collect_web_metrics(url, device_name='Pixel 7', headless=False, timeout=30000):
    """
    Collect comprehensive web page metrics using Playwright

    Args:
        url: The URL to analyze
        device_name: Device emulation (default: 'Pixel 7')
        headless: Whether to run browser in headless mode
        timeout: Page load timeout in milliseconds

    Returns:
        Dictionary of collected metrics
    """
    start_time = time.time()
    results = {
        'url': url,
        'timestamp': datetime.now().isoformat(),
        'device': device_name,
    }

    process = psutil.Process(os.getpid())
    initial_memory = process.memory_info().rss / 1024 / 1024  # MB

    with sync_playwright() as playwright:
        # Get device specifications if provided
        device_specs = {}
        if device_name in playwright.devices:
            device_specs = playwright.devices[device_name]

        # Launch browser with CDP connection for additional metrics
        browser = playwright.chromium.launch(headless=headless)

        try:
            # Create context with device emulation if specified
            context = browser.new_context(**device_specs) if device_specs else browser.new_context()

            # Create a new page and attach CDP session for network metrics
            page = context.new_page()
            client = page.context.new_cdp_session(page)

            # Enable relevant CDP domains
            client.send("Network.enable")
            client.send("Performance.enable")

            # Collect network metrics
            network_metrics = {
                'request_timings': [],
                'resource_sizes': [],
                'errors': 0,
                'requests_count': 0,
                'total_bytes': 0
            }

            # Track response received events
            def on_response_received(event):
                request_id = event['requestId']
                response = event['response']

                timing = response.get('timing', {})
                if timing:
                    network_metrics['request_timings'].append({
                        'url': response['url'],
                        'timing': timing,
                        'status': response['status'],
                        'size': response.get('encodedDataLength', 0)
                    })

                network_metrics['total_bytes'] += response.get('encodedDataLength', 0)
                network_metrics['requests_count'] += 1

                if 400 <= response['status'] < 600:
                    network_metrics['errors'] += 1

            client.on("Network.responseReceived", on_response_received)

            # Navigate to the URL and wait for load
            navigation_start = time.time()
            load_start_cpu = psutil.cpu_percent()

            response = page.goto(url, timeout=timeout, wait_until="load")

            # Collect main performance metrics after page load
            performance_metrics = client.send("Performance.getMetrics")

            # Measure memory and CPU after page load
            load_end_memory = process.memory_info().rss / 1024 / 1024  # MB
            load_end_cpu = psutil.cpu_percent()

            # Calculate Time to First Byte
            ttfb = response.request.timing.get('responseStart', 0) - response.request.timing.get('requestStart', 0)

            # Wait a bit to ensure all resources are loaded and events fired
            page.wait_for_timeout(2000)

            # Collect web vital metrics using JavaScript
            web_vitals = page.evaluate("""() => {
                const result = {};

                // Get paint timings
                const paintEntries = performance.getEntriesByType('paint');
                paintEntries.forEach(entry => {
                    result[entry.name] = entry.startTime;
                });

                // Get navigation timing
                const navEntry = performance.getEntriesByType('navigation')[0];
                if (navEntry) {
                    result.domContentLoaded = navEntry.domContentLoadedEventEnd;
                    result.loadEvent = navEntry.loadEventEnd;
                    result.domInteractive = navEntry.domInteractive;
                    result.connectEnd = navEntry.connectEnd;
                    result.connectStart = navEntry.connectStart;
                    result.domainLookupEnd = navEntry.domainLookupEnd;
                    result.domainLookupStart = navEntry.domainLookupStart;
                    result.fetchStart = navEntry.fetchStart;
                    result.requestStart = navEntry.requestStart;
                    result.responseStart = navEntry.responseStart;
                    result.responseEnd = navEntry.responseEnd;
                }

                // Get Largest Contentful Paint if available
                const lcpEntry = performance.getEntriesByType('largest-contentful-paint');
                if (lcpEntry && lcpEntry.length > 0) {
                    result.largestContentfulPaint = lcpEntry[0].startTime;
                } else {
                    // Fall back to largest paint entry from PerformanceObserver
                    if (window.LCP_VALUE) {
                        result.largestContentfulPaint = window.LCP_VALUE;
                    }
                }

                // Get Cumulative Layout Shift if available
                if (window.CLS_VALUE) {
                    result.cumulativeLayoutShift = window.CLS_VALUE;
                }

                // Resource timing
                const resourceEntries = performance.getEntriesByType('resource');
                result.resourceCount = resourceEntries.length;

                // Calculate Total Blocking Time (simplified approximation)
                const longTasks = performance.getEntriesByType('longtask') || [];
                result.totalBlockingTime = longTasks.reduce((sum, task) => sum + task.duration, 0);

                return result;
            }""")

            # Get First Input Delay and CLS using PerformanceObserver (setup script)
            page.evaluate("""() => {
                // Set up CLS observer
                let clsValue = 0;
                let clsEntries = [];

                new PerformanceObserver((entryList) => {
                    for (const entry of entryList.getEntries()) {
                        if (!entry.hadRecentInput) {
                            clsValue += entry.value;
                            clsEntries.push(entry);
                        }
                    }
                    window.CLS_VALUE = clsValue;
                }).observe({type: 'layout-shift', buffered: true});

                // Set up LCP observer
                new PerformanceObserver((entryList) => {
                    const entries = entryList.getEntries();
                    const lastEntry = entries[entries.length - 1];
                    window.LCP_VALUE = lastEntry.startTime;
                }).observe({type: 'largest-contentful-paint', buffered: true});

                // Set up FID observer
                new PerformanceObserver((entryList) => {
                    for (const entry of entryList.getEntries()) {
                        window.FID_VALUE = entry.processingStart - entry.startTime;
                        break;
                    }
                }).observe({type: 'first-input', buffered: true});
            }""")

            # Interact with the page to measure INP (simulate a click on the first button or link)
            try:
                elements = page.query_selector_all('button, a[href]')
                if elements and len(elements) > 0:
                    # Record the time before interaction
                    page.evaluate("""() => {
                        window.INP_START = performance.now();
                    }""")

                    # Perform the interaction
                    elements[0].click(force=True, timeout=5000)

                    # Measure time to next paint
                    inp = page.evaluate("""() => {
                        return performance.now() - window.INP_START;
                    }""")

                    results['interactionToNextPaint'] = inp
            except Exception as e:
                print(f"Could not measure INP: {e}")

            # Get Speed Index using the Speedline library integration if available
            # Note: This would typically require a custom solution and is an approximation
            try:
                # This is a simplified approximation using visual completeness
                frames = []
                for i in range(10):  # Capture 10 frames
                    frames.append({
                        'timestamp': i * 100,
                        'screenshot': page.screenshot(type='jpeg', quality=30)
                    })
                # A real implementation would analyze these frames to calculate Speed Index
                # For now, we just estimate it based on FCP and LCP values
                if 'first-contentful-paint' in web_vitals and 'largestContentfulPaint' in web_vitals:
                    results['speedIndex'] = (web_vitals['first-contentful-paint'] + web_vitals[
                        'largestContentfulPaint']) / 2
            except Exception as e:
                print(f"Could not estimate Speed Index: {e}")

            # Calculate additional metrics
            try:
                # Calculate Mean Resource Load Time
                if network_metrics['request_timings']:
                    load_times = [timing['timing'].get('receiveHeadersEnd', 0) - timing['timing'].get('requestTime', 0)
                                  for timing in network_metrics['request_timings']
                                  if timing['timing'].get('requestTime') is not None]
                    if load_times:
                        results['meanResourceLoadTime'] = statistics.mean(load_times) * 1000  # Convert to ms

                # Error Rate
                results['errorRate'] = (network_metrics['errors'] / network_metrics['requests_count'] * 100) if \
                network_metrics['requests_count'] > 0 else 0

                # DNS Lookup Time
                if 'domainLookupStart' in web_vitals and 'domainLookupEnd' in web_vitals:
                    results['dnsLookupTime'] = web_vitals['domainLookupEnd'] - web_vitals['domainLookupStart']

                # Latency/RTT (approximated)
                if 'connectStart' in web_vitals and 'connectEnd' in web_vitals:
                    results['connectionTime'] = web_vitals['connectEnd'] - web_vitals['connectStart']

                # Calculate throughput (bytes per second)
                page_load_time = web_vitals.get('loadEvent', 0) / 1000  # Convert to seconds
                if page_load_time > 0:
                    results['throughputBytesPerSecond'] = network_metrics['total_bytes'] / page_load_time
            except Exception as e:
                print(f"Error calculating additional metrics: {e}")

            # Capture final performance and memory usage
            final_memory = process.memory_info().rss / 1024 / 1024  # MB

            # Compile all metrics
            results.update({
                # Network metrics
                'totalRequests': network_metrics['requests_count'],
                'totalBytes': network_metrics['total_bytes'],
                'timeToFirstByte': ttfb,

                # Web vital metrics from JavaScript
                'firstPaint': web_vitals.get('first-paint'),
                'firstContentfulPaint': web_vitals.get('first-contentful-paint'),
                'largestContentfulPaint': web_vitals.get('largestContentfulPaint'),
                'domInteractive': web_vitals.get('domInteractive'),
                'domContentLoaded': web_vitals.get('domContentLoaded'),
                'pageLoadTime': web_vitals.get('loadEvent'),
                'cumulativeLayoutShift': web_vitals.get('cumulativeLayoutShift', 0),
                'totalBlockingTime': web_vitals.get('totalBlockingTime', 0),

                # Memory and CPU metrics
                'memoryIncreaseMB': final_memory - initial_memory,
                'peakMemoryUsageMB': final_memory,
                'cpuUtilization': load_end_cpu,

                # First Input Delay (if measured)
                'firstInputDelay': page.evaluate("window.FID_VALUE || null")
            })

            # Clean up and close
            page.close()
            browser.close()

            return results

        except Exception as e:
            print(f"Error collecting metrics: {e}")
            browser.close()
            return {"error": str(e)}


def run_analysis(url, device_name='Pixel 7', output_file=None):
    """
    Run the analysis and save results to a file if specified

    Args:
        url: URL to analyze
        device_name: Device to emulate
        output_file: Path to save results (JSON format)
    """
    print(f"Analyzing {url} on {device_name}...")
    metrics = collect_web_metrics(url, device_name)

    # Pretty print results
    print("\n==== WEB PAGE METRICS REPORT ====")
    print(f"URL: {metrics.get('url')}")
    print(f"Device: {metrics.get('device')}")
    print(f"Timestamp: {metrics.get('timestamp')}")
    print("\n--- LOADING METRICS ---")

    # Safe printing functions to handle None values
    def safe_print(label, key, unit="", format_str=".2f"):
        value = metrics.get(key)
        if value is not None:
            print(f"{label}: {value:{format_str}} {unit}")
        else:
            print(f"{label}: N/A")

    safe_print("Page Load Time", "pageLoadTime", "ms")
    safe_print("First Paint", "firstPaint", "ms")
    safe_print("First Contentful Paint", "firstContentfulPaint", "ms")
    safe_print("Largest Contentful Paint", "largestContentfulPaint", "ms")
    safe_print("DOM Interactive", "domInteractive", "ms")
    safe_print("DOM Content Loaded", "domContentLoaded", "ms")
    safe_print("Time to First Byte", "timeToFirstByte", "ms")
    safe_print("Speed Index", "speedIndex", "ms")

    print("\n--- NETWORK METRICS ---")
    safe_print("Total Requests", "totalRequests", "", "")

    total_bytes = metrics.get('totalBytes')
    if total_bytes is not None:
        print(f"Total Data Transferred: {total_bytes / 1024:.2f} KB")
    else:
        print(f"Total Data Transferred: N/A")

    safe_print("Mean Resource Load Time", "meanResourceLoadTime", "ms")
    safe_print("DNS Lookup Time", "dnsLookupTime", "ms")
    safe_print("Connection Time (RTT approx)", "connectionTime", "ms")

    throughput = metrics.get('throughputBytesPerSecond')
    if throughput is not None:
        print(f"Throughput: {throughput / 1024:.2f} KB/s")
    else:
        print(f"Throughput: N/A")

    safe_print("Error Rate", "errorRate", "%")

    print("\n--- INTERACTIVITY METRICS ---")
    safe_print("Total Blocking Time", "totalBlockingTime", "ms")
    safe_print("First Input Delay", "firstInputDelay", "ms")
    safe_print("Interaction to Next Paint", "interactionToNextPaint", "ms")
    safe_print("Cumulative Layout Shift", "cumulativeLayoutShift", "")

    print("\n--- RESOURCE USAGE ---")
    safe_print("Peak Memory Usage", "peakMemoryUsageMB", "MB")
    safe_print("Memory Increase", "memoryIncreaseMB", "MB")
    safe_print("CPU Utilization", "cpuUtilization", "%")

    # Save to file if specified
    if output_file:
        with open(output_file, 'w') as f:
            json.dump(metrics, f, indent=2)
        print(f"\nDetailed results saved to {output_file}")

    return metrics


if __name__ == "__main__":
    # Run with default parameters
    url = "https://google.com"
    run_analysis(url)