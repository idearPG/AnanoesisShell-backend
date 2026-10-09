package com.ananoesis.shell.desktop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@link DesktopGuardFilter} 的分支覆盖补测。
 *
 * <p>WHY 独立测试文件：原类无单元测试，9 个分支（守卫关闭/开启、探活、引导换票、会话票校验）
 * 均未覆盖。</p>
 */
@DisplayName("DesktopGuardFilter")
class DesktopGuardFilterTest {

    private DesktopGuard guard;
    private DesktopSessionStore sessionStore;
    private DesktopGuardFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        guard = mock(DesktopGuard.class);
        sessionStore = mock(DesktopSessionStore.class);
        filter = new DesktopGuardFilter(guard, sessionStore);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        filterChain = mock(FilterChain.class);
    }

    @Test
    @DisplayName("守卫关闭时直通")
    void guardDisabledPassesThrough() throws Exception {
        when(guard.enabled()).thenReturn(false);
        filter.doFilterInternal(request, response, filterChain);
        org.mockito.Mockito.verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("守卫开启 + GET health 直通")
    void guardEnabledGetHealthPassesThrough() throws Exception {
        when(guard.enabled()).thenReturn(true);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/actuator/health");
        when(request.getContextPath()).thenReturn("");
        filter.doFilterInternal(request, response, filterChain);
        org.mockito.Mockito.verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("守卫开启 + 无会话票返回 401")
    void guardEnabledNoSessionCookieReturns401() throws Exception {
        when(guard.enabled()).thenReturn(true);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/hosts");
        when(request.getContextPath()).thenReturn("");
        when(request.getCookies()).thenReturn(null);
        filter.doFilterInternal(request, response, filterChain);
        org.mockito.Mockito.verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    @DisplayName("守卫开启 + 有效会话票直通")
    void guardEnabledValidSessionCookiePassesThrough() throws Exception {
        when(guard.enabled()).thenReturn(true);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/hosts");
        when(request.getContextPath()).thenReturn("");
        Cookie cookie = new Cookie("ananoesis_desktop_session", "valid-session");
        when(request.getCookies()).thenReturn(new Cookie[]{cookie});
        when(sessionStore.isValid("valid-session")).thenReturn(true);
        filter.doFilterInternal(request, response, filterChain);
        org.mockito.Mockito.verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("守卫开启 + 无效会话票返回 401")
    void guardEnabledInvalidSessionCookieReturns401() throws Exception {
        when(guard.enabled()).thenReturn(true);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/hosts");
        when(request.getContextPath()).thenReturn("");
        Cookie cookie = new Cookie("ananoesis_desktop_session", "invalid-session");
        when(request.getCookies()).thenReturn(new Cookie[]{cookie});
        when(sessionStore.isValid("invalid-session")).thenReturn(false);
        filter.doFilterInternal(request, response, filterChain);
        org.mockito.Mockito.verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    @DisplayName("守卫开启 + GET 根路径带 tk + 令牌正确换票 302")
    void guardEnabledBootstrapValidTokenRedirects() throws Exception {
        when(guard.enabled()).thenReturn(true);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/");
        when(request.getContextPath()).thenReturn("");
        when(request.getParameter("tk")).thenReturn("valid-launcher-token");
        when(guard.verifyLauncherToken("valid-launcher-token")).thenReturn(true);
        when(sessionStore.issue()).thenReturn("new-session-id");
        filter.doFilterInternal(request, response, filterChain);
        org.mockito.Mockito.verify(response).setStatus(HttpServletResponse.SC_FOUND);
        org.mockito.Mockito.verify(response).setHeader("Location", "/");
    }

    @Test
    @DisplayName("守卫开启 + GET 根路径带 tk + 令牌错误返回 401")
    void guardEnabledBootstrapInvalidTokenReturns401() throws Exception {
        when(guard.enabled()).thenReturn(true);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/");
        when(request.getContextPath()).thenReturn("");
        when(request.getParameter("tk")).thenReturn("invalid-launcher-token");
        when(guard.verifyLauncherToken("invalid-launcher-token")).thenReturn(false);
        filter.doFilterInternal(request, response, filterChain);
        org.mockito.Mockito.verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
