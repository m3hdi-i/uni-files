import json
import time
import statistics
from playwright.sync_api import sync_playwright, Playwright
from typing import Dict, List, Any, Optional

class WebMetricsCollector:
    def __init__(self):
        self.requests = []
        self.errors = []
        self.start_time = None
        self.memory_baseline = None
        
    def collect_metrics(self, page) -> Dict[str, Any]:
        """Collect comprehensive web performance metrics"""
        try:
            metrics = {}
            
            # Get Navigation Timing API metrics
            navigation_metrics = self._get_navigation_timing(page)
            metrics.update(navigation_metrics)
            
            # Get Web Vitals metrics
            web_vitals = self._get_web_vitals(page)
            metrics.update(web_vitals)
            
            # Get network metrics
            network_metrics = self._get_network_metrics()
            metrics.update(network_metrics)
            
            # Get memory metrics
            memory_metrics = self._get_memory_metrics(page)
            metrics.update(memory_metrics)
            
            return metrics
            
        except Exception as e:
            print(f"Error collecting metrics: {e}")
            return {}
    
    def _get_navigation_timing(self, page) -> Dict[str, Any]:
        """Get timing metrics from Navigation Timing API"""
        try:
            timing_script = """
            () => {
                const timing = performance.timing;
                const navigation = performance.getEntriesByType('navigation')[0];
                
                return {
                    // Basic timing metrics
                    pageLoadTime: timing.loadEventEnd - timing.navigationStart,
                    domInteractive: timing.domInteractive - timing.navigationStart,
                    domContentLoaded: timing.domContentLoadedEventEnd - timing.navigationStart,
                    timeToFirstByte: timing.responseStart - timing.navigationStart,
                    dnsLookupTime: timing.domainLookupEnd - timing.domainLookupStart,
                    connectionTime: timing.connectEnd - timing.connectStart,
                    
                    // Navigation API metrics (if available)
                    redirectTime: navigation ? navigation.redirectEnd - navigation.redirectStart : 0,
                    fetchStart: navigation ? navigation.fetchStart : timing.fetchStart - timing.navigationStart,
                    responseEnd: navigation ? navigation.responseEnd : timing.responseEnd - timing.navigationStart
                };
            }
            """
            
            timing_data = page.evaluate(timing_script)
            return {
                'page_load_time_ms': timing_data.get('pageLoadTime', 0),
                'dom_interactive_ms': timing_data.get('domInteractive', 0),
                'dom_content_loaded_ms': timing_data.get('domContentLoaded', 0),
                'time_to_first_byte_ms': timing_data.get('timeToFirstByte', 0),
                'dns_lookup_time_ms': timing_data.get('dnsLookupTime', 0),
                'connection_time_ms': timing_data.get('connectionTime', 0),
                'redirect_time_ms': timing_data.get('redirectTime', 0)
            }
            
        except Exception as e:
            print(f"Error getting navigation timing: {e}")
            return {}
    
    def _get_web_vitals(self, page) -> Dict[str, Any]:
        """Get Core Web Vitals and paint metrics"""
        try:
            web_vitals_script = """
            () => {
                return new Promise((resolve) => {
                    const metrics = {};
                    
                    // Get paint metrics
                    const paintEntries = performance.getEntriesByType('paint');
                    paintEntries.forEach(entry => {
                        if (entry.name === 'first-paint') {
                            metrics.firstPaint = entry.startTime;
                        } else if (entry.name === 'first-contentful-paint') {
                            metrics.firstContentfulPaint = entry.startTime;
                        }
                    });
                    
                    // Get LCP using PerformanceObserver
                    let lcpValue = 0;
                    try {
                        const observer = new PerformanceObserver((list) => {
                            const entries = list.getEntries();
                            const lastEntry = entries[entries.length - 1];
                            lcpValue = lastEntry.startTime;
                        });
                        observer.observe({ entryTypes: ['largest-contentful-paint'] });
                        
                        // Wait a bit for LCP to be captured
                        setTimeout(() => {
                            observer.disconnect();
                            metrics.largestContentfulPaint = lcpValue;
                            resolve(metrics);
                        }, 1000);
                    } catch (e) {
                        // Fallback if PerformanceObserver is not supported
                        metrics.largestContentfulPaint = 0;
                        resolve(metrics);
                    }
                });
            }
            """
            
            vitals_data = page.evaluate(web_vitals_script)
            
            # Get additional metrics using evaluate_handle for better compatibility
            try:
                # Try to get INP and TBT if available
                additional_metrics = page.evaluate("""
                () => {
                    const metrics = {};
                    
                    // Try to get Total Blocking Time approximation
                    const longTasks = performance.getEntriesByType('longtask') || [];
                    let totalBlockingTime = 0;
                    longTasks.forEach(task => {
                        if (task.duration > 50) {
                            totalBlockingTime += task.duration - 50;
                        }
                    });
                    metrics.totalBlockingTime = totalBlockingTime;
                    
                    return metrics;
                }
                """)
                vitals_data.update(additional_metrics)
            except:
                pass
            
            return {
                'first_paint_ms': vitals_data.get('firstPaint', 0),
                'first_contentful_paint_ms': vitals_data.get('firstContentfulPaint', 0),
                'largest_contentful_paint_ms': vitals_data.get('largestContentfulPaint', 0),
                'total_blocking_time_ms': vitals_data.get('totalBlockingTime', 0),
                'interaction_to_next_paint_ms': 0  # INP requires specific interaction measurement
            }
            
        except Exception as e:
            print(f"Error getting web vitals: {e}")
            return {}
    
    def _get_network_metrics(self) -> Dict[str, Any]:
        """Calculate network performance metrics from collected requests"""
        try:
            if not self.requests:
                return {}
            
            total_requests = len(self.requests)
            total_data = sum(req.get('size', 0) for req in self.requests)
            load_times = [req.get('duration', 0) for req in self.requests if req.get('duration', 0) > 0]
            
            mean_load_time = statistics.mean(load_times) if load_times else 0
            
            # Calculate throughput (bytes per second)
            total_duration = max(req.get('end_time', 0) for req in self.requests) - min(req.get('start_time', 0) for req in self.requests) if self.requests else 0
            throughput = (total_data / (total_duration / 1000)) if total_duration > 0 else 0
            
            # Calculate error rate
            error_count = len(self.errors)
            error_rate = (error_count / total_requests * 100) if total_requests > 0 else 0
            
            return {
                'total_requests': total_requests,
                'total_data_transferred_bytes': total_data,
                'mean_resource_load_time_ms': mean_load_time,
                'throughput_bytes_per_second': throughput,
                'error_rate_percent': error_rate,
                'total_errors': error_count
            }
            
        except Exception as e:
            print(f"Error calculating network metrics: {e}")
            return {}
    
    def _get_memory_metrics(self, page) -> Dict[str, Any]:
        """Get memory usage metrics"""
        try:
            memory_script = """
            () => {
                const metrics = {};
                
                // Get memory info if available (Chrome-specific)
                if (performance.memory) {
                    metrics.usedJSHeapSize = performance.memory.usedJSHeapSize;
                    metrics.totalJSHeapSize = performance.memory.totalJSHeapSize;
                    metrics.jsHeapSizeLimit = performance.memory.jsHeapSizeLimit;
                }
                
                return metrics;
            }
            """
            
            memory_data = page.evaluate(memory_script)
            
            peak_memory = memory_data.get('usedJSHeapSize', 0)
            memory_increase = peak_memory - (self.memory_baseline or 0)
            
            return {
                'peak_memory_usage_bytes': peak_memory,
                'memory_increase_bytes': memory_increase,
                'total_js_heap_size_bytes': memory_data.get('totalJSHeapSize', 0),
                'js_heap_size_limit_bytes': memory_data.get('jsHeapSizeLimit', 0),
                'cpu_utilization_percent': 0  # CPU monitoring requires additional tools
            }
            
        except Exception as e:
            print(f"Error getting memory metrics: {e}")
            return {}
    
    def setup_request_monitoring(self, page):
        """Set up request and response monitoring"""
        def handle_request(request):
            try:
                req_data = {
                    'url': request.url,
                    'method': request.method,
                    'start_time': time.time() * 1000,
                    'resource_type': request.resource_type
                }
                self.requests.append(req_data)
            except Exception as e:
                print(f"Error handling request: {e}")
        
        def handle_response(response):
            try:
                # Find the corresponding request
                for req in self.requests:
                    if req.get('url') == response.url and 'duration' not in req:
                        req['end_time'] = time.time() * 1000
                        req['duration'] = req['end_time'] - req['start_time']
                        req['status'] = response.status
                        req['size'] = len(response.body()) if response.body() else 0
                        
                        # Track errors
                        if response.status >= 400:
                            self.errors.append({
                                'url': response.url,
                                'status': response.status,
                                'error': f"HTTP {response.status}"
                            })
                        break
            except Exception as e:
                print(f"Error handling response: {e}")
        
        def handle_request_failed(request):
            try:
                self.errors.append({
                    'url': request.url,
                    'error': 'Request failed',
                    'failure_text': request.failure
                })
            except Exception as e:
                print(f"Error handling request failure: {e}")
        
        page.on('request', handle_request)
        page.on('response', handle_response)
        page.on('requestfailed', handle_request_failed)
    
    def get_baseline_memory(self, page):
        """Get baseline memory usage"""
        try:
            memory_data = page.evaluate("""
            () => {
                return performance.memory ? performance.memory.usedJSHeapSize : 0;
            }
            """)
            self.memory_baseline = memory_data
        except Exception as e:
            print(f"Error getting baseline memory: {e}")
            self.memory_baseline = 0

def run(playwright: Playwright):
    """Main function to run performance testing"""
    browser = None
    context = None
    page = None
    
    try:
        # Initialize metrics collector
        metrics_collector = WebMetricsCollector()
        
        # Set up mobile device
        pixel7 = playwright.devices['Pixel 7']
        context_args = {
            "viewport": pixel7["viewport"],
            "user_agent": pixel7["user_agent"],
            "device_scale_factor": pixel7.get("device_scale_factor", 1)
        }
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(**context_args)
        
        page = context.new_page()
        
        # Get baseline memory
        metrics_collector.get_baseline_memory(page)
        
        # Set up monitoring
        metrics_collector.setup_request_monitoring(page)
        
        # Record start time
        metrics_collector.start_time = time.time() * 1000
        
        print("Navigating to website...")
        page.goto("https://google.com", wait_until="networkidle")
        
        print(f"Page title: {page.title()}")
        
        # Wait a bit more for all metrics to be captured
        page.wait_for_timeout(3000)
        
        # Collect all metrics
        print("\nCollecting performance metrics...")
        metrics = metrics_collector.collect_metrics(page)
        
        if metrics:
            print("\n" + "="*60)
            print("WEBSITE PERFORMANCE METRICS")
            print("="*60)
            
            # Website Loading Metrics
            print("\n📊 WEBSITE LOADING METRICS:")
            print(f"  Page Load Time: {metrics.get('page_load_time_ms', 0):.2f} ms")
            print(f"  First Paint (FP): {metrics.get('first_paint_ms', 0):.2f} ms")
            print(f"  First Contentful Paint (FCP): {metrics.get('first_contentful_paint_ms', 0):.2f} ms")
            print(f"  Largest Contentful Paint (LCP): {metrics.get('largest_contentful_paint_ms', 0):.2f} ms")
            print(f"  DOM Interactive: {metrics.get('dom_interactive_ms', 0):.2f} ms")
            print(f"  DOM Content Loaded: {metrics.get('dom_content_loaded_ms', 0):.2f} ms")
            print(f"  Time to First Byte (TTFB): {metrics.get('time_to_first_byte_ms', 0):.2f} ms")
            
            # Network Performance Indicators
            print("\n🌐 NETWORK PERFORMANCE INDICATORS:")
            print(f"  Total Requests: {metrics.get('total_requests', 0)}")
            print(f"  Total Data Transferred: {metrics.get('total_data_transferred_bytes', 0):,} bytes")
            print(f"  Mean Resource Load Time: {metrics.get('mean_resource_load_time_ms', 0):.2f} ms")
            print(f"  DNS Lookup Time: {metrics.get('dns_lookup_time_ms', 0):.2f} ms")
            print(f"  Connection Time: {metrics.get('connection_time_ms', 0):.2f} ms")
            print(f"  Throughput: {metrics.get('throughput_bytes_per_second', 0):.2f} bytes/sec")
            print(f"  Error Rate: {metrics.get('error_rate_percent', 0):.2f}%")
            
            # User Experience Evaluation
            print("\n👤 USER EXPERIENCE EVALUATION:")
            print(f"  Total Blocking Time (TBT): {metrics.get('total_blocking_time_ms', 0):.2f} ms")
            print(f"  Interaction to Next Paint (INP): {metrics.get('interaction_to_next_paint_ms', 0):.2f} ms")
            
            # System Resource Analysis
            print("\n💾 SYSTEM RESOURCE ANALYSIS:")
            print(f"  Peak Memory Usage: {metrics.get('peak_memory_usage_bytes', 0):,} bytes")
            print(f"  Memory Increase: {metrics.get('memory_increase_bytes', 0):,} bytes")
            print(f"  Total JS Heap Size: {metrics.get('total_js_heap_size_bytes', 0):,} bytes")
            print(f"  CPU Utilization: {metrics.get('cpu_utilization_percent', 0):.2f}% (requires additional tooling)")
            
            # Save metrics to JSON file
            try:
                with open('performance_metrics.json', 'w') as f:
                    json.dump(metrics, f, indent=2)
                print(f"\n✅ Metrics saved to 'performance_metrics.json'")
            except Exception as e:
                print(f"❌ Error saving metrics to file: {e}")
        else:
            print("❌ No metrics could be collected")
            
    except Exception as e:
        print(f"❌ Error during execution: {e}")
        
    finally:
        # Clean up resources
        try:
            if page:
                page.close()
            if context:
                context.close()
            if browser:
                browser.close()
        except Exception as e:
            print(f"Error during cleanup: {e}")

# Main execution
if __name__ == "__main__":
    try:
        with sync_playwright() as playwright:
            run(playwright)
    except Exception as e:
        print(f"❌ Fatal error: {e}")
    
    print("\n🏁 Performance analysis complete!")