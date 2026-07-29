package base;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;

public class BaseTest {

    protected Playwright playwright;
    protected Browser browser;
    protected Page page;

    private static final boolean HEADLESS =
            Boolean.parseBoolean(System.getProperty("headless", "true"));

    @BeforeClass
    public void setupClass() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(HEADLESS)
        );
    }

    @BeforeMethod
    public void setupMethod() {
        page = browser.newPage();
    }

    @AfterMethod
    public void teardownMethod() {
        if (page != null) {
            page.close();
        }
    }

    @AfterClass
    public void teardownClass() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }
}