package com.srm.gps.application.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Every /api/portal/** request needs a valid sign-in token (Authorization: Bearer ...), or it is answered 401
 * before any controller runs. The verified user is put on the request; the portal controllers take the role and the
 * company from there only, so a transporter can never ask for another transporter's shipments by changing a URL.
 */
public class PortalAuthFilter extends OncePerRequestFilter {

    /** Registers the filter for /api/portal/* only (a plain filter bean would run on every URL). */
    @Configuration
    public static class Registration {

        @Bean
        public FilterRegistrationBean<PortalAuthFilter> portalAuthFilter(PortalTokens tokens) {
            FilterRegistrationBean<PortalAuthFilter> registration = new FilterRegistrationBean<>(new PortalAuthFilter(tokens));
            registration.addUrlPatterns("/api/portal/*");
            registration.setName("portalAuthFilter");
            return registration;
        }
    }

    private final PortalTokens tokens;

    PortalAuthFilter(PortalTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/portal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        Optional<PortalUser> user = header != null && header.startsWith("Bearer ")
                ? tokens.verify(header.substring("Bearer ".length()).trim()) : Optional.empty();
        if (user.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Sign in to use the portal\"}");
            return;
        }
        request.setAttribute(PortalUser.REQUEST_ATTRIBUTE, user.get());
        chain.doFilter(request, response);
    }
}
