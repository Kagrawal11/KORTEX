package tests;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class SmokeTest {

    private Playwright playwright;
    private Browser browser;

    @BeforeClass
    public void setup() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(false)
        );
    }

    @Test
    public void verifyExampleDomainTitle() {
        Page page = browser.newPage();
        page.navigate("https://example.com");

        String title = page.title();
        System.out.println("Page title: " + title);

        page.screenshot(new Page.ScreenshotOptions()
                .setPath(java.nio.file.Paths.get("screenshots/smoke-test.png")));

        Assert.assertEquals(title, "Example Domain");
    }

    @AfterClass
    public void teardown() {
        browser.close();
        playwright.close();
    }
}