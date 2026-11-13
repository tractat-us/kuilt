// Runs :kuilt-identity's wasmJs tests in Playwright's WebKit instead of ChromeHeadless (#3246).
//
// Safari and WebKit refuse a compressed P-256 `importKey("raw")` (#3232); Chromium, the JDK and
// CryptoKit accept it. So only this engine can see the module import a compressed point again.
// Kotlin's Karma integration appends this file to karma.conf.js after its own browser setup, so
// the browser list below replaces the ChromeHeadless one the Gradle DSL had to name.
//
// Why a launcher of our own rather than karma-webkit-launcher: on macOS that one runs
// Playwright's pw_run.sh as a plain process, the WebKit app it starts detaches from it, and the
// launcher's cleanup looks for it with `ps | grep`, finds nothing, and throws. The build then never
// finishes and the browser is orphaned. Playwright's own `launch` keeps the browser on a pipe it
// owns, so `close` ends it, and the orphan guard beside this file sees the spawn.
const { webkit } = require("playwright-core");

function PlaywrightWebKit(baseBrowserDecorator, logger) {
    baseBrowserDecorator(this);
    const log = logger.create("launcher.PlaywrightWebKit");
    let browser = null;

    this._start = (url) => {
        webkit.launch({ headless: true })
            .then(async (launched) => {
                browser = launched;
                // The engine build, so a run's log says which WebKit it measured.
                log.info(`Playwright WebKit ${launched.version()}`);
                const page = await launched.newPage();
                await page.goto(url);
            })
            .catch((error) => {
                log.error(`Playwright WebKit failed to start: ${error && error.stack || error}`);
                this._done("failure");
            });
    };

    this.on("kill", (done) => {
        const closing = browser ? browser.close() : Promise.resolve();
        browser = null;
        closing
            .catch((error) => log.warn(`closing Playwright WebKit: ${error}`))
            .then(() => done());
    });
}
PlaywrightWebKit.prototype.name = "PlaywrightWebKit";
PlaywrightWebKit.$inject = ["baseBrowserDecorator", "logger"];

config.plugins = config.plugins || [];
config.plugins.push({ "launcher:PlaywrightWebKit": ["type", PlaywrightWebKit] });
config.browsers = ["PlaywrightWebKit"];
