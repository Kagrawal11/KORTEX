package tests;

import base.BaseTest;
import org.testng.Assert;
import org.testng.annotations.Test;

public class SmokeTest extends BaseTest {

    @Test
    public void verifyExampleDomainTitle() {
        page.navigate("https://example.com");

        String title = page.title();
        System.out.println("Page title: " + title);

        page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions()
                .setPath(java.nio.file.Paths.get("screenshots/smoke-test.png")));

        Assert.assertEquals(title, "Example Domain");
    }
}