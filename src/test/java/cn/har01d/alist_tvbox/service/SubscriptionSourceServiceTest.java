package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.EmbyRepository;
import cn.har01d.alist_tvbox.entity.FeiniuRepository;
import cn.har01d.alist_tvbox.entity.JellyfinRepository;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubscriptionSourceServiceTest {
    @Test
    void exposesPianDanAsBuiltinNavigationSource() {
        PluginRepository pluginRepository = mock(PluginRepository.class);
        SettingRepository settingRepository = mock(SettingRepository.class);
        SiteRepository siteRepository = mock(SiteRepository.class);
        EmbyRepository embyRepository = mock(EmbyRepository.class);
        FeiniuRepository feiniuRepository = mock(FeiniuRepository.class);
        JellyfinRepository jellyfinRepository = mock(JellyfinRepository.class);
        when(settingRepository.findById("builtin_subscription_sources")).thenReturn(Optional.empty());
        when(siteRepository.findById(1)).thenReturn(Optional.empty());
        when(pluginRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of());
        when(embyRepository.count()).thenReturn(0L);
        when(feiniuRepository.count()).thenReturn(0L);
        when(jellyfinRepository.count()).thenReturn(0L);

        SubscriptionSourceService service = new SubscriptionSourceService(
                new AppProperties(),
                pluginRepository,
                settingRepository,
                siteRepository,
                embyRepository,
                feiniuRepository,
                jellyfinRepository,
                new ObjectMapper()
        );

        assertThat(service.findAll())
                .anySatisfy(source -> assertThat(source)
                        .returns("csp_PianDan", SubscriptionSourceService.ManagedSource::key)
                        .returns("片单导航", SubscriptionSourceService.ManagedSource::name)
                        .returns(true, SubscriptionSourceService.ManagedSource::builtin)
                        .returns(true, SubscriptionSourceService.ManagedSource::enabled));

        // WebHome 首页站与其它内置源同权管理:默认启用且居首位(站点选择器首位)
        List<SubscriptionSourceService.ManagedSource> all = service.findAll();
        assertThat(all.get(0))
                .returns("atv_home", SubscriptionSourceService.ManagedSource::key)
                .returns("影视首页", SubscriptionSourceService.ManagedSource::name)
                .returns(true, SubscriptionSourceService.ManagedSource::builtin)
                .returns(true, SubscriptionSourceService.ManagedSource::enabled)
                .returns(1, SubscriptionSourceService.ManagedSource::sortOrder);
    }
}
