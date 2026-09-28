import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import org.yaml.snakeyaml.Yaml;
import java.nio.file.*;
import java.util.*;

class BrowserSmoke {
    public static void main(String[] args)throws Exception{
        Map<String,Object> config=new Yaml().load(Files.readString(Path.of("src/main/resources/application-local.yml")));
        Map<?,?> platform=(Map<?,?>)config.get("nocode");
        Files.createDirectories(Path.of("tmp/verification/screens"));
        try(Playwright pw=Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1")));Browser browser=pw.chromium().launch(new BrowserType.LaunchOptions().setChannel("chrome").setHeadless(true))){
            var context=browser.newContext(new Browser.NewContextOptions().setViewportSize(1440,1000));var page=context.newPage();
            List<String> errors=new ArrayList<>();page.onPageError(errors::add);
            page.navigate("http://localhost:5173/");page.locator(".hero h1").waitFor();
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("tmp/verification/screens/home.png")).setFullPage(true));
            page.locator("a[href='/login']").click();
            page.locator("input[autocomplete='username']").fill(platform.get("admin-account").toString());
            page.locator("input[type='password']").fill(platform.get("admin-password").toString());
            page.locator("form.auth-form .button.full").click();
            page.waitForURL("http://localhost:5173/");page.locator("nav a[href='/apps']").click();
            page.locator(".app-card").first().waitFor();
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("tmp/verification/screens/apps.png")).setFullPage(true));
            page.navigate("http://localhost:5173/apps/1");page.locator("iframe").waitFor();
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("tmp/verification/screens/workbench.png")).setFullPage(true));
            page.locator(".segmented button").nth(1).click();
            page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("index.html").setExact(true)).waitFor();
            page.waitForFunction("document.querySelector('pre')?.textContent.includes('html')");
            if(!page.locator("pre").innerText().contains("html"))throw new AssertionError("Source viewer did not load HTML");
            page.navigate("http://localhost:5173/admin/users");page.locator(".table-panel tbody tr").first().waitFor();
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("tmp/verification/screens/admin.png")).setFullPage(true));
            page.navigate("http://localhost:5173/admin/messages");page.locator(".table-panel tbody tr").first().waitFor();
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("tmp/verification/screens/messages.png")).setFullPage(true));
            page.navigate("http://localhost:5173/profile");page.locator(".form-stack input").first().waitFor();
            if(!page.locator(".form-stack input").first().inputValue().equals(platform.get("admin-account").toString()))throw new AssertionError("Profile account mismatch");
            page.navigate("http://localhost:5173/apps/5");page.locator("iframe").waitFor();
            var generated=page.frameLocator("iframe");generated.locator("input[placeholder='输入任务']").fill("Browser verification task");generated.getByRole(AriaRole.BUTTON,new FrameLocator.GetByRoleOptions().setName("添加").setExact(true)).click();
            generated.getByText("Browser verification task",new FrameLocator.GetByTextOptions().setExact(true)).waitFor();generated.locator("input[type=checkbox]").check();
            if(!generated.locator("input[type=checkbox]").isChecked())throw new AssertionError("Generated Vue interaction failed");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("tmp/verification/screens/vue-workbench.png")).setFullPage(true));
            page.setViewportSize(390,844);page.navigate("http://localhost:5173/");page.locator(".hero h1").waitFor();page.locator(".app-card").first().waitFor();page.waitForFunction("!document.body.innerText.includes('正在加载作品')");page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("tmp/verification/screens/mobile.png")).setFullPage(true));
            if(!errors.isEmpty())throw new AssertionError("Browser runtime errors: "+errors);
            System.out.println("Browser smoke passed: home, login, apps, workbench, code viewer, user/message admin, profile, generated Vue add/complete interactions, mobile; no page errors.");
        }
    }
}
