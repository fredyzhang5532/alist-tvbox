package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.EmbyRepository;
import cn.har01d.alist_tvbox.entity.FeiniuRepository;
import cn.har01d.alist_tvbox.entity.JellyfinRepository;
import cn.har01d.alist_tvbox.entity.PlaybackTokenRepository;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.PluginFilterRepository;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.entity.SubscriptionRepository;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 上游订阅 spider 与本项目 spring.jar 的主 spider 位之争:
 * TVBox 只有唯一的全局 spider jar 位,上游订阅自带的 spider 若占据全局位,
 * 本项目 spring.jar(代理/播放同步等常驻服务)就不会随订阅加载。
 */
class SubscriptionServiceSpiderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 订阅源管理里的 WebHome 内置源条目(builtin-atv_home,findEnabledSources 消费形态)。 */
    private static final SubscriptionSourceService.SubscriptionSourceRef WEB_HOME_SOURCE =
            new SubscriptionSourceService.SubscriptionSourceRef("builtin-atv_home", true, "atv_home", "影视首页", null);

    @BeforeEach
    void setUp() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/sub/token/1");
        request.setServerName("atv.example");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void upstreamSpiderYieldsGlobalSlotToSpringJar() {
        SubscriptionService service = newService("""
                {
                  "spider": "http://up.example/spider.jar",
                  "sites": [
                    {"key": "up_csp", "name": "上游CSP", "type": 3, "api": "csp_XYQ"},
                    {"key": "up_t4", "name": "上游T4", "type": 3, "api": "http://up.example/api?ac=videolist"},
                    {"key": "up_selfjar", "name": "自带jar", "type": 3, "api": "csp_Other", "jar": "http://up.example/other.jar"}
                  ]
                }
                """);

        Map<String, Object> config = service.subscription("", "http://up.example/config.json", "", null);

        // 全局主 spider 位回归本项目 spring.jar,其代理服务随 TVBox 启动加载
        assertEquals("http://atv.example/spring.jar", config.get("spider"));

        Map<String, Object> upCsp = findSite(config, "up_csp");
        // 上游无 jar 的 csp 站点降级为站点级 jar,指向上游自己的 spider
        assertEquals("http://up.example/spider.jar", upCsp.get("jar"));

        // t4 接口(api 为 http)不需要 jar
        assertNull(findSite(config, "up_t4").get("jar"));

        // 上游站点自带的 jar 保持不变
        assertEquals("http://up.example/other.jar", findSite(config, "up_selfjar").get("jar"));
    }

    @Test
    void explicitOverrideSpiderWins() {
        SubscriptionService service = newService("""
                {"spider": "http://up.example/spider.jar", "sites": []}
                """);

        Map<String, Object> config = service.subscription("", "http://up.example/config.json",
                "{\"spider\":\"http://mine.example/my.jar\"}", null);

        // 用户显式指定的 spider 优先于默认值
        assertEquals("http://mine.example/my.jar", config.get("spider"));
    }

    @Test
    void globalSpiderDefaultsToSpringJarWithoutUpstream() {
        SubscriptionService service = newService("{}");

        Map<String, Object> config = service.subscription("", "", "", null);

        // 无上游时同样默认注入,保证 spring.jar 常驻服务随订阅加载
        assertEquals("http://atv.example/spring.jar", config.get("spider"));
        assertNotNull(config.get("sites"));
    }

    @Test
    void webHomeSiteFollowsClientCapability() {
        // 能力端(webhtv/fish):订阅源管理已启用 → 原生 homePage 形态,宿主直载页面
        WebHomeService capable = mock(WebHomeService.class);
        when(capable.isCapable(anyString())).thenReturn(true);
        SubscriptionService service = newService("{}", capable, List.of(WEB_HOME_SOURCE));
        Map<String, Object> config = service.subscription("", "http://up.example/config.json", "", null);
        List<Map<String, Object>> sites = (List<Map<String, Object>>) config.get("sites");
        assertEquals("atv_home", sites.get(0).get("key"));
        assertEquals("csp_Builtin", sites.get(0).get("api"));
        assertEquals("http://atv.example/webhome/app.html?token=-&v=16", sites.get(0).get("homePage"));

        // 普通端(原版 FongMi/OK影视等):csp_WebHome spider 形态,spring.jar 全屏 WebView 加载同一页面
        SubscriptionService plain = newService("{}", mock(WebHomeService.class), List.of(WEB_HOME_SOURCE));
        Map<String, Object> config2 = plain.subscription("", "http://up.example/config.json", "", null);
        Map<String, Object> atvHome = findSite(config2, "atv_home");
        assertEquals("csp_WebHome", atvHome.get("api"));
        assertNull(atvHome.get("homePage"));
        // ext = base64({"url": 页面地址}),同 csp_Media 字符串 ext 形态(宿主透传保底)
        String ext = new String(java.util.Base64.getDecoder().decode((String) atvHome.get("ext")));
        assertEquals("{\"url\":\"http://atv.example/webhome/app.html?token=-&v=16\"}", ext);
    }

    @Test
    void webHomeSiteFollowsSubscriptionSourceSwitch() {
        // 订阅源管理里禁用 atv_home:即便客户端能力达标也不再注入
        WebHomeService capable = mock(WebHomeService.class);
        when(capable.isCapable(anyString())).thenReturn(true);
        SubscriptionService service = newService("{}", capable, List.of());
        Map<String, Object> config = service.subscription("", "http://up.example/config.json", "", null);
        for (Map<String, Object> site : (List<Map<String, Object>>) config.get("sites")) {
            assertEquals(false, "atv_home".equals(site.get("key")));
        }
    }

    private Map<String, Object> findSite(Map<String, Object> config, String key) {
        List<Map<String, Object>> sites = (List<Map<String, Object>>) config.get("sites");
        return sites.stream().filter(s -> key.equals(s.get("key"))).findFirst().orElseThrow();
    }

    private SubscriptionService newService(String upstreamJson) {
        return newService(upstreamJson, mock(WebHomeService.class));
    }

    private SubscriptionService newService(String upstreamJson, WebHomeService webHomeService) {
        return newService(upstreamJson, webHomeService, List.of());
    }

    private SubscriptionService newService(String upstreamJson, WebHomeService webHomeService,
                                           List<SubscriptionSourceService.SubscriptionSourceRef> sources) {
        SettingRepository settingRepository = mock(SettingRepository.class);
        when(settingRepository.findById(anyString())).thenAnswer(invocation -> {
            Object key = invocation.getArgument(0);
            if (Constants.ALI_SECRET.equals(key)) {
                return Optional.of(new Setting(Constants.ALI_SECRET, "secret"));
            }
            return Optional.empty();
        });

        ShareRepository shareRepository = mock(ShareRepository.class);
        when(shareRepository.countByType(anyInt())).thenReturn(0);

        DriverAccountRepository driverAccountRepository = mock(DriverAccountRepository.class);
        when(driverAccountRepository.findByTypeAndMasterTrue(any())).thenReturn(Optional.empty());

        SubscriptionSourceService subscriptionSourceService = mock(SubscriptionSourceService.class);
        when(subscriptionSourceService.findEnabledSources()).thenReturn(sources);

        SubscriptionService service = new SubscriptionService(
                mock(Environment.class),
                new AppProperties(),
                new RestTemplateBuilder(),
                objectMapper,
                mock(JdbcTemplate.class),
                settingRepository,
                mock(SubscriptionRepository.class),
                mock(AccountRepository.class),
                mock(SiteRepository.class),
                shareRepository,
                driverAccountRepository,
                mock(EmbyRepository.class),
                mock(FeiniuRepository.class),
                mock(JellyfinRepository.class),
                mock(PluginRepository.class),
                mock(PluginFilterRepository.class),
                mock(AListLocalService.class),
                mock(ConfigFileService.class),
                mock(TenantService.class),
                mock(UserService.class),
                mock(FileDownloader.class),
                subscriptionSourceService,
                mock(PlaybackTokenRepository.class),
                webHomeService
        );

        ReflectionTestUtils.setField(service, "okHttpClient", httpServerReturning(upstreamJson));
        return service;
    }

    private static OkHttpClient httpServerReturning(String body) {
        return new OkHttpClient.Builder()
                .addInterceptor(chain -> new okhttp3.Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(ResponseBody.create(body, MediaType.get("application/json")))
                        .build())
                .build();
    }
}
