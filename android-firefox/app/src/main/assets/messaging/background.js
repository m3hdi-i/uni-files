browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
    return browser.runtime.sendNativeMessage("browser", message);
});